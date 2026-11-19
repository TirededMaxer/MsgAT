// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import Combine

func sendOptionsLabel(_ value:SendOptions)->String {
    let text = InterfaceStrings.current()
    var parts=[String]()
    if value.silent {parts.append(text("silent"))}
    if let due=value.sendAt {
        let date = Date(timeIntervalSince1970: Double(due) / 1000)
            .formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(text.locale))
        parts.append(text.format("scheduled_time", date))
    }
    return parts.joined(separator:" · ")
}

@MainActor
struct SendOptionsView:View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var silent:Bool
    @State private var scheduled:Bool
    @State private var date:Date
    @State private var time:String
    @State private var error:String?
    private let zone=TimeZone.current
    let timeOnly:Bool
    private let startScheduled: Bool
    let onApply:(SendOptions)->Void
    init(initial:SendOptions,timeOnly:Bool=false,startScheduled:Bool=false,onApply:@escaping(SendOptions)->Void){
        self.timeOnly=timeOnly;self.startScheduled=startScheduled;self.onApply=onApply
        let local=initial.sendAt.map{Date(timeIntervalSince1970:Double($0)/1000)} ?? Date().addingTimeInterval(300)
        _silent=State(initialValue:initial.silent);_scheduled=State(initialValue:timeOnly || startScheduled || initial.sendAt != nil);_date=State(initialValue:local)
        var calendar=Calendar(identifier:.gregorian);calendar.timeZone=TimeZone.current
        _time=State(initialValue:String(format:"%02d:%02d",calendar.component(.hour,from:local),calendar.component(.minute,from:local)))
    }
    var body:some View {
        VStack(alignment:.leading,spacing:16){
            Text(interfaceText(timeOnly ? "change_scheduled_time" : "send_options")).font(.headline)
            ScrollView {
                VStack(alignment:.leading,spacing:12){
                    if !timeOnly {
                        Toggle(interfaceText("send_without_sound"),isOn:$silent)
                        if !startScheduled { Toggle(interfaceText("schedule_next_message"),isOn:$scheduled) }
                        Text(interfaceText("schedule_connected")).font(.caption).foregroundStyle(.secondary)
                    }
                    if scheduled {
                        DatePicker(interfaceText("date_label"),selection:$date,displayedComponents:.date)
                        TextField(interfaceText("time_label"),text:$time).textFieldStyle(.roundedBorder)
                        Text(zone.identifier).font(.caption).foregroundStyle(.secondary)
                    }
                    if let error {Text(error).font(.callout).foregroundStyle(.red)}
                }.padding(2)
            }.frame(maxHeight:220)
            HStack {Spacer();Button(interfaceText("cancel")){dismiss()}.keyboardShortcut(.cancelAction);Button(interfaceText(startScheduled ? "schedule_next_message" : "apply"),action:apply).keyboardShortcut(.defaultAction)}
        }.padding(20).frame(width:440)
    }
    private func apply(){
        var due:Int64?
        if scheduled {
            let fields=time.split(separator:":",omittingEmptySubsequences:false)
            guard fields.count==2,fields.allSatisfy({$0.count==2 && $0.allSatisfy({$0.isASCII && $0.isNumber})}),let hour=Int(fields[0]),let minute=Int(fields[1]),(0...23).contains(hour),(0...59).contains(minute) else {error=interfaceText("enter_time");return}
            var calendar=Calendar(identifier:.gregorian);calendar.timeZone=zone
            var components=calendar.dateComponents([.year,.month,.day],from:date);components.hour=hour;components.minute=minute;components.second=0
            guard let year=components.year,(1...9999).contains(year),let day=calendar.dateInterval(of:.day,for:date),let instant=calendar.nextDate(after:day.start.addingTimeInterval(-1),matching:components,matchingPolicy:.strict,repeatedTimePolicy:.first,direction:.forward) else {error=interfaceText("schedule_missing_time");return}
            guard instant>Date(),instant.timeIntervalSince1970*1000<=253402214399999 else {error=interfaceText("choose_future");return}
            due=Int64(instant.timeIntervalSince1970*1000)
        }
        onApply(SendOptions(sendAt:due,silent:silent));dismiss()
    }
}

