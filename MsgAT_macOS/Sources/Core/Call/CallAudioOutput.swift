import CoreAudio
import Foundation

/// Temporarily selects the built-in speakers; the call audio device follows the system route.
final class CallAudioOutput {
    private let recoveryKey = "org.msgat.call.audioOutputOverride"
    private var originalOutputUID: String?
    private var speakerOutputUID: String?

    init() {
        let saved = UserDefaults.standard.dictionary(forKey: recoveryKey)
        originalOutputUID = saved?["originalUID"] as? String
        speakerOutputUID = saved?["speakerUID"] as? String
        do { try restore() }
        catch { print("[MsgAT-Call] Previous audio output restoration failed: \(error.localizedDescription)") }
    }

    func enableSpeaker() throws {
        if originalOutputUID != nil { try restore() }
        let original = try defaultOutput()
        let speaker = try builtInSpeaker()
        let originalUID = try deviceUID(original)
        let speakerUID = try deviceUID(speaker)
        originalOutputUID = originalUID
        speakerOutputUID = speakerUID
        UserDefaults.standard.set(["originalUID": originalUID, "speakerUID": speakerUID], forKey: recoveryKey)
        do {
            try setDefaultOutput(speaker)
        } catch {
            try? restore()
            throw error
        }
    }

    func restore() throws {
        guard let originalUID = originalOutputUID, let appliedUID = speakerOutputUID else { return }
        let currentUID = try deviceUID(defaultOutput())
        // Preserve a system output change made by the user or another application.
        if currentUID == appliedUID {
            let devices = try objectList(AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDevices)
            guard let original = devices.first(where: { (try? deviceUID($0)) == originalUID && isAlive($0) }) else {
                throw OutputError.operation("Restore disconnected output", kAudioHardwareBadDeviceError)
            }
            try setDefaultOutput(original)
        }
        originalOutputUID = nil
        speakerOutputUID = nil
        UserDefaults.standard.removeObject(forKey: recoveryKey)
    }

    func isSpeakerOverrideActive() throws -> Bool {
        guard let speakerOutputUID else { return false }
        return try deviceUID(defaultOutput()) == speakerOutputUID
    }

    private func builtInSpeaker() throws -> AudioDeviceID {
        for device in try objectList(AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDevices) {
            guard isAlive(device),
                  (try? scalar(device, selector: kAudioDevicePropertyTransportType)) == kAudioDeviceTransportTypeBuiltIn,
                  let streams = try? objectList(device, selector: kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeOutput),
                  streams.contains(where: {
                      (try? scalar($0, selector: kAudioStreamPropertyTerminalType)) == kAudioStreamTerminalTypeSpeaker
                  }) else { continue }
            return device
        }
        throw OutputError.operation("Find built-in speaker", kAudioHardwareBadDeviceError)
    }

    private func defaultOutput() throws -> AudioDeviceID {
        let device = try scalar(AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDefaultOutputDevice)
        guard device != kAudioObjectUnknown, isAlive(device) else {
            throw OutputError.operation("Read default output", kAudioHardwareBadDeviceError)
        }
        return device
    }

    private func setDefaultOutput(_ device: AudioDeviceID) throws {
        var address = address(kAudioHardwarePropertyDefaultOutputDevice)
        var settable = DarwinBoolean(false)
        let object = AudioObjectID(kAudioObjectSystemObject)
        try check(AudioObjectIsPropertySettable(object, &address, &settable), "Check output routing")
        guard settable.boolValue else {
            throw OutputError.operation("Set default output", kAudioHardwareIllegalOperationError)
        }
        var value = device
        try check(AudioObjectSetPropertyData(object, &address, 0, nil, UInt32(MemoryLayout.size(ofValue: value)), &value), "Set default output")
    }

    private func isAlive(_ device: AudioDeviceID) -> Bool {
        (try? scalar(device, selector: kAudioDevicePropertyDeviceIsAlive)) == 1
    }

    private func deviceUID(_ device: AudioDeviceID) throws -> String {
        var address = address(kAudioDevicePropertyDeviceUID)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout.size(ofValue: value))
        try check(AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value), "Read output UID")
        guard let value else { throw OutputError.operation("Read output UID", kAudioHardwareBadDeviceError) }
        return value.takeRetainedValue() as String
    }

    private func scalar(_ object: AudioObjectID, selector: AudioObjectPropertySelector) throws -> UInt32 {
        var address = address(selector)
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout.size(ofValue: value))
        try check(AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value), "Read audio property")
        return value
    }

    private func objectList(
        _ object: AudioObjectID,
        selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal
    ) throws -> [AudioObjectID] {
        var address = address(selector, scope: scope)
        var size: UInt32 = 0
        try check(AudioObjectGetPropertyDataSize(object, &address, 0, nil, &size), "Read audio list size")
        let stride = MemoryLayout<AudioObjectID>.stride
        guard size > 0 else { return [] }
        guard Int(size) % stride == 0 else {
            throw OutputError.operation("Read audio list size", kAudioHardwareBadPropertySizeError)
        }
        var values = [AudioObjectID](repeating: 0, count: Int(size) / stride)
        let status = values.withUnsafeMutableBufferPointer {
            AudioObjectGetPropertyData(object, &address, 0, nil, &size, $0.baseAddress!)
        }
        try check(status, "Read audio list")
        return Array(values.prefix(Int(size) / stride))
    }

    private func address(
        _ selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal
    ) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private func check(_ status: OSStatus, _ operation: String) throws {
        if status != noErr { throw OutputError.operation(operation, status) }
    }

    private enum OutputError: LocalizedError {
        case operation(String, OSStatus)
        var errorDescription: String? {
            switch self { case let .operation(operation, status): return "\(operation): \(status)" }
        }
    }
}
