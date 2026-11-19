// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Combine
@preconcurrency import CoreLocation

@MainActor
final class LiveLocationCoordinator: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LiveLocationCoordinator()

    private struct Session {
        let share: LiveLocationShare
        let deadline: ContinuousClock.Instant
    }

    @Published private(set) var shares: [String: LiveLocationShare] = [:]
    @Published private(set) var collecting = false
    private let clock = ContinuousClock()
    private var sessions: [String: Session] = [:]
    private var starting: [String: Task<LiveLocationShare?, Never>] = [:]
    private var manager: CLLocationManager?
    private var sampler: Task<Void, Never>?
    private var latest: LocationPoint?
    private var foreground = false
    private var closed = false

    func setForeground(_ foreground: Bool) {
        self.foreground = foreground
        updateLocationManager()
    }

    func start(peer: String, point: LocationPoint, durationMs: Int64, label: String?, reply: ChatMessage?,
               draft: DraftSendCommit, silent: Bool) async -> Bool {
        guard !closed, foreground, starting[peer] == nil, sessions[peer] == nil else {
            draft.owner.observeFailedSend(draft)
            return false
        }
        let request = Task<LiveLocationShare?, Never> { @MainActor in
            await withCheckedContinuation { continuation in
                SecureMessaging.shared.startLiveLocation(to: peer, point: point, durationMs: durationMs, label: label,
                    reply: reply, draft: draft, silent: silent) { continuation.resume(returning: $0) }
            }
        }
        starting[peer] = request
        let share = await request.value
        starting[peer] = nil
        guard let share else { return false }
        // Close owns any share whose creation was still pending when teardown started.
        guard !closed else { return true }
        let remaining = min(durationMs, max(0, share.expiresAt - wallTime))
        guard remaining > 0 else { return true }
        sessions[peer] = Session(share: share, deadline: clock.now.advanced(by: .milliseconds(remaining)))
        publish()
        updateLocationManager()
        if sampler == nil {
            sampler = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.sample()
            }
        }
        return true
    }

    func stop(peer: String, messageID: String) async -> Bool {
        guard !closed else { return false }
        if sessions[peer]?.share.messageID == messageID {
            sessions.removeValue(forKey: peer)
            publish()
            updateLocationManager()
        }
        return await sendStop(peer: peer, messageID: messageID)
    }

    func close() async {
        guard !closed else { return }
        closed = true
        let requests = starting
        var stopped = sessions.mapValues(\.share)
        sessions.removeAll()
        publish()
        updateLocationManager()
        let running = sampler
        sampler = nil
        running?.cancel()
        await running?.value
        for (peer, request) in requests {
            if let share = await request.value { stopped[peer] = share }
        }
        for (peer, share) in stopped { _ = await sendStop(peer: peer, messageID: share.messageID) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager === self.manager else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways: break
        case .notDetermined: break
        default: locationAccessEnded()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard manager === self.manager, foreground, let sample = locations.last, sample.horizontalAccuracy >= 0,
              abs(sample.timestamp.timeIntervalSinceNow) <= 30 else { return }
        latest = LocationPoint(latitude: sample.coordinate.latitude, longitude: sample.coordinate.longitude)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if manager === self.manager, (error as? CLError)?.code == .denied { locationAccessEnded() }
    }

    private var wallTime: Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private func sample() async {
        defer { sampler = nil }
        while !Task.isCancelled, !sessions.isEmpty {
            let now = wallTime
            let elapsed = clock.now
            sessions = sessions.filter { $0.value.share.expiresAt > now && $0.value.deadline > elapsed }
            publish()
            updateLocationManager()
            guard let earliest = sessions.values.map(\.deadline).min() else { return }
            if foreground, let point = latest {
                latest = nil
                for (peer, session) in sessions {
                    guard !Task.isCancelled, foreground else { break }
                    guard sessions[peer]?.share.messageID == session.share.messageID else { continue }
                    let active = await withCheckedContinuation { continuation in
                        SecureMessaging.shared.updateLiveLocation(to: peer, messageID: session.share.messageID, point: point) {
                            continuation.resume(returning: $0)
                        }
                    }
                    if !active, sessions[peer]?.share.messageID == session.share.messageID {
                        sessions.removeValue(forKey: peer)
                    }
                }
                publish()
                updateLocationManager()
            }
            do { try await clock.sleep(until: min(earliest, clock.now.advanced(by: .seconds(15)))) } catch { return }
        }
    }

    private func updateLocationManager() {
        guard !closed, foreground, !sessions.isEmpty else {
            manager?.delegate = nil
            manager?.stopUpdatingLocation()
            manager = nil
            latest = nil
            collecting = false
            return
        }
        guard manager == nil else { return }
        let source = CLLocationManager()
        guard source.authorizationStatus == .authorizedAlways else {
            locationAccessEnded()
            return
        }
        manager = source
        source.delegate = self
        source.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        source.distanceFilter = 10
        source.startUpdatingLocation()
        collecting = true
    }

    private func locationAccessEnded() {
        let stopped = sessions.mapValues(\.share)
        sessions.removeAll()
        publish()
        updateLocationManager()
        for (peer, share) in stopped {
            SecureMessaging.shared.stopLiveLocation(to: peer, messageID: share.messageID) { _ in }
        }
    }

    private func sendStop(peer: String, messageID: String) async -> Bool {
        await withCheckedContinuation { continuation in
            SecureMessaging.shared.stopLiveLocation(to: peer, messageID: messageID) { continuation.resume(returning: $0) }
        }
    }

    private func publish() {
        let current = sessions.mapValues(\.share)
        if shares != current { shares = current }
    }
}