@MainActor
private final class SendQueueModel:ObservableObject {
    @Published var rows=[SendQueueEntry]()
    @Published var loading=false
    @Published var busy=false
    @Published var actionError: String?
    @Published var loadFailed = false
    @Published private(set) var offset=0
    @Published private(set) var displayedOffset = 0
    @Published private(set) var scheduled: Bool?
    @Published private(set) var displayedFilter: Bool?
    @Published private(set) var hasNextPage = false
    private var closed=false
    private var reload=false
    private var failedPage: (offset: Int, scheduled: Bool?)?
    let peer:String?
    init(peer:String?){self.peer=peer}
    func stop(){closed=true}
    func page(_ delta: Int) {
        guard !loading, !busy else { return }
        requestPage(max(0, offset + delta), filter: scheduled)
    }
    func filter(_ value: Bool?) {
        guard !loading, !busy, scheduled != value else { return }
        actionError = nil
        requestPage(0, filter: value)
    }
    func refresh() {
        guard !loading, !busy else { return }
        actionError = nil
        let destination = failedPage ?? (offset: displayedOffset, scheduled: displayedFilter)
        requestPage(destination.offset, filter: destination.scheduled)
    }
    private func requestPage(_ destination: Int, filter: Bool?) {
        failedPage = nil
        loadFailed = false
        offset = destination
        scheduled = filter
        Task { await load() }
    }
    func load()async {
        guard !closed, !loadFailed else { return }
        guard !loading,!busy else{reload=true;return}
        loading=true
        repeat {
            reload=false
            let requested = (offset, scheduled)
            let result: [SendQueueEntry]? = await withCheckedContinuation { continuation in
                SecureMessaging.shared.sendQueue(peer: peer, offset: offset, scheduled: scheduled, limit: 51) {
                    continuation.resume(returning: $0)
                }
            }
            guard !closed else{loading=false;return}
            if let result {
                if result.isEmpty, offset > 0 {
                    offset = max(0, offset - 50)
                    reload = true
                } else {
                    rows = Array(result.prefix(50))
                    hasNextPage = result.count > 50
                    displayedOffset = offset
                    displayedFilter = scheduled
                    loadFailed = false
                    failedPage = nil
                }
            } else {
                failedPage = requested
                offset = displayedOffset
                scheduled = displayedFilter
                loadFailed = true
                reload = false
            }
        }while reload && !closed
        loading=false
    }
    func change(_ row:SendQueueEntry,action:String,at:Int64?=nil){
        guard !closed, !busy else { return }
        busy = true
        actionError = nil
        Task {
            let ok: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.manageSend(row.id, action: action, sendAt: at, expectedPeer: row.peer,
                                                  expectedScheduledAt: row.sendAt) {
                    continuation.resume(returning: $0)
                }
            }
            guard !closed else {busy=false;return}
            busy=false
            if !ok { actionError = "queue_stale" }
            failedPage = nil
            loadFailed = false
            await load()
        }
    }
    func openEditor(_ row: SendQueueEntry) async -> ScheduledMessageDraft? {
        guard !closed, !loading, !busy, row.canEdit, let at = row.sendAt else { return nil }
        busy = true
        actionError = nil
        let draft: ScheduledMessageDraft? = await withCheckedContinuation { continuation in
            SecureMessaging.shared.scheduledMessageDraft(id: row.id, peer: row.peer, at: at) {
                continuation.resume(returning: $0)
            }
        }
        busy = false
        guard !closed else { return nil }
        if draft == nil {
            actionError = "queue_stale"
            failedPage = nil
            loadFailed = false
            await load()
        }
        return draft
    }
}

