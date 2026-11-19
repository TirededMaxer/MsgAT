// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import Combine
@preconcurrency import CoreLocation
import MapKit

struct LocationSendRequest: Identifiable {
    let id = UUID()
    let recipient: String
    let initial: SendOptions
    let send: @MainActor (LocationPoint, String?, SendOptions) async -> Bool
    let shareLive: @MainActor (LocationPoint, Int64, String?, Bool) async -> Bool
}

extension LocationPoint {
    init?(latitude: Double, longitude: Double) {
        guard latitude.isFinite, longitude.isFinite, (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude) else { return nil }
        self.init(latitudeE6: Int((latitude * 1_000_000).rounded()), longitudeE6: Int((longitude * 1_000_000).rounded()))
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: Double(latitudeE6) / 1_000_000, longitude: Double(longitudeE6) / 1_000_000)
    }

    var latitudeText: String { Self.degrees(latitudeE6) }
    var longitudeText: String { Self.degrees(longitudeE6) }

    private static func degrees(_ value: Int) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), Double(value) / 1_000_000)
    }
}

@MainActor
private final class CurrentPositionLookup: NSObject, ObservableObject, CLLocationManagerDelegate {
    struct Fix: Equatable {
        let point: LocationPoint
        let accuracy: Int?
        let timestamp: Date
    }

    @Published private(set) var fix: Fix?
    @Published private(set) var locating = false
    @Published private(set) var error: String?
    private var manager: CLLocationManager?
    private var timeout: Task<Void, Never>?
    private var requested = false

    func start() {
        guard manager == nil else { return }
        fix = nil
        error = nil
        locating = true
        let active = CLLocationManager()
        manager = active
        active.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        active.delegate = self
        if active.authorizationStatus == .notDetermined { active.requestWhenInUseAuthorization() }
        else { authorize(active) }
    }

    func stop() {
        timeout?.cancel()
        timeout = nil
        manager?.delegate = nil
        manager?.stopUpdatingLocation()
        manager = nil
        locating = false
        requested = false
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorize(manager)
    }

    private func authorize(_ active: CLLocationManager) {
        guard active === manager else { return }
        switch active.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            guard !requested else { return }
            requested = true
            active.requestLocation()
            timeout = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                self?.finish(error: "location_unavailable_manual")
            }
        case .notDetermined:
            break
        default:
            finish(error: "location_access_manual")
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard manager === self.manager, let sample = locations.last,
              let point = LocationPoint(latitude: sample.coordinate.latitude, longitude: sample.coordinate.longitude) else { return }
        let accuracy = sample.horizontalAccuracy
        fix = Fix(point: point, accuracy: accuracy.isFinite && (0...10_000_000).contains(accuracy) ? Int(accuracy.rounded()) : nil,
                  timestamp: sample.timestamp)
        finish(error: nil)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard manager === self.manager else { return }
        finish(error: "location_unavailable_manual")
    }

    private func finish(error: String?) {
        self.error = error
        stop()
    }
}

