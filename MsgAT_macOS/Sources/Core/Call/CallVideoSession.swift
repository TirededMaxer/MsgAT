// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Combine
@preconcurrency import AVFoundation
@preconcurrency import WebRTC

/// The call and its camera commands are owned by the main thread. Capture callbacks
/// run on WebRTC's capture queue and are fenced before enabling the outgoing track.
final class CallVideoSession: ObservableObject, @unchecked Sendable {
    let localTrack: RTCVideoTrack
    @Published private(set) var remoteTrack: RTCVideoTrack?
    @Published private(set) var cameraEnabled = false
    @Published private(set) var cameraBusy = false
    @Published private(set) var cameraErrorKey: String?
    @Published private(set) var selectedDevice: String?

    private let capturer: RTCCameraVideoCapturer
    private var cameraTask: Task<Void, Never>?
    private var interruption: NSObjectProtocol?
    private var generation: UInt64 = 0
    private var running = false
    private var closed = false

    init(factory: RTCPeerConnectionFactory) {
        let source = factory.videoSource()
        localTrack = factory.videoTrack(with: source, trackId: "msgat-video")
        localTrack.isEnabled = false
        capturer = RTCCameraVideoCapturer(delegate: source)
        interruption = NotificationCenter.default.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
                                                               object: capturer.captureSession, queue: .main) { [weak self] _ in
            guard let self, !self.closed else { return }
            self.disableCamera()
            self.cameraErrorKey = "call_camera_stopped"
        }
    }

    deinit {
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
    }

    var devices: [AVCaptureDevice] { RTCCameraVideoCapturer.captureDevices() }

    func setRemoteTrack(_ track: RTCVideoTrack) {
        guard !closed, remoteTrack?.trackId != track.trackId else { return }
        remoteTrack = track
    }

    func removeRemoteTrack(_ track: RTCVideoTrack) {
        if remoteTrack?.trackId == track.trackId { remoteTrack = nil }
    }

    @MainActor
    func enableCamera(deviceID: String? = nil) {
        guard !closed, !cameraBusy, NSApp.isActive else { return }
        cameraErrorKey = nil
        cameraBusy = true
        generation &+= 1
        let revision = generation
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: break
        case .notDetermined:
            Task { @MainActor [weak self] in
                let access = await AVCaptureDevice.requestAccess(for: .video)
                guard let self, self.generation == revision, !self.closed else { return }
                self.cameraBusy = false
                if access { self.enableCamera(deviceID: deviceID) }
                else { self.cameraErrorKey = "call_camera_permission" }
            }
            return
        default:
            cameraBusy = false
            cameraErrorKey = "call_camera_permission"
            return
        }
        let previous = cameraTask
        cameraTask = Task { @MainActor [self] in
            await previous?.value
            defer { if generation == revision { cameraBusy = false } }
            guard generation == revision, !closed, NSApp.isActive else { return }
            let available = devices
            let requested = deviceID ?? selectedDevice
            guard let camera = available.first(where: { $0.uniqueID == requested }) ?? available.first,
                  let configuration = preferredFormat(for: camera) else {
                cameraErrorKey = "call_camera_unavailable"
                return
            }
            if running {
                localTrack.isEnabled = false
                cameraEnabled = false
                await stopDevice()
            }
            guard generation == revision, !closed, NSApp.isActive else { return }
            do {
                try await startDevice(camera, format: configuration.format, fps: configuration.fps)
                guard generation == revision, !closed, NSApp.isActive else { await stopDevice(); return }
                selectedDevice = camera.uniqueID
                localTrack.isEnabled = true
                cameraEnabled = true
            } catch {
                await stopDevice()
                if generation == revision { cameraErrorKey = "call_camera_open_failed" }
            }
        }
    }

    func disableCamera() {
        guard cameraEnabled || cameraBusy || running else { return }
        generation &+= 1
        let revision = generation
        localTrack.isEnabled = false
        cameraEnabled = false
        cameraBusy = true
        let previous = cameraTask
        cameraTask = Task { @MainActor [self] in
            await previous?.value
            await stopDevice()
            if generation == revision { cameraBusy = false }
        }
    }

    func close() -> Task<Void, Never>? {
        closed = true
        remoteTrack = nil
        disableCamera()
        return cameraTask
    }

    @MainActor
    private func startDevice(_ device: AVCaptureDevice, format: AVCaptureDevice.Format, fps: Int) async throws {
        running = true
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            capturer.startCapture(with: device, format: format, fps: fps, completionHandler: { error in
                if let error { done.resume(throwing: error) } else { done.resume() }
            })
        }
    }

    @MainActor
    private func stopDevice() async {
        guard running else { return }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            capturer.stopCapture(completionHandler: { done.resume() })
        }
        running = false
    }

    private func preferredFormat(for device: AVCaptureDevice) -> (format: AVCaptureDevice.Format, fps: Int)? {
        RTCCameraVideoCapturer.supportedFormats(for: device).compactMap { format -> (format: AVCaptureDevice.Format, fps: Int, score: Int)? in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let width = Int(dimensions.width), height = Int(dimensions.height)
            guard width > 0, height > 0, width <= 1920, height <= 1080 else { return nil }
            let rates = format.videoSupportedFrameRateRanges.compactMap { range -> Int? in
                let value = min(30, Int(range.maxFrameRate.rounded(.down)))
                return value >= 10 && Double(value) >= range.minFrameRate ? value : nil
            }
            guard let fps = rates.max() else { return nil }
            return (format: format, fps: fps, score: abs(width - 640) + abs(height - 480))
        }.min { $0.score < $1.score }.map { ($0.format, $0.fps) }
    }
}