@MainActor
struct SendQueueView:View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model:SendQueueModel
    @ObservedObject private var client = NWClient.shared
    @State private var cancel:SendQueueEntry?
    @State private var reschedule:SendQueueEntry?
    @State private var edit: EditRequest?
    private let peer: String?
    private let onOpenChat: ((String, String?) throws -> Bool)?

    private struct EditRequest: Identifiable {
        let row: SendQueueEntry
        let draft: ScheduledMessageDraft
        var id: String { row.id }
    }

    init(peer: String? = nil, onOpenChat: ((String, String?) throws -> Bool)? = nil) {
        self.peer = peer
        self.onOpenChat = onOpenChat
        _model = StateObject(wrappedValue: SendQueueModel(peer: peer))
    }
    var body:some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment:.leading,spacing:12){
            Text(interfaceText("sending_queue")).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            Picker(interfaceText("sending_queue"), selection: Binding(get: { model.scheduled }, set: { model.filter($0) })) {
                Text(interfaceText("queue_filter_all")).tag(nil as Bool?)
                Text(interfaceText("queue_filter_scheduled")).tag(true as Bool?)
                Text(interfaceText("queue_filter_pending")).tag(false as Bool?)
            }
            .pickerStyle(.segmented).labelsHidden().disabled(model.loading || model.busy)
            if model.loading {ProgressView().controlSize(.small)}
            if let error = model.actionError { Text(interfaceText(error)).foregroundStyle(.red).font(.callout) }
            if model.loadFailed { Text(interfaceText("queue_loading")).foregroundStyle(.red).font(.callout) }
            List(model.rows){row in
                VStack(alignment:.leading,spacing:6){
                    MessageSummaryText(text:row.summary).lineLimit(3)
                    if peer == nil {
                        let alias = row.peerAlias?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        Text(alias.isEmpty ? String(row.peer.prefix(12)) : alias)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if let due = row.sendAt ?? row.relayRetryAt, row.state != "failed" {
                        let now = Date()
                        let scheduled = Date(timeIntervalSince1970: Double(due) / 1_000)
                        TimelineView(.explicit(scheduled > now ? [now, scheduled] : [now])) { time in
                            Text(summary(row, at: time.date)).font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        Text(summary(row, at: Date())).font(.caption)
                            .foregroundStyle(row.state == "failed" ? Color.red : Color.secondary)
                    }
                    ViewThatFits(in: .horizontal) {
                        HStack { rowActions(row) }.fixedSize(horizontal: true, vertical: false)
                        Menu(interfaceText("queue_actions")) { rowActions(row) }
                    }
                    .disabled(model.busy || model.loading)
                }.padding(.vertical,6)
            }
            .scrollContentBackground(.hidden)
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            .id(model.displayedOffset)
            .id(model.displayedFilter)
            .overlay {if model.rows.isEmpty && !model.loading && !model.loadFailed {Text(interfaceText("queue_empty")).foregroundStyle(.secondary)}}
            Text(interfaceText("queue_online")).font(.caption).foregroundStyle(.secondary)
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack {
                Button(interfaceText(model.loadFailed ? "retry" : "queue_refresh"), action: model.refresh)
                    .disabled(model.loading || model.busy)
                Button(interfaceText("queue_previous")){model.page(-50)}.disabled(model.offset==0 || model.loading || model.busy)
                Button(interfaceText("queue_next")){model.page(50)}.disabled(!model.hasNextPage || model.loading || model.busy)
                Spacer();Button(interfaceText("close")){dismiss()}.keyboardShortcut(.cancelAction).disabled(model.busy)
            }
        }.padding(20).frame(minWidth:560,idealWidth:660,minHeight:380,idealHeight:520)
        .background(colors.chrome)
        .task {await model.load()}.onDisappear{model.stop()}.interactiveDismissDisabled(model.busy)
        .onReceive(NotificationCenter.default.publisher(for:SecureMessaging.sendQueueDidChange).merge(with:NotificationCenter.default.publisher(for:MsgDao.didChangeNotification)).throttle(for:.milliseconds(150),scheduler:RunLoop.main,latest:true)){_ in Task{await model.load()}}
        .alert(interfaceText("queue_cancel_title"),isPresented:Binding(get:{cancel != nil},set:{if !$0 {cancel=nil}})) {
            Button(interfaceText("queue_keep"),role:.cancel){cancel=nil}
            Button(interfaceText("queue_cancel"),role:.destructive){if let row=cancel {cancel=nil;model.change(row,action:"cancel")}}
        }message:{Text(interfaceText("queue_cancel_body"))}
        .sheet(item:$reschedule){row in SendOptionsView(initial:SendOptions(sendAt:row.sendAt,silent:row.silent),timeOnly:true){model.change(row,action:"reschedule",at:$0.sendAt)}}
        .sheet(item: $edit, onDismiss: { Task { await model.load() } }) { request in
            ScheduledMessageEditor(row: request.row, draft: request.draft)
        }
    }
    @ViewBuilder
    private func rowActions(_ row: SendQueueEntry) -> some View {
        if let onOpenChat, peer == nil || row.sendAt == nil {
            Button(interfaceText(row.sendAt == nil ? "show_in_chat" : "contact_open_chat")) {
                do {
                    if try onOpenChat(row.peer, row.sendAt == nil ? row.id : nil) { dismiss() }
                    else {
                        model.actionError = "queue_stale"
                        Task { await model.load() }
                    }
                } catch {
                    model.actionError = "history_load_failed"
                }
            }
        }
        if row.canEdit {
            Button(interfaceText("queue_edit")) {
                Task {
                    if let draft = await model.openEditor(row), AppLockSession.shared.canShowContent {
                        edit = EditRequest(row: row, draft: draft)
                    }
                }
            }
        }
        if row.sendAt != nil, row.failure == nil {
            Button(interfaceText("queue_now")) { model.change(row, action: "send_now") }
            Button(interfaceText("change_scheduled_time")) { reschedule = row }
        }
        if row.canRetry { Button(interfaceText("retry")) { model.change(row, action: "retry") } }
        if row.canCancel { Button(interfaceText("cancel"), role: .destructive) { cancel = row } }
    }

    private func summary(_ row: SendQueueEntry, at date: Date) -> String {
        let time = Int64(date.timeIntervalSince1970 * 1_000)
        let state: String
        if row.state == "failed" {
            switch row.failure {
            case "recipient": state = interfaceText("queue_recipient_unavailable")
            case "media": state = interfaceText("queue_media_unavailable")
            default: state = interfaceText("queue_attention")
            }
        } else if let blockedUntil = row.relayRetryAt, blockedUntil > time {
            state = interfaceText("queue_relay_full")
        } else if row.state == "sending" {
            state = interfaceText("queue_unconfirmed")
        } else {
            if let due = row.sendAt {
                state = due > time ? "" :
                    interfaceText(client.connectionState == .synced ? "queue_waiting" : "queue_waiting_connection")
            } else {
                state = interfaceText("queue_waiting")
            }
        }
        return [state,sendOptionsLabel(SendOptions(sendAt:row.sendAt,silent:row.silent))].filter{!$0.isEmpty}.joined(separator:" · ")
    }
}

