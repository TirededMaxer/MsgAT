import AVFoundation
import CoreAudio
#if canImport(MsgATNativeAudio)
import MsgATNativeAudio
#endif

/// WebRTC's public PCM device interface, with output routing owned by AVAudioEngine.
final class MacCallAudioDevice: NSObject, RTCAudioDevice, @unchecked Sendable {
    struct Selection: Sendable, Equatable {
        var input: AudioDeviceID? = nil
        var output: AudioDeviceID? = nil
    }

    private let inputEngine = AVAudioEngine()
    private let outputEngine = AVAudioEngine()
    private let delegateLock = NSLock()
    private var audioDelegate: RTCAudioDeviceDelegate?
    private var failureHandler: (@Sendable () -> Void)?
    private var routeChangeHandler: (@Sendable (Selection) -> Void)?
    private var routes = Selection()
    private var routeRefreshRevision: UInt64 = 0
    private let routeQueue = DispatchQueue(label: "org.msgat.call.output-route")
    private var routeListeners: [AudioObjectPropertySelector: AudioObjectPropertyListenerBlock] = [:]
    private var sourceNode: AVAudioSourceNode?
    private var sinkNode: AVAudioSinkNode?
    private var inputObserver: NSObjectProtocol?
    private var outputObserver: NSObjectProtocol?
    private var inputRate: Double = 48_000
    private let outputRate: Double = 48_000
    private let capacity: AVAudioFrameCount = 16_384

    private(set) var isInitialized = false
    private(set) var isPlayoutInitialized = false
    private(set) var isRecordingInitialized = false
    private(set) var isPlaying = false
    private(set) var isRecording = false
    var deviceInputSampleRate: Double { inputRate }
    var deviceOutputSampleRate: Double { outputRate }
    var inputNumberOfChannels: Int { 1 }
    var outputNumberOfChannels: Int { 1 }
    var inputIOBufferDuration: TimeInterval { ioBufferDuration(inputEngine.inputNode) }
    var outputIOBufferDuration: TimeInterval { ioBufferDuration(outputEngine.outputNode) }
    var inputLatency: TimeInterval { inputEngine.inputNode.auAudioUnit.deviceInputLatency }
    var outputLatency: TimeInterval { outputEngine.outputNode.auAudioUnit.deviceOutputLatency }

    func setFailureHandler(_ handler: @escaping @Sendable () -> Void) {
        delegateLock.lock(); failureHandler = handler; delegateLock.unlock()
    }