@MainActor
struct LocationSendView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var lookup = CurrentPositionLookup()
    let request: LocationSendRequest
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var label = ""
    @State private var accuracy: Int?
    @State private var options: SendOptions
    @State private var showOptions = false
    @State private var sending = false
    @State private var error: String?
    @State private var live = false
    @State private var pendingLiveFix = false
    @State private var durationMs: Int64 = 15 * 60 * 1_000

    init(request: LocationSendRequest) {
        self.request = request
        _options = State(initialValue: request.initial)
    }

    private var point: LocationPoint? {
        let parse: (String) -> Double? = {
            Double($0.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: "."))
        }
        guard let lat = parse(latitude), let lon = parse(longitude) else { return nil }
        return LocationPoint(latitude: lat, longitude: lon)
    }

    private var selectedLabel: String? {
        let text = label.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    private var validLabel: Bool {
        guard let selectedLabel else { return true }
        return selectedLabel.utf8.count <= 256 && !selectedLabel.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
    }

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        let screen = NSApp.keyWindow?.screen ?? NSScreen.main
        let maxFormHeight = min(350, max(140, (screen?.visibleFrame.height ?? 680) - 190))
        return DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(interfaceText("location_share")).font(.headline)
                Text(interfaceText.format("recipient", request.recipient)).foregroundStyle(.secondary).lineLimit(1)
                Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Picker(interfaceText("location_share"), selection: $live) {
                            Text(interfaceText("location")).tag(false)
                            Text(interfaceText("live_location")).tag(true)
                        }.pickerStyle(.segmented).disabled(sending || lookup.locating)
                        if live {
                            Text(interfaceText("live_location_explain")).foregroundStyle(.secondary)
                            Picker(interfaceText("location_duration"), selection: $durationMs) {
                                Text(interfaceText("location_duration_15")).tag(Int64(15 * 60 * 1_000))
                                Text(interfaceText("location_duration_60")).tag(Int64(60 * 60 * 1_000))
                                Text(interfaceText("location_duration_480")).tag(Int64(8 * 60 * 60 * 1_000))
                            }.disabled(sending || lookup.locating)
                        } else {
                            Button(lookup.locating ? interfaceText("location_finding") : interfaceText("location_find")) {
                                error = nil
                                lookup.start()
                            }.disabled(sending || lookup.locating)
                            HStack {
                                coordinateField(interfaceText("location_latitude"), text: $latitude)
                                coordinateField(interfaceText("location_longitude"), text: $longitude)
                            }
                            if let point { Text("\(point.latitudeText), \(point.longitudeText)").font(.caption.monospaced()).textSelection(.enabled) }
                            else if !latitude.isEmpty && !longitude.isEmpty {
                                Text(interfaceText("location_coordinates_invalid")).font(.caption).foregroundStyle(.red)
                            }
                            if let accuracy { Text(interfaceText.format("location_accuracy", accuracy)).font(.caption).foregroundStyle(.secondary) }
                        }
                        TextField(interfaceText("location_label"), text: $label).textFieldStyle(.roundedBorder).disabled(sending || lookup.locating)
                        if !validLabel { Text(interfaceText("location_label_invalid")).font(.caption).foregroundStyle(.red) }
                        if live {
                            Toggle(interfaceText("location_silent"), isOn: $options.silent).disabled(sending || lookup.locating)
                        } else {
                            Button(sendOptionsLabel(options).isEmpty ? interfaceText("send_options") : sendOptionsLabel(options)) {
                                showOptions = true
                            }.disabled(sending)
                        }
                        if let error = error ?? lookup.error {
                            let key = live && error == "location_unavailable_manual" ? "location_unavailable" :
                                live && error == "location_access_manual" ? "location_access" : error
                            Text(interfaceText(key)).font(.callout).foregroundStyle(.red)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(3)
                }
                .frame(maxHeight: maxFormHeight)
                Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(sending)
                    Button(lookup.locating && live ? interfaceText("location_finding") : sending ? interfaceText("location_sending") : live ? interfaceText("live_location_share") : interfaceText("location_send"), action: submit)
                        .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction)
                        .disabled(sending || lookup.locating || (!live && point == nil) || !validLabel)
                }
            }
        }
        .frame(width: 460)
        .interactiveDismissDisabled(sending)
        .onChange(of: lookup.fix) { value in
            guard let value else { return }
            if pendingLiveFix {
                pendingLiveFix = false
                if abs(value.timestamp.timeIntervalSinceNow) <= 30, value.accuracy != nil {
                    submitLive(value.point)
                } else {
                    error = "location_unavailable"
                }
                return
            }
            latitude = value.point.latitudeText
            longitude = value.point.longitudeText
            accuracy = value.accuracy
        }
        .onChange(of: lookup.error) { if $0 != nil { pendingLiveFix = false } }
        .onChange(of: live) { _ in error = nil }
        .onDisappear { lookup.stop() }
        .sheet(isPresented: $showOptions) { SendOptionsView(initial: options) { options = $0 } }
    }

    private func coordinateField(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: Binding(get: { text.wrappedValue }, set: {
            text.wrappedValue = String($0.prefix(20))
            accuracy = nil
            error = nil
        })).textFieldStyle(.roundedBorder).disabled(sending || lookup.locating)
    }

    private func submit() {
        guard !sending, !lookup.locating, validLabel else { return }
        if live {
            pendingLiveFix = true
            error = nil
            lookup.start()
            return
        }
        guard let point else { return }
        let title = selectedLabel
        let chosen = options
        sending = true
        error = nil
        Task { @MainActor in
            defer { sending = false }
            if await request.send(point, title, chosen) { dismiss() }
            else { error = "location_queue_error" }
        }
    }

    private func submitLive(_ point: LocationPoint) {
        let title = selectedLabel
        let duration = durationMs
        let silent = options.silent
        sending = true
        Task { @MainActor in
            defer { sending = false }
            if await request.shareLive(point, duration, title, silent) { dismiss() }
            else { error = "live_location_start_error" }
        }
    }
}