@MainActor
private struct ScheduledMessageEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @FocusState private var editorFocused: Bool
    let row: SendQueueEntry
    let draft: ScheduledMessageDraft
    @State private var replacement: String
    @State private var saving = false
    @State private var failed = false

    init(row: SendQueueEntry, draft: ScheduledMessageDraft) {
        self.row = row
        self.draft = draft
        _replacement = State(initialValue: draft.text)
    }

    private var tooLong: Bool { replacement.utf8.count > 48 * 1024 }
    private var canSave: Bool {
        !saving && !tooLong && replacement != draft.text &&
            (draft.isCaption || !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    var body: some View {
        let title = interfaceText(draft.isCaption ? "editing_caption" : "editing_message")
        DialogSurface {
            VStack(alignment: .leading, spacing: 10) {
                Text(title).font(.headline)
                Text(sendOptionsLabel(SendOptions(sendAt: row.sendAt, silent: row.silent)))
                    .font(.caption).foregroundStyle(.secondary)
                TextEditor(text: $replacement)
                    .focused($editorFocused).disabled(saving)
                    .frame(minHeight: 116, idealHeight: 160, maxHeight: 230)
                    .accessibilityLabel(title)
                    .onChange(of: replacement) { _ in failed = false }
                if tooLong || failed {
                    Text(interfaceText(tooLong ? (draft.isCaption ? "caption_too_long" : "queue_text_too_long") : "queue_stale"))
                        .font(.caption).foregroundStyle(.red)
                }
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(saving)
                    Button(interfaceText("save"), action: save).disabled(!canSave)
                }
            }
        }
        .frame(minWidth: 400, idealWidth: 520)
        .task { editorFocused = true }
        .interactiveDismissDisabled(saving)
    }

    private func save() {
        guard canSave, AppLockSession.shared.canShowContent, let at = row.sendAt else { return }
        let value = replacement
        saving = true
        failed = false
        Task {
            let success: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.editScheduledMessage(id: row.id, peer: row.peer, at: at,
                    original: draft, replacement: value) { continuation.resume(returning: $0) }
            }
            saving = false
            if success { dismiss() }
            else { failed = true; editorFocused = true }
        }
    }
}