    func setRouteChangeHandler(_ handler: @escaping @Sendable (Selection) -> Void) {
        delegateLock.lock(); routeChangeHandler = handler; delegateLock.unlock()
    }

    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool {
        delegateLock.lock(); audioDelegate = delegate; delegateLock.unlock()
        isInitialized = true
        inputObserver = observe(inputEngine)
        outputObserver = observe(outputEngine)
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.systemRouteChanged()
        }
        for selector in [kAudioHardwarePropertyDefaultInputDevice, kAudioHardwarePropertyDefaultOutputDevice, kAudioHardwarePropertyDevices] {
            var address = propertyAddress(selector)
            let status = AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, routeQueue, listener)
            if status == noErr { routeListeners[selector] = listener }
            else { log(AudioError.routeListener(status)) }
        }
        return true
    }

    func terminateDevice() -> Bool {
        _ = stopRecording(); _ = stopPlayout()
        if let sinkNode { inputEngine.detach(sinkNode); self.sinkNode = nil }
        if let sourceNode { outputEngine.detach(sourceNode); self.sourceNode = nil }
        if let inputObserver { NotificationCenter.default.removeObserver(inputObserver) }
        if let outputObserver { NotificationCenter.default.removeObserver(outputObserver) }
        inputObserver = nil; outputObserver = nil
        for (selector, listener) in routeListeners {
            var address = propertyAddress(selector)
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, routeQueue, listener)
        }
        routeListeners.removeAll()
        isInitialized = false; isRecordingInitialized = false; isPlayoutInitialized = false
        delegateLock.withLock {
            audioDelegate = nil
            routeRefreshRevision &+= 1
        }
        return true
    }

    func initializeRecording() -> Bool {
        do { try configureInput(); isRecordingInitialized = true; return true }
        catch { reportDeviceFailure(error); return false }
    }

    func initializePlayout() -> Bool {
        do { try configureOutput(); isPlayoutInitialized = true; return true }
        catch { reportDeviceFailure(error); return false }
    }

    func startRecording() -> Bool {
        do { try inputEngine.start(); isRecording = true; return true }
        catch { reportDeviceFailure(error); return false }
    }

    func startPlayout() -> Bool {
        do { try outputEngine.start(); isPlaying = true; return true }
        catch { reportDeviceFailure(error); return false }
    }

    func stopRecording() -> Bool {
        inputEngine.stop(); isRecording = false
        if let delegate = delegateSnapshot() {
            delegate.dispatchSync { delegate.notifyAudioInputInterrupted() }
        }
        return true
    }

    func stopPlayout() -> Bool {
        outputEngine.stop(); isPlaying = false
        if let delegate = delegateSnapshot() {
            delegate.dispatchSync { delegate.notifyAudioOutputInterrupted() }
        }
        return true
    }

    func resetRoutes() {
        delegateLock.withLock { routes = Selection() }
    }

    /// The I/O units are reconfigured on WebRTC's ADM thread.
    func selectRoute(_ device: AudioDeviceID?, input: Bool, completion: @escaping @Sendable (Bool) -> Void) {
        guard let delegate = delegateSnapshot() else { completion(false); return }
        delegate.dispatchAsync { [weak self, weak delegate] in
            guard let self, let delegate, self.delegateSnapshot() === delegate, self.isInitialized else {
                completion(false); return
            }
            guard (input ? self.isRecordingInitialized : self.isPlayoutInitialized) else { completion(false); return }
            let previous = self.preferredRoute(input: input)
            if previous == device { completion(true); return }
            self.setPreferredRoute(device, input: input)
            do {
                try self.rebuildRoute(input: input, delegate: delegate)
                self.publishRoutes()
                completion(self.preferredRoute(input: input) == device)
            } catch {
                self.log(error)
                self.setPreferredRoute(previous, input: input)
                do { try self.rebuildRoute(input: input, delegate: delegate) }
                catch {
                    self.setPreferredRoute(nil, input: input)
                    do { try self.rebuildRoute(input: input, delegate: delegate) }
                    catch { self.reportDeviceFailure(error) }
                }
                self.publishRoutes()
                completion(false)
            }
        }
    }

    private func rebuildRoute(input: Bool, delegate: RTCAudioDeviceDelegate) throws {
        if input {
            inputEngine.stop()
            delegate.notifyAudioInputInterrupted()
            try configureInput()
            delegate.notifyAudioInputParametersChange()
            if isRecording { try inputEngine.start() }
        } else {
            outputEngine.stop()
            delegate.notifyAudioOutputInterrupted()
            try configureOutput()
            delegate.notifyAudioOutputParametersChange()
            if isPlaying { try outputEngine.start() }
        }
    }

    private func systemRouteChanged() {
        let revision = delegateLock.withLock {
            routeRefreshRevision &+= 1
            return routeRefreshRevision
        }
        routeQueue.asyncAfter(deadline: .now() + .milliseconds(100)) { [weak self] in
            self?.refreshSystemRoute(revision: revision, mayRetry: true)
        }
    }

    private func refreshSystemRoute(revision: UInt64, mayRetry: Bool) {
        guard delegateLock.withLock({ routeRefreshRevision == revision }) else { return }
        guard let delegate = delegateSnapshot() else { return }
        delegate.dispatchAsync { [weak self, weak delegate] in
            guard let self, let delegate, self.delegateSnapshot() === delegate, self.isInitialized,
                  self.delegateLock.withLock({ self.routeRefreshRevision == revision }) else { return }
            do {
                if self.isRecording {
                    let desired = try self.resolvedDevice(input: true)
                    if !self.inputEngine.isRunning || self.inputEngine.inputNode.auAudioUnit.deviceID != desired {
                        try self.rebuildRoute(input: true, delegate: delegate)
                    }
                }
                if self.isPlaying {
                    let desired = try self.resolvedDevice(input: false)
                    if !self.outputEngine.isRunning || self.outputEngine.outputNode.auAudioUnit.deviceID != desired {
                        try self.rebuildRoute(input: false, delegate: delegate)
                    }
                }
                self.publishRoutes()
            } catch {
                guard self.delegateLock.withLock({ self.routeRefreshRevision == revision }) else { return }
                if mayRetry {
                    self.routeQueue.asyncAfter(deadline: .now() + .milliseconds(400)) { [weak self] in
                        self?.refreshSystemRoute(revision: revision, mayRetry: false)
                    }
                } else {
                    self.reportDeviceFailure(error)
                }
            }
        }
    }

    private func preferredRoute(input: Bool) -> AudioDeviceID? {
        delegateLock.withLock { input ? routes.input : routes.output }
    }

    private func setPreferredRoute(_ device: AudioDeviceID?, input: Bool) {
        delegateLock.withLock {
            if input { routes.input = device } else { routes.output = device }
        }
    }

    private func publishRoutes() {
        let (handler, selection) = delegateLock.withLock { (routeChangeHandler, routes) }
        handler?(selection)
    }

    private func configureInput() throws {
        guard let delegate = delegateSnapshot() else { throw AudioError.uninitialized }
        let node = inputEngine.inputNode
        if let sinkNode { inputEngine.detach(sinkNode); self.sinkNode = nil }
        let device = try resolvedDevice(input: true)
        if node.auAudioUnit.deviceID != device { try node.auAudioUnit.setDeviceID(device) }
        for _ in 0..<2 {
            let hardware = node.inputFormat(forBus: 0)
            guard hardware.sampleRate.isFinite, hardware.sampleRate > 0, hardware.channelCount > 0,
                  let format = AVAudioFormat(standardFormatWithSampleRate: hardware.sampleRate, channels: hardware.channelCount)
            else { throw AudioError.invalidFormat }
            if try attachInputSink(node, format: format, delegate: delegate) { return }
        }
        throw AudioError.invalidFormat
    }

    private func attachInputSink(_ node: AVAudioInputNode, format: AVAudioFormat,
                                 delegate: RTCAudioDeviceDelegate) throws -> Bool {
        guard
              let pcm = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: format.sampleRate, channels: 1, interleaved: true),
              let storage = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity) else { throw AudioError.invalidFormat }
        let deliver = delegate.deliverRecordedData
        let sink = AVAudioSinkNode { timestamp, frames, data in
            guard frames <= storage.frameCapacity, let samples = storage.int16ChannelData?[0] else { return noErr }
            let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: data))
            let channelCount = buffers.reduce(0) { $0 + Int($1.mNumberChannels) }
            guard channelCount > 0 else { return noErr }
            let count = Int(frames)
            for frame in 0..<count {
                var sum: Float = 0
                for buffer in buffers {
                    guard let data = buffer.mData else { continue }
                    let floats = data.assumingMemoryBound(to: Float.self)
                    let channels = Int(buffer.mNumberChannels)
                    guard Int(buffer.mDataByteSize) >= count * channels * MemoryLayout<Float>.stride else { continue }
                    for channel in 0..<channels { sum += floats[frame * channels + channel] }
                }
                let value = sum / Float(channelCount)
                samples[frame] = value.isFinite ? Int16(max(-32768, min(32767, value * 32768))) : 0
            }
            storage.frameLength = frames
            var flags: AudioUnitRenderActionFlags = []
            return deliver(&flags, timestamp, 0, frames, storage.audioBufferList, nil, nil)
        }
        inputEngine.attach(sink)
        guard MsgATConnectCallInput(inputEngine, node, sink, format) else {
            inputEngine.detach(sink)
            return false
        }
        inputRate = format.sampleRate
        sinkNode = sink
        return true
    }

    private func configureOutput() throws {
        guard let delegate = delegateSnapshot() else { throw AudioError.uninitialized }
        if let sourceNode { outputEngine.detach(sourceNode); self.sourceNode = nil }
        let output = outputEngine.outputNode.auAudioUnit
        let device = try resolvedDevice(input: false)
        if output.deviceID != device { try output.setDeviceID(device) }
        guard let pcm = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: outputRate, channels: 1, interleaved: true),
              let storage = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity),
              let renderFormat = AVAudioFormat(standardFormatWithSampleRate: outputRate, channels: 1) else { throw AudioError.invalidFormat }
        let getData = delegate.getPlayoutData
        let node = AVAudioSourceNode(format: renderFormat) { silence, timestamp, frames, output in
            let buffers = UnsafeMutableAudioBufferListPointer(output)
            guard frames <= storage.frameCapacity, let samples = storage.int16ChannelData?[0] else {
                for buffer in buffers { if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) } }
                silence.pointee = true; return noErr
            }
            storage.frameLength = frames
            var flags: AudioUnitRenderActionFlags = []
            let status = getData(&flags, timestamp, 0, frames, storage.mutableAudioBufferList)
            let muted = status != noErr || flags.contains(.unitRenderAction_OutputIsSilence)
            for buffer in buffers {
                guard let data = buffer.mData else { continue }
                let floats = data.assumingMemoryBound(to: Float.self)
                let channels = Int(buffer.mNumberChannels)
                guard Int(buffer.mDataByteSize) >= Int(frames) * channels * MemoryLayout<Float>.stride else { continue }
                for frame in 0..<Int(frames) {
                    for channel in 0..<channels { floats[frame * channels + channel] = muted ? 0 : Float(samples[frame]) / 32768 }
                }
            }
            silence.pointee = ObjCBool(muted)
            return noErr
        }
        outputEngine.attach(node)
        outputEngine.connect(node, to: outputEngine.mainMixerNode, format: renderFormat)
        sourceNode = node
        outputEngine.prepare()
    }

    private func observe(_ engine: AVAudioEngine) -> NSObjectProtocol {
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            self?.systemRouteChanged()
        }
    }

    private func delegateSnapshot() -> RTCAudioDeviceDelegate? {
        delegateLock.lock(); defer { delegateLock.unlock() }; return audioDelegate
    }

    private func resolvedDevice(input: Bool) throws -> AudioDeviceID {
        if let preferred = preferredRoute(input: input) {
            var alive: UInt32 = 0
            var size = UInt32(MemoryLayout.size(ofValue: alive))
            var address = propertyAddress(kAudioDevicePropertyDeviceIsAlive)
            if AudioObjectGetPropertyData(preferred, &address, 0, nil, &size, &alive) == noErr, alive == 1 {
                return preferred
            }
            setPreferredRoute(nil, input: input)
        }
        var address = propertyAddress(input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice)
        var value: AudioDeviceID = 0
        var size = UInt32(MemoryLayout.size(ofValue: value))
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &value)
        guard status == noErr, value != kAudioObjectUnknown else { throw AudioError.noDevice }
        return value
    }

    private func propertyAddress(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector,
                                   mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    }

    private func ioBufferDuration(_ node: AVAudioIONode) -> TimeInterval {
        let device = node.auAudioUnit.deviceID
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyBufferFrameSize,
                                                mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var frames: UInt32 = 0
        var size = UInt32(MemoryLayout.size(ofValue: frames))
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &frames) == noErr else { return 0.01 }
        address.mSelector = kAudioDevicePropertyNominalSampleRate
        var rate: Double = 0
        size = UInt32(MemoryLayout.size(ofValue: rate))
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &rate) == noErr,
              frames > 0, rate > 0 else { return 0.01 }
        return Double(frames) / rate
    }

    private func log(_ error: Error) { print("[MsgAT-Call] Audio engine: \(error.localizedDescription)") }
    private func reportDeviceFailure(_ error: Error) {
        log(error)
        delegateLock.lock(); let handler = failureHandler; delegateLock.unlock()
        handler?()
    }
    private enum AudioError: Error { case uninitialized, invalidFormat, noDevice, routeListener(OSStatus) }
}