@MainActor
struct LocationDetailsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    let label: String
    let load: () throws -> LocationPoint?
    @State private var point: LocationPoint?
    @State private var error: String?
    @State private var retry = 0

    init(label: String, load: @escaping () throws -> LocationPoint?) {
        self.label = label
        self.load = load
    }

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(label == "Location" ? interfaceText("location") : label).font(.headline)
                if let point {
                    Text("\(point.latitudeText), \(point.longitudeText)").font(.body.monospaced()).textSelection(.enabled)
                } else if error == nil { ProgressView().controlSize(.small) }
                if let error {
                    HStack {
                        Text(interfaceText(error)).font(.callout).foregroundStyle(.red)
                        if error == "location_open_error" {
                            Spacer()
                            Button(interfaceText("retry")) { retry += 1 }
                        }
                    }
                }
                HStack {
                    Spacer()
                    Button(interfaceText("close")) { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(interfaceText("location_open_maps"), action: openMaps).disabled(point == nil)
                }
            }
        }
        .frame(width: 420)
        .task(id: retry) {
            error = nil
            let read = load
            do {
                let selected = try await EncryptedMediaWork.run { try read() }
                guard !Task.isCancelled else { return }
                point = selected
                if selected == nil { error = "location_missing" }
            } catch { if !Task.isCancelled { self.error = "location_open_error" } }
        }
    }

    private func openMaps() {
        error = nil
        let read = load
        Task { @MainActor in
            do {
                let current = try await EncryptedMediaWork.run { try read() }
                guard let current, current == point else {
                    point = nil
                    error = "location_missing"
                    return
                }
                if !MKMapItem(placemark: MKPlacemark(coordinate: current.coordinate)).openInMaps() {
                    error = "location_maps_error"
                }
            } catch { self.error = "location_maps_error" }
        }
    }
}

@MainActor
struct LiveLocationCardStatus: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let message: ChatMessage
    @ObservedObject private var coordinator = LiveLocationCoordinator.shared
    @State private var snapshot: LiveLocationSnapshot?

    var body: some View {
        TimelineView(.explicit(locationRefreshDates(snapshot))) { context in
            let status = liveStatus(snapshot, message: message, coordinator: coordinator, at: context.date)
            if status != "live_location" || message.content != "Location" {
                Text(interfaceText(status)).font(.caption).opacity(0.78)
            }
        }
        .task(id: message.id) { reload() }
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification).receive(on: DispatchQueue.main)) { _ in reload() }
    }

    private func reload() {
        snapshot = try? SecureMessaging.shared.liveLocation(peer: message.conversationUuid, messageID: message.id)
    }
}

@MainActor
struct LiveLocationDetailsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @ObservedObject private var coordinator = LiveLocationCoordinator.shared
    let message: ChatMessage
    @State private var snapshot: LiveLocationSnapshot?
    @State private var loaded = false
    @State private var stopping = false
    @State private var error: String?

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(message.content == "Location" ? interfaceText("live_location") : message.content).font(.headline)
                if let snapshot {
                    TimelineView(.explicit(locationRefreshDates(snapshot))) { context in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(interfaceText(liveStatus(snapshot, message: message, coordinator: coordinator, at: context.date)))
                                .font(.callout).foregroundStyle(.secondary)
                            Text("\(snapshot.point.latitudeText), \(snapshot.point.longitudeText)")
                                .font(.body.monospaced()).textSelection(.enabled)
                            if message.isMine, !snapshot.ended, context.date < expiryDate(snapshot) {
                                Button(interfaceText("live_location_stop"), role: .destructive, action: stop).disabled(stopping)
                            }
                        }
                    }
                } else if loaded {
                    Text(interfaceText("location_missing")).foregroundStyle(.secondary)
                } else {
                    ProgressView().controlSize(.small)
                }
                if let error { Text(interfaceText(error)).font(.callout).foregroundStyle(.red) }
                HStack {
                    Spacer()
                    Button(interfaceText("close")) { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(interfaceText("location_open_maps"), action: openMaps).disabled(snapshot == nil)
                }
            }
        }
        .frame(width: 420)
        .task(id: message.id) { reload() }
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification).receive(on: DispatchQueue.main)) { _ in reload() }
    }

    private func reload() {
        snapshot = try? SecureMessaging.shared.liveLocation(peer: message.conversationUuid, messageID: message.id)
        loaded = true
    }

    private func stop() {
        stopping = true
        error = nil
        Task { @MainActor in
            if !(await coordinator.stop(peer: message.conversationUuid, messageID: message.id)) {
                error = "live_location_stop_error"
            }
            reload()
            stopping = false
        }
    }

    private func openMaps() {
        error = nil
        reload()
        guard let point = snapshot?.point else { return }
        if !MKMapItem(placemark: MKPlacemark(coordinate: point.coordinate)).openInMaps() {
            error = "location_maps_error"
        }
    }
}

private func expiryDate(_ snapshot: LiveLocationSnapshot) -> Date {
    Date(timeIntervalSince1970: Double(snapshot.expiresAt) / 1_000)
}

private func locationRefreshDates(_ snapshot: LiveLocationSnapshot?) -> [Date] {
    let now = Date()
    guard let snapshot, !snapshot.ended else { return [now] }
    let expiry = expiryDate(snapshot)
    return expiry > now ? [now, expiry] : [now]
}

@MainActor
private func liveStatus(_ snapshot: LiveLocationSnapshot?, message: ChatMessage, coordinator: LiveLocationCoordinator,
                        at date: Date) -> String {
    guard let snapshot else { return "live_location" }
    if snapshot.ended || date >= expiryDate(snapshot) { return "live_location_ended" }
    if message.isMine, !coordinator.collecting || coordinator.shares[message.conversationUuid]?.messageID != message.id {
        return "live_location_paused"
    }
    return "live_location"
}