@MainActor
struct PreparationRows:View {
    @Environment(\.interfaceStrings) private var interfaceText
    let rows:[PendingPreparation]
    let onCancel:(UUID)->Void
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:8){
                ForEach(rows){row in
                    HStack {
                        VStack(alignment:.leading,spacing:3){
                            Text(row.savingMessages ? interfaceText("saved_messages") :
                                    row.filename ?? (row.isSticker ? interfaceText("stickers") :
                                    interfaceText.format("preparation_batch", Int64(row.fileCount))))
                                .font(.caption).lineLimit(1)
                            Text(status(row.progress, savingMessages: row.savingMessages)).font(.caption2).foregroundStyle(.secondary)
                            let fileCount = row.savingMessages ? row.progress.attachmentCount : row.fileCount
                            if row.progress.phase == "preparing", fileCount > 1 {
                                Text(interfaceText.format("preparation_attachment", Int64(row.progress.attachment),
                                                          Int64(fileCount))).font(.caption)
                            }
                            if row.progress.phase=="preparing",row.progress.total>0 {ProgressView(value:Double(row.progress.completed),total:Double(row.progress.total))}
                            else {ProgressView().controlSize(.small)}
                        }
                        Spacer()
                        if row.progress.canCancel {
                            Button { onCancel(row.id) } label: { Image(systemName: "xmark") }
                                .buttonStyle(.borderless)
                                .help(interfaceText("preparation_cancel"))
                                .accessibilityLabel(interfaceText("preparation_cancel"))
                        }
                    }
                }
            }.padding(.horizontal,12).padding(.vertical,6)
        }.frame(height:min(160,CGFloat(rows.count)*64))
    }
    private func status(_ value:PreparationProgress, savingMessages: Bool)->String {
        switch value.phase {
        case "canceling": return interfaceText("preparation_canceling")
        case "committing": return interfaceText(savingMessages ? "saving" : "preparation_queuing")
        case "finished": return interfaceText("preparation_finishing")
        default:
            if value.step == .encodingPhoto { return interfaceText("preparation_photo_waiting") }
            guard value.total > 0 else {
                return interfaceText(value.step == .encodingVideo ? "preparation_video_waiting" : "preparation_waiting")
            }
            let fraction = min(1, max(0, Double(value.completed) / Double(value.total)))
            return interfaceText.format(value.step == .encodingVideo ? "preparation_video_progress" : "preparation_progress",
                                        Int64(fraction * 100))
        }
    }
}
