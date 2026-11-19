// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import CoreImage
import CoreVideo
import SwiftUI
@preconcurrency import WebRTC

struct CallVideoView: NSViewRepresentable {
    let track: RTCVideoTrack
    var mirrored = false

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        view.layer?.contentsGravity = .resizeAspect
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        view.layer?.setAffineTransform(CGAffineTransform(scaleX: mirrored ? -1 : 1, y: 1))
        context.coordinator.bind(track, to: view)
    }

    static func dismantleNSView(_ view: NSView, coordinator: Coordinator) {
        coordinator.detach()
        view.layer?.contents = nil
    }

    @MainActor
    final class Coordinator {
        private var track: RTCVideoTrack?
        private var renderer: CallFrameRenderer?

        func bind(_ track: RTCVideoTrack, to view: NSView) {
            if self.track === track { return }
            detach()
            view.layer?.contents = nil
            let renderer = CallFrameRenderer { [weak view] image in
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                view?.layer?.contents = image
                CATransaction.commit()
            }
            self.track = track
            self.renderer = renderer
            track.add(renderer)
        }

        func detach() {
            if let renderer {
                renderer.invalidate()
                track?.remove(renderer)
            }
            track = nil
            renderer = nil
        }
    }
}

/// Owns at most one pending frame in addition to the one being drawn.
private final class CallFrameRenderer: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private static let imageContext = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()
    private let renderQueue = DispatchQueue(label: "org.msgat.call-video", qos: .userInteractive)
    private var display: (@MainActor (CGImage) -> Void)?
    private var latest: RTCVideoFrame?
    private var drawing = false

    init(display: @escaping @MainActor (CGImage) -> Void) { self.display = display }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        let start = lock.withLock {
            guard display != nil else { return false }
            latest = frame
            if drawing { return false }
            drawing = true
            return true
        }
        if start { renderQueue.async { [self] in drawNext() } }
    }

    func invalidate() {
        lock.withLock {
            display = nil
            latest = nil
        }
    }

    private func drawNext() {
        let frame = lock.withLock {
            let value = latest
            latest = nil
            return value
        }
        let image = autoreleasepool { frame.flatMap(makeImage) }
        DispatchQueue.main.async { [self] in
            if let image, let show = lock.withLock({ display }) { show(image) }
            let again = lock.withLock {
                if display != nil && latest != nil { return true }
                drawing = false
                return false
            }
            if again { renderQueue.async { [self] in drawNext() } }
        }
    }

    private func makeImage(_ frame: RTCVideoFrame) -> CGImage? {
        let width = Int(frame.width), height = Int(frame.height)
        guard width > 0, height > 0, width <= 3840, height <= 3840, width * height <= 8_294_400 else { return nil }
        var image: CIImage
        if let buffer = frame.buffer as? RTCCVPixelBuffer {
            image = CIImage(cvPixelBuffer: buffer.pixelBuffer)
            let crop = CGRect(x: CGFloat(buffer.cropX), y: image.extent.height - CGFloat(buffer.cropY) - CGFloat(buffer.cropHeight),
                              width: CGFloat(buffer.cropWidth), height: CGFloat(buffer.cropHeight))
            if !crop.isEmpty && image.extent.contains(crop) { image = image.cropped(to: crop) }
        } else {
            guard let pixel = makePixelBuffer(frame.buffer.toI420(), width: width, height: height) else { return nil }
            image = CIImage(cvPixelBuffer: pixel).cropped(to: CGRect(x: 0, y: CGFloat(height % 2), width: CGFloat(width), height: CGFloat(height)))
        }
        let orientation: Int32 = switch frame.rotation.rawValue {
        case 90: 6
        case 180: 3
        case 270: 8
        default: 1
        }
        image = image.oriented(forExifOrientation: orientation)
        let scale = min(1, 1920 / image.extent.width, 1080 / image.extent.height)
        if scale < 1 { image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) }
        return Self.imageContext.createCGImage(image, from: image.extent)
    }

    private func makePixelBuffer(_ frame: RTCI420Buffer, width: Int, height: Int) -> CVPixelBuffer? {
        let evenWidth = (width + 1) & ~1, evenHeight = (height + 1) & ~1
        var buffer: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, evenWidth, evenHeight, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                  nil, &buffer) == kCVReturnSuccess, let buffer,
              CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let luma = CVPixelBufferGetBaseAddressOfPlane(buffer, 0),
              let chroma = CVPixelBufferGetBaseAddressOfPlane(buffer, 1)?.assumingMemoryBound(to: UInt8.self) else { return nil }
        let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
        for row in 0..<evenHeight {
            let dest = luma.advanced(by: row * lumaStride)
            memcpy(dest, frame.dataY.advanced(by: min(row, height - 1) * Int(frame.strideY)), width)
            if evenWidth != width { dest.storeBytes(of: UInt8(16), toByteOffset: width, as: UInt8.self) }
        }
        for row in 0..<(evenHeight / 2) {
            let dest = chroma.advanced(by: row * chromaStride)
            let u = frame.dataU.advanced(by: row * Int(frame.strideU))
            let v = frame.dataV.advanced(by: row * Int(frame.strideV))
            for column in 0..<(evenWidth / 2) {
                dest[2 * column] = u[column]
                dest[2 * column + 1] = v[column]
            }
        }
        return buffer
    }
}
