// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

final class MicrophoneOwnership: @unchecked Sendable {
    static let shared = MicrophoneOwnership()

    final class RecordingLease: Sendable {
        fileprivate let drain: @Sendable () async -> Void
        fileprivate init(drain: @escaping @Sendable () async -> Void) { self.drain = drain }
    }

    final class CallLease: Sendable {
        fileprivate let recording: RecordingLease?
        fileprivate init(recording: RecordingLease?) { self.recording = recording }
    }

    private let lock = NSLock()
    private var recording: RecordingLease?
    private var call: CallLease?

    func startRecording(drain: @escaping @Sendable () async -> Void) throws -> RecordingLease {
        try lock.withLock {
            guard recording == nil, call == nil else { throw SecurityCore.Failure.operation("The microphone is already in use.") }
            let lease = RecordingLease(drain: drain)
            recording = lease
            return lease
        }
    }

    func reserveCall() -> CallLease? {
        lock.withLock {
            guard call == nil else { return nil }
            let lease = CallLease(recording: recording)
            call = lease
            return lease
        }
    }

    func drainRecording(for lease: CallLease) async -> Bool {
        if let recording = lease.recording { await recording.drain() }
        return lock.withLock { call === lease && recording == nil }
    }

    func release(_ lease: RecordingLease?) {
        lock.withLock { if recording === lease { recording = nil } }
    }

    func release(_ lease: CallLease?) {
        lock.withLock { if call === lease { call = nil } }
    }
}
