import AVFoundation
import CoreAudio
#if canImport(MsgATNativeAudio)
import MsgATNativeAudio
#endif

/// WebRTC's public PCM device interface, with output routing owned by AVAudioEngine.
final class MacCallAudioDevice: NSObject, RTCAudioDevice, @unchecked Sendable {
    private let inputEngine = AVAudioEngine()
    private let outputEngine = AVAudioEngine()
    private let delegateLock = NSLock()
    private var audioDelegate: RTCAudioDeviceDelegate?
    private var failureHandler: (@Sendable () -> Void)?
    private var routeChangeHandler: (@Sendable () -> Void)?
    private let routeQueue = DispatchQueue(label: "org.msgat.call.output-route")
    private var routeListener: AudioObjectPropertyListenerBlock?
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

    func setRouteChangeHandler(_ handler: @escaping @Sendable () -> Void) {
        delegateLock.lock(); routeChangeHandler = handler; delegateLock.unlock()
    }

    func initialize(with delegate: RTCAudioDeviceDelegate) -> Bool {
        delegateLock.lock(); audioDelegate = delegate; delegateLock.unlock()
        isInitialized = true
        inputObserver = observe(inputEngine, input: true)
        outputObserver = observe(outputEngine, input: false)
        var address = outputAddress()
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.refreshOutputRoute { [weak self] success in
                guard let self else { return }
                if !success { self.reportDeviceFailure(AudioError.noOutput); return }
                self.delegateLock.lock(); let handler = self.routeChangeHandler; self.delegateLock.unlock()
                handler?()
            }
        }
        let status = AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, routeQueue, listener)
        if status == noErr { routeListener = listener }
        else { log(AudioError.routeListener(status)) }
        return true
    }

    func terminateDevice() -> Bool {
        _ = stopRecording(); _ = stopPlayout()
        if let sinkNode { inputEngine.detach(sinkNode); self.sinkNode = nil }
        if let sourceNode { outputEngine.detach(sourceNode); self.sourceNode = nil }
        if let inputObserver { NotificationCenter.default.removeObserver(inputObserver) }
        if let outputObserver { NotificationCenter.default.removeObserver(outputObserver) }
        inputObserver = nil; outputObserver = nil
        if let routeListener {
            var address = outputAddress()
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, routeQueue, routeListener)
            self.routeListener = nil
        }
        isInitialized = false; isRecordingInitialized = false; isPlayoutInitialized = false
        delegateLock.lock(); audioDelegate = nil; delegateLock.unlock()
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

    /// Runs device changes on the thread that owns WebRTC's ADM, without blocking the UI.
    func refreshOutputRoute(completion: @escaping @Sendable (Bool) -> Void) {
        guard let delegate = delegateSnapshot() else { completion(true); return }
        delegate.dispatchAsync { [weak self, weak delegate] in
            guard let self, let delegate, self.delegateSnapshot() === delegate, self.isInitialized else {
                completion(false); return
            }
            guard self.isPlayoutInitialized else { completion(true); return }
            do {
                let resume = self.isPlaying
                self.outputEngine.stop()
                delegate.notifyAudioOutputInterrupted()
                try self.configureOutput()
                delegate.notifyAudioOutputParametersChange()
                if resume { try self.outputEngine.start() }
                completion(true)
            } catch { self.log(error); completion(false) }
        }
    }

    private func configureInput() throws {
        guard let delegate = delegateSnapshot() else { throw AudioError.uninitialized }
        let node = inputEngine.inputNode
        let format = node.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0, format.commonFormat == .pcmFormatFloat32,
              let pcm = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: format.sampleRate, channels: 1, interleaved: true),
              let storage = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity) else { throw AudioError.invalidFormat }
        inputRate = format.sampleRate
        if let sinkNode { inputEngine.detach(sinkNode); self.sinkNode = nil }
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
        inputEngine.connect(node, to: sink, format: format)
        sinkNode = sink
        inputEngine.prepare()
    }

    private func configureOutput() throws {
        guard let delegate = delegateSnapshot() else { throw AudioError.uninitialized }
        try outputEngine.outputNode.auAudioUnit.setDeviceID(defaultOutputID())
        if let sourceNode { outputEngine.detach(sourceNode); self.sourceNode = nil }
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

    private func observe(_ engine: AVAudioEngine, input: Bool) -> NSObjectProtocol {
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            guard let self, let delegate = self.delegateSnapshot() else { return }
            delegate.dispatchAsync { [weak self, weak delegate] in
                guard let self, let delegate, self.delegateSnapshot() === delegate, self.isInitialized else { return }
                if input {
                    guard self.isRecording, !self.inputEngine.isRunning else { return }
                    do {
                        self.inputEngine.stop(); delegate.notifyAudioInputInterrupted()
                        try self.configureInput(); delegate.notifyAudioInputParametersChange()
                        try self.inputEngine.start()
                    } catch { self.reportDeviceFailure(error) }
                } else {
                    guard self.isPlaying else { return }
                    if self.outputEngine.isRunning,
                       (try? self.defaultOutputID()) == self.outputEngine.outputNode.auAudioUnit.deviceID { return }
                    self.refreshOutputRoute { [weak self] success in
                        if !success { self?.reportDeviceFailure(AudioError.noOutput) }
                    }
                }
            }
        }
    }

    private func delegateSnapshot() -> RTCAudioDeviceDelegate? {
        delegateLock.lock(); defer { delegateLock.unlock() }; return audioDelegate
    }

    private func defaultOutputID() throws -> AudioDeviceID {
        var address = outputAddress()
        var value: AudioDeviceID = 0
        var size = UInt32(MemoryLayout.size(ofValue: value))
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &value)
        guard status == noErr, value != kAudioObjectUnknown else { throw AudioError.noOutput }
        return value
    }

    private func outputAddress() -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
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
    private enum AudioError: Error { case uninitialized, invalidFormat, noOutput, routeListener(OSStatus) }
}
