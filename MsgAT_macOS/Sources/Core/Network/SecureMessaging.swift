// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import ImageIO
import SQLCipher
import UniformTypeIdentifiers
import AppKit

/// Real V2 messaging coordinator. All cryptographic state transitions run serially;
/// database effects and pending receipts are committed before transport ACKs.
struct SendOptions:Sendable,Equatable {var sendAt:Int64?=nil;var silent=false}
struct LiveLocationShare: Sendable, Equatable { let messageID: String; let expiresAt: Int64 }
struct LocationPoint: Sendable, Equatable {
    let latitudeE6: Int
    let longitudeE6: Int

    init?(latitudeE6: Int, longitudeE6: Int) {
        guard (-90_000_000...90_000_000).contains(latitudeE6),
              (-180_000_000...180_000_000).contains(longitudeE6) else { return nil }
        self.latitudeE6 = latitudeE6
        self.longitudeE6 = longitudeE6
    }

    init?(body: [String: Any]) {
        guard let latitude = body["latitude_e6"] as? Int, let longitude = body["longitude_e6"] as? Int else { return nil }
        self.init(latitudeE6: latitude, longitudeE6: longitude)
    }
}
struct MediaSendChoice: Sendable {
    var caption = ""
    var options = SendOptions()
    var asFile = false
    var asAlbum = false
    var compressPhotos = false
    var compressVideos = false
    var videoTrims: [URL: VideoTrim] = [:]
    var itemCaptions: [String] = []
}
struct SendQueueEntry:Sendable,Identifiable {let id:String;let peer:String;let summary:String;let sendAt:Int64?;let silent:Bool;let state:String;let canCancel:Bool;let canRetry:Bool;let peerAlias:String?;let failure:String?;let relayRetryAt:Int64?;let canEdit:Bool}
struct ScheduledMessageDraft: Sendable { let text: String; let isCaption: Bool }

struct ActivityPrivacy:Sendable {let online:Bool;let typing:Bool}

enum PeerActivityStatus: String, Sendable {
    case typing = "peer_typing"
    case online = "peer_online"
    case offline = "peer_offline"
}

final class SecureMessaging: @unchecked Sendable {
    static let shared = SecureMessaging()
    static let sendQueueDidChange=Notification.Name("MsgAT.SendQueueChanged")
    static let messagesRemoved = Notification.Name("MsgAT.MessagesRemoved")
    static let attachmentCacheChanged = Notification.Name("MsgAT.AttachmentCacheChanged")
    static let attachmentMetadataChanged = Notification.Name("MsgAT.AttachmentMetadataChanged")
    static let peerActivityChanged = Notification.Name("MsgAT.PeerActivityChanged")
    private static let deliveryRetryDelay: Int64 = 86_400_000
    private static let deliveryRetryWindow = 6 * deliveryRetryDelay
    private func notifySendQueue(){NotificationCenter.default.post(name:Self.sendQueueDidChange,object:nil)}

    private func notifyDelivery(_ change: MessageDeliveryChange) {
        NotificationCenter.default.post(name: MsgDao.deliveryDidChangeNotification, object: change)
        notifySendQueue()
    }
    private let queue = DispatchQueue(label: "org.msgat.secure-messaging", qos: .userInitiated)
    private let store = SignalProtocolStore()
    private var ready = false
    private var stopped = false
    private var epoch: UInt64 = 0
    private var inFlight = Set<String>()
    private var syncNonce: Data?
    private var nextRelayRetryAt = Int64.max
    private var requested = [String: (id: String, sent: Date)]()
    private var claimedBundles=[String:(bundle:[String:Any],selected:Int64?,request:String,expiresAt:Int64)]()
    private var unavailable = [String: Date]()
    private var timer: DispatchSourceTimer?
    private var nextExpiration: Int64 = 0
    private var nextRetentionSweep: Int64 = 0
    private var publishedBody: String?
    private var directoryEpoch: String?
    private var nextMaintenance = Date.distantPast
    private let imports = DispatchQueue(label: "org.msgat.attachment-import", qos: .utility)
    private let importLock = NSLock()
    private let importDrain = DispatchGroup()
    private var importsClosed = false
    private var pendingImports = 0
    private var attachments: AttachmentQueue!
    private var forwarding: ForwardTaskQueue!
    private var storageMaintenance: EncryptedObjectMaintenance!
    var forwardingTasks: ForwardTaskQueue {forwarding}
    private init() {
        attachments = AttachmentQueue { [weak self] in
            guard let coordinator = self else { return }
            coordinator.perform { try coordinator.flush() }
            coordinator.forwarding?.signal()
        }
        forwarding=ForwardTaskQueue(wakeDownloads: { [weak self] in self?.attachments.signal() }) { [weak self] id,request,cancellation,completion in
            guard let self else {completion(false);return}
            self.processForwardTask(id,request:request,cancellation:cancellation,completion:completion)
        }
        storageMaintenance=EncryptedObjectMaintenance { [weak self] in self?.attachments.signal() }
        let activityTimer=DispatchSource.makeTimerSource(queue:queue)
        activityTimer.schedule(deadline:.now()+2,repeating:2)
        activityTimer.setEventHandler {[weak self] in
            guard let self else {return}
            self.activityLock.lock();let enabled=self.activityForeground && (self.activityOnline || self.activityTyping);self.activityLock.unlock()
            if enabled {self.signalActivity()}
        }
        self.activityTimer=activityTimer;activityTimer.resume()
        let scheduleTimer=DispatchSource.makeTimerSource(queue:queue)
        scheduleTimer.schedule(deadline:.now()+1,repeating:1)
        scheduleTimer.setEventHandler{[weak self] in
            guard let self, min(self.nextExpiration, self.nextRetentionSweep) <= self.now ||
                    (self.ready && min(self.nextScheduledSend, self.nextRelayRetryAt) <= self.now) else { return }
            do {
                try self.flush()
            } catch {
                let time = self.now
                if self.nextScheduledSend <= time {
                    self.nextScheduledSend = time + 30_000
                    self.scheduleDeadlineLoaded = false
                }
                if self.nextRelayRetryAt <= time { self.nextRelayRetryAt = time + 30_000 }
                NSLog("Pending sends retained (%@)", String(describing: type(of: error)))
            }
        };self.scheduleTimer=scheduleTimer;scheduleTimer.resume()
    }
    func attachmentProgress(_ id: String) -> AttachmentProgress? { attachments.progress(id) }
    func shutdown() {
        importLock.withLock { importsClosed = true }
        forwarding.stop()
        storageMaintenance.close()
        attachments.close()
        queue.sync { self.stopped=true; self.ready = false; self.activityTimer?.cancel();self.activityTimer=nil;self.scheduleTimer?.cancel();self.scheduleTimer=nil;self.activityLock.lock();self.activityReceived.removeAll();self.activityOrder.removeAll();self.activityLock.unlock(); self.timer?.cancel(); self.timer = nil }
        importDrain.wait()
        imports.sync { }
        queue.sync { } // Drain import completions/failures before destroying identity protection.
    }

    func beginSync(epoch: UInt64, directoryNonce: Data?) { perform { [self] in
        guard NWClient.shared.isCurrent(epoch) else { return }
        self.epoch = epoch
        self.ready = false
        self.syncNonce = nil
        self.inFlight.removeAll()
        self.requested.removeAll();self.claimedBundles.removeAll()
        guard let directoryNonce, directoryNonce.count == 16, directoryNonce.contains(where: { $0 != 0 }) else {
            throw self.failure("Missing relay directory epoch")
        }
        let directoryEpoch = directoryNonce.base64EncodedString()
        self.directoryEpoch = directoryEpoch
        let bundle = try self.store.publicBundle(directoryEpoch: directoryEpoch)
        self.publishedBody = bundle["body"] as? String
        self.nextMaintenance = Date().addingTimeInterval(3600)
        guard self.send(GhostPacket(type: GhostPacket.typeV2KeyPublish,
            targetMailboxId: KeychainManager.shared.myMailboxId, encryptedData: try JSONSerialization.data(withJSONObject: bundle))) else { return }
        if self.timer == nil {
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 30, repeating: 30)
            timer.setEventHandler { [weak self] in self?.retry() }
            self.timer = timer
            timer.resume()
        }
    } }

    func disconnected(epoch: UInt64) { queue.async {
        guard NWClient.shared.isCurrent(epoch) else { return }
        self.epoch = epoch
        self.ready = false
        self.syncNonce = nil
        self.inFlight.removeAll()
        self.requested.removeAll();self.claimedBundles.removeAll()
        self.timer?.cancel()
        self.timer = nil
    } }

    func handle(_ packet: GhostPacket, epoch: UInt64) { perform {
        guard self.epoch == epoch, NWClient.shared.isCurrent(epoch) else { return }
        switch packet.type {
        case GhostPacket.typeV2KeyPublished:
            guard let bytes = packet.encryptedData, bytes.count < 1024,
                  let response = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], response["published"] as? Bool == true else { throw self.failure("Invalid key publication acknowledgement") }
            NWClient.shared.reportInitialSyncProgress(epoch: epoch)
            self.ready = true
            self.pull()
            try self.flush()
        case GhostPacket.typeV2KeyResponse:
            try self.keyResponse(packet)
        case GhostPacket.typeV2Deferred:
            guard packet.payloadLen == 0, let recipient = packet.targetMailboxId,
                  let id = self.eventID(packet.nonceOrIv) else { throw self.failure("Invalid relay deferral") }
            if self.inFlight.contains(id) {
                let deferred = try AppDatabase.shared.transaction { db in
                    try db.execute("""
                        INSERT INTO protocol_relay_deferrals(recipient,retry_at)
                        SELECT recipient,? FROM protocol_outbox WHERE event_id=? AND recipient=? AND delivered=0
                        ON CONFLICT(recipient) DO UPDATE SET retry_at=excluded.retry_at
                        """, values: [.integer(self.now + 30_000), .text(id), .text(recipient)])
                    return try db.query("SELECT changes()", map: { sqlite3_column_int($0, 0) > 0 }).first == true
                }
                if deferred {
                    self.inFlight.remove(id)
                    self.notifySendQueue()
                    try self.flush()
                }
            }
        case GhostPacket.typeV2Rejected:
            guard let peer=packet.targetMailboxId, let id=self.eventID(packet.nonceOrIv), let proof=packet.encryptedData, proof.count==113 else { throw self.failure("Invalid revocation rejection") }
            if try !AppDatabase.shared.query("SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)],map:{ _ in true }).isEmpty {
                try self.markPeerRevoked(peer,proof:proof.base64EncodedString())
                try self.flush()
            }
        case GhostPacket.typeV2Stored:
            guard packet.payloadLen == 0, let recipient = packet.targetMailboxId,
                  let event = self.eventID(packet.nonceOrIv) else { throw self.failure("Invalid durable acknowledgement") }
            let businessStored = try AppDatabase.shared.transaction { db -> Bool? in
                try db.execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0",values:[.text(event),.text(recipient)])
                let matched = try db.query("SELECT changes()", map: { sqlite3_column_int($0, 0) > 0 }).first == true
                guard matched else { return nil }
                try db.execute("DELETE FROM object_transfers WHERE event_id=? AND direction=0", values: [.text(event)])
                try db.execute("DELETE FROM live_location_outgoing WHERE event_id=? AND peer=?", values: [.text(event), .text(recipient)])
                try db.execute("UPDATE messages SET status=1 WHERE messageId=? AND conversationUuid=? AND isMine=1 AND isRevoked=0 AND status=0",values:[.text(event),.text(recipient)])
                let stored = try db.query("SELECT changes()", map: { sqlite3_column_int($0, 0) > 0 }).first == true
                if stored {
                    try db.execute("""
                        INSERT OR IGNORE INTO message_delivery_retries(message_id,retry_at)
                        SELECT message_id,? FROM message_sends WHERE message_id=? AND kind<>'chat.location.start'
                        """, values: [.integer(self.now + Self.deliveryRetryDelay), .text(event)])
                }
                return stored
            }
            guard let businessStored else { return }
            if businessStored { self.notifyDelivery(MessageDeliveryChange(peer: recipient, messageID: event)) }
            self.inFlight.remove(event)
            try self.flush()
        case GhostPacket.typeV2Delivery:
            guard let sender = packet.targetMailboxId, let event = self.eventID(packet.nonceOrIv) else { throw self.failure("Invalid delivery envelope") }
            var call: SignalProtocolStore.Incoming?
            var pinTarget: String?
            var editedCaption: String?
            var contactUnavailable=false;var contactsChanged=false;var businessChanged=false;var notify=false;var silent=false;var activityObservation:[String:Any]?
            var deliveryChange: MessageDeliveryChange?
            let delivery = try self.store.receive(packet.encryptedData ?? Data(), authenticatedSender: sender, expectedEventID: event, onDuplicate: { peer,id,transaction in try self.rescheduleReceipt(peer:peer,message:id,in:transaction) },notifyBusiness:false) { incoming, transaction in
                if incoming.kind == "chat.activity" {
                    activityObservation = try self.receiveActivity(incoming, in: transaction)
                } else if incoming.kind == "chat.receipt" {
                    deliveryChange = try self.applyDeliveryReceipt(incoming, in: transaction)
                } else {
                    try self.apply(incoming, transaction: transaction) { pinTarget = $0 }
                    businessChanged = true
                    if incoming.kind == "chat.edit", incoming.body["caption"] as? Bool == true {
                        editedCaption = incoming.body["message_id"] as? String
                    }
                }
                silent=incoming.body["silent"] as? Bool == true
                notify=try !ConversationIndexStore.muted(transaction,peer:sender,now:self.now) && ["chat.text","chat.contact","chat.location","chat.location.start","chat.attachment"].contains(incoming.kind) && !transaction.query("SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=0 AND isRead=0",values:[.text(event),.text(sender)],map:{_ in true}).isEmpty
                contactsChanged=incoming.kind.hasPrefix("contact.")
                contactUnavailable=try contactsChanged && transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",values:[.text(sender)],map:{_ in true}).isEmpty
                if incoming.kind.hasPrefix("call."), try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(sender)], map: { _ in true }).isEmpty { call = incoming }
            }
            guard delivery != .deferred else { return }
            let fresh = delivery == .applied
            NWClient.shared.reportInitialSyncProgress(epoch: epoch)
            if fresh, notify || pinTarget != nil {
                NotificationHelper.shared.showPrivateMessageNotification(senderUuid: sender, messageID: pinTarget ?? event, silent: silent, pinned: pinTarget != nil)
            }
            if fresh,businessChanged {NotificationCenter.default.post(name:MsgDao.didChangeNotification,object:nil)}
            if fresh, let editedCaption {
                NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: editedCaption)
            }
            if fresh, let deliveryChange { self.notifyDelivery(deliveryChange) }
            if fresh,let activityObservation {self.publishActivity(sender,value:activityObservation)}
            if fresh,contactsChanged {ContactDirectoryStore.invalidate()}
            if fresh,contactUnavailable {self.endUnavailableContact(sender)}
            if fresh, let call {
                DispatchQueue.main.async { CallSignalingHandler.shared.receive(peer: call.sender, kind: call.kind, body: call.body) }
            }
            _ = self.send(GhostPacket(type: GhostPacket.typeV2Received, targetMailboxId: sender, nonceOrIv: self.eventBytes(event)))
            try self.flush()
        case GhostPacket.typeV2SyncEnd:
            guard packet.payloadLen == 0, packet.targetMailboxId == KeychainManager.shared.myUuid else {
                throw self.failure("Invalid synchronization acknowledgement")
            }
            guard let nonce = self.syncNonce, packet.nonceOrIv == nonce else { return }
            self.syncNonce = nil
            NWClient.shared.completeInitialSync(epoch: epoch)
            try self.flush()
        default: break
        }
    } }

    func requireCallable(_ recipient: String, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard self.ready, try AppDatabase.shared.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(recipient)], map: { _ in true }).isEmpty == false else { completion(false); return }
            completion(true)
        }
    }

    func sendCallSignal(to recipient: String, kind: String, body: [String: Any], createdAt: Int64, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            guard self.ready, kind.hasPrefix("call."), let expiry = body["expires_at"] as? Int64, expiry > now else { throw self.failure("Call signaling expired or disconnected") }
            try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient, kind: kind, body: body, createdAt: createdAt)) { transaction in
                guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(recipient)], map: { _ in true }).isEmpty else { throw self.failure("Accept the contact before calling") }
            }
            completion(true)
            do { try self.flush() } catch { self.report(error) }
        }
    }

    func sendContactCard(to recipient: String, account: String, name: String, reply: ChatMessage?, draft: DraftSendCommit?=nil,
                         options: SendOptions=SendOptions(), completion: @escaping @Sendable (Bool)->Void) {
        sendText(to: recipient, text: name, reply: reply, draft: draft, options: options, contactAccount: account, completion: completion)
    }

    func sendLocation(to recipient: String, point: LocationPoint, label: String?, reply: ChatMessage?, draft: DraftSendCommit?=nil,
                      options: SendOptions=SendOptions(), completion: @escaping @Sendable (Bool)->Void) {
        sendText(to: recipient, text: label ?? "Location", reply: reply, draft: draft, options: options, location: point, completion: completion)
    }

    func startLiveLocation(to recipient: String, point: LocationPoint, durationMs: Int64, label: String?,
                           reply: ChatMessage?, draft: DraftSendCommit? = nil, silent: Bool = false,
                           completion: @escaping @Sendable (LiveLocationShare?) -> Void) {
        perform(onFailure: {
            if let draft { draft.owner.observeFailedSend(draft) }
            completion(nil)
        }) {
            guard (1...(8 * 60 * 60 * 1000)).contains(durationMs) else {
                throw self.failure("Choose a live location duration of up to eight hours")
            }
            if let draft {
                guard !draft.clearsText, draft.draft.peer == recipient, draft.draft.replyId == reply?.messageId,
                      draft.clearRevision > draft.draft.revision else {
                    throw self.failure("The draft no longer matches the message")
                }
            }
            _ = try self.store.publicBundle()
            let id = UUID().uuidString.lowercased()
            let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
            var expiresAt = timestamp + durationMs
            var body: [String: Any] = [
                "latitude_e6": point.latitudeE6, "longitude_e6": point.longitudeE6, "expires_at": expiresAt
            ]
            if let label, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { body["label"] = label }
            if silent { body["silent"] = true }
            try AppDatabase.shared.transaction(notifyChanged: true) { db in
                try self.requireAccepted(recipient, in: db)
                body["reply"] = try self.makeReply(db, peer: recipient, original: reply, selection: draft?.draft.replyQuote)
                guard try !LiveLocationStore.hasActiveShare(db, peer: recipient, now: timestamp) else {
                    throw self.failure("Stop the current live location before sharing again")
                }
                if let draft {
                    guard try !db.query("SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",
                                        values: [.text(recipient), .text(draft.draft.token)], map: { _ in true }).isEmpty else {
                        throw self.failure("The conversation changed before the location was queued")
                    }
                }
                let duration = try self.stampExpiration(&body, peer: recipient, in: db)
                if duration > 0 {
                    expiresAt = min(expiresAt, timestamp + duration)
                    body["expires_at"] = expiresAt
                }
                let kind = "chat.location.start"
                let event = SignalProtocolStore.Pending(eventID: id, recipient: recipient, kind: kind, body: body, createdAt: timestamp)
                guard try self.store.enqueue(event, in: db),
                      try LiveLocationStore.apply(db, peer: recipient, mine: true, kind: kind,
                                                  eventID: id, body: body, createdAt: timestamp, now: timestamp) else {
                    throw self.failure("The live location could not be queued")
                }
                try self.insertText(db, id: id, peer: recipient, mine: true, text: body["label"] as? String ?? "Location",
                                    timestamp: timestamp, reply: body["reply"] as? [String: Any], location: point, live: true)
                if let draft { try ConversationDraftStore.clearForCommittedSend(db, draft) }
            }
            if let draft { draft.owner.observeCommittedSend(draft) }
            completion(LiveLocationShare(messageID: id, expiresAt: expiresAt))
            do { try self.flush() } catch { self.report(error) }
        }
    }

    func liveLocation(peer: String, messageID: String) throws -> LiveLocationSnapshot? {
        try AppDatabase.shared.transaction { db in try LiveLocationStore.read(db, peer: peer, messageID: messageID) }
    }

    func updateLiveLocation(to recipient: String, messageID: String, point: LocationPoint,
                            completion: @escaping @Sendable (Bool) -> Void) {
        changeLiveLocation(to: recipient, messageID: messageID, point: point, completion: completion)
    }

    func stopLiveLocation(to recipient: String, messageID: String, completion: @escaping @Sendable (Bool) -> Void) {
        changeLiveLocation(to: recipient, messageID: messageID, point: nil, completion: completion)
    }

    private func changeLiveLocation(to recipient: String, messageID: String, point: LocationPoint?,
                                    completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            var active = point == nil
            let changed = try AppDatabase.shared.transaction { db in
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                guard let state = try LiveLocationStore.read(db, peer: recipient, messageID: messageID, mineOnly: true),
                      !state.ended, now < state.expiresAt else { return false }
                guard try !db.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",
                                    values: [.text(recipient)], map: { _ in true }).isEmpty else { return false }
                active = true
                let end = point == nil
                if end, try !db.query("SELECT 1 FROM protocol_pending_events WHERE event_id=? AND recipient=? AND kind='chat.location.start'",
                                      values: [.text(messageID), .text(recipient)], map: { _ in true }).isEmpty {
                    try db.execute("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",
                                   values: [.text(messageID), .text(recipient)])
                    try db.execute("DELETE FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1",
                                   values: [.text(messageID), .text(recipient)])
                    return true
                }
                if !end, point == state.point || state.revision >= 4095 { return false }
                let id = UUID().uuidString.lowercased()
                let kind = end ? "chat.location.end" : "chat.location.update"
                let timestamp = max(now, state.startedAt)
                var body: [String: Any] = [
                    "message_id": messageID, "started_at": state.startedAt, "expires_at": state.expiresAt,
                    "revision": state.revision + 1
                ]
                if let point {
                    body["latitude_e6"] = point.latitudeE6
                    body["longitude_e6"] = point.longitudeE6
                }
                guard try LiveLocationStore.apply(db, peer: recipient, mine: true, kind: kind,
                                                  eventID: id, body: body, createdAt: timestamp, now: now) else { return false }
                let event = SignalProtocolStore.Pending(eventID: id, recipient: recipient, kind: kind, body: body, createdAt: timestamp)
                guard try self.store.enqueue(event, in: db) else { throw self.failure("The live location could not be queued") }
                try LiveLocationStore.replaceOutgoing(db, eventID: id, peer: recipient, messageID: messageID,
                                                      expiresAt: state.expiresAt, end: end)
                return true
            }
            if changed { NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil) }
            completion(active)
            do { try self.flush() } catch { self.report(error) }
        }
    }

    func sendText(to recipient: String,text: String,reply: ChatMessage?,draft: DraftSendCommit?=nil,preview: WebPreviewDraft?=nil,options:SendOptions=SendOptions(),contactAccount:String?=nil,location:LocationPoint?=nil,preparation:ObjectPreparation?=nil,completion: @escaping @Sendable (Bool)->Void) {
        let plainText = contactAccount == nil && location == nil
        guard (contactAccount == nil || location == nil), plainText || preview == nil else { completion(false); return }
        guard reserveImport() else {if let draft {draft.owner.observeFailedSend(draft)};completion(false);return}
        imports.async {
            var imported: [String:Any]?
            do {
                if let image = preview?.image {
                    imported = try EncryptedObjectStore.shared.importBytes(image, filename: "preview.jpg", mime: "image/jpeg", preparation: preparation)
                }
                let cover=imported
                self.perform(onFailure:{
                    if let cover {try? EncryptedObjectStore.shared.discardCopy(cover)}
                    self.releaseImport();if let draft {draft.owner.observeFailedSend(draft)};completion(false)
                }) {
                    guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf8.count <= 48 * 1024 else { throw self.failure("Message text is empty or too large") }
                    if let draft {
                        guard draft.clearsText == plainText, draft.draft.peer == recipient,
                              !plainText || draft.draft.text == text, draft.draft.replyId == reply?.messageId,
                              draft.clearRevision > draft.draft.revision else { throw self.failure("The draft no longer matches the message") }
                    }
                    _ = try self.store.publicBundle()
                    let id = UUID().uuidString.lowercased()
                    let now = Int64(Date().timeIntervalSince1970 * 1000)
                    let kind: String
                    var body: [String: Any]
                    if let location {
                        kind = "chat.location"
                        body = ["latitude_e6":location.latitudeE6,"longitude_e6":location.longitudeE6,"label":text]
                    } else if let contactAccount {
                        kind = "chat.contact"
                        body = ["account":contactAccount,"name":text]
                    } else {
                        kind = "chat.text"
                        body = ["text":text]
                    }
                    if options.silent {body["silent"]=true}
                    if let preview {body["preview"]=preview.body(image:cover)}
                    try AppDatabase.shared.transaction(notifyChanged: true) { transaction in
                        body["reply"] = try self.makeReply(transaction, peer: recipient, original: reply, selection: draft?.draft.replyQuote)
                        try self.stampExpiration(&body, peer: recipient, in: transaction)
                        try preparation?.beginCommit()
                        let event = SignalProtocolStore.Pending(eventID: id, recipient: recipient, kind: kind, body: body, createdAt: now)
                        guard try self.store.enqueue(event, in: transaction) else {
                            throw self.failure("The message could not be queued")
                        }
                        let accepted = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(recipient)], map: { _ in true })
                        guard !accepted.isEmpty else { throw self.failure("Accept the contact request before sending messages") }
                        if let contactAccount, contactAccount != KeychainManager.shared.myUuid {
                            try self.requireAccepted(contactAccount, in: transaction)
                        }
                        if let draft {
                            guard try !transaction.query("SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",values:[.text(recipient),.text(draft.draft.token)],map:{ _ in true }).isEmpty else {
                                throw self.failure("The conversation changed before the message was queued")
                            }
                        }
                        if try !self.holdScheduled(event,options:options,in:transaction){try self.insertText(transaction,id:id,peer:recipient,mine:true,text:text,timestamp:now,reply:body["reply"] as? [String:Any],preview:body["preview"] as? [String:Any],contactAccount:contactAccount,location:location)}
                        if let draft { try ConversationDraftStore.clearForCommittedSend(transaction, draft) }
                    }
                    if let draft { draft.owner.observeCommittedSend(draft) }
                    self.releaseImport();completion(true)
                    do { try self.flush() } catch { self.report(error) }
                }
            } catch {
                if let imported {try? EncryptedObjectStore.shared.discardCopy(imported)}
                self.releaseImport()
                if let draft { draft.owner.observeFailedSend(draft) }
                if preparation?.wasCancelled != true && !(error is CancellationError) { self.report(error) }
                completion(false)
            }
        }
    }

    func sendSticker(to recipient:String,id stickerID:String,reply:ChatMessage?=nil,draft:DraftSendCommit?=nil,options:SendOptions=SendOptions(),preparation:ObjectPreparation?=nil,completion:@escaping @Sendable(Bool)->Void){
        let finish:@Sendable(Bool)->Void={ok in preparation?.finish();completion(ok)}
        guard reserveImport() else {finish(false);return}
        imports.async {
            var imported:[String:Any]?
            do {
                try preparation?.check()
                let original=try AppDatabase.shared.transaction {db in try self.requireAccepted(recipient,in:db);guard let source=try StickerLibraryStore.source(db,id:stickerID) else {throw self.failure("Sticker is no longer available.")};return source}
                imported=try EncryptedObjectStore.shared.copyObject(try MessageActionsStore.shared.object(original.manifest),preparation:preparation)
                let copy=imported!
                self.perform(onFailure:{try? EncryptedObjectStore.shared.discardCopy(copy);self.releaseImport();if let draft {draft.owner.observeFailedSend(draft)};finish(false)}) {
                    let created=self.now;let id=UUID().uuidString.lowercased()
                    try preparation?.beginCommit()
                    try AppDatabase.shared.transaction(notifyChanged:true){db in
                        try self.requireAccepted(recipient,in:db)
                        guard try StickerLibraryStore.source(db,id:stickerID)?.sha==original.sha else {throw self.failure("Sticker changed while preparing.")}
                        if let draft {guard !draft.clearsText,draft.draft.peer==recipient,draft.draft.replyId==reply?.messageId,draft.clearRevision>draft.draft.revision,
                            try !db.query("SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",values:[.text(recipient),.text(draft.draft.token)],map:{_ in true}).isEmpty else {throw self.failure("The draft no longer matches the sticker.")}}
                        var body:[String:Any]=["object":copy,"caption":"","sticker":true];if options.silent {body["silent"]=true}
                        body["reply"] = try self.makeReply(db, peer: recipient, original: reply, selection: draft?.draft.replyQuote)
                        try self.stampExpiration(&body, peer: recipient, in: db)
                        guard try self.store.enqueue(.init(eventID:id,recipient:recipient,kind:"chat.attachment",body:body,createdAt:created),in:db) else {throw self.failure("Sticker could not be queued.")}
                        if try !self.holdScheduled(.init(eventID:id,recipient:recipient,kind:"chat.attachment",body:body,createdAt:created),options:options,in:db){try EncryptedObjectStore.remember(copy,in:db,available:true);try self.insertAttachment(db,id:id,peer:recipient,mine:true,manifest:copy,caption:"",timestamp:created,reply:body["reply"] as? [String:Any],sticker:true)}
                        try db.execute("UPDATE sticker_items SET last_used=? WHERE id=?",values:[.integer(created),.text(stickerID)]);if let draft {try ConversationDraftStore.clearForCommittedSend(db,draft)}
                    }
                    self.releaseImport();if let draft {draft.owner.observeCommittedSend(draft)};finish(true);do{try self.flush()}catch{self.report(error)}
                }
            } catch {if let imported {try? EncryptedObjectStore.shared.discardCopy(imported)};self.releaseImport();if let draft {draft.owner.observeFailedSend(draft)};finish(false);if preparation?.wasCancelled != true {self.report(error)}}
        }
    }

    func sendMedia(to recipient: String, sources: [MediaSendSource], reply: ChatMessage?, draft: DraftSendCommit? = nil,
                   options: SendOptions = SendOptions(), caption: String = "", asFile: Bool = false, asAlbum: Bool = false, compressPhotos: Bool = false,
                   compressVideos: Bool = false, videoTrims: [URL: VideoTrim] = [:], itemCaptions: [String] = [],
                   preparation: ObjectPreparation? = nil, completion: @escaping @Sendable (Bool) -> Void) {
        let files = sources.compactMap(\.fileURL)
        let uniqueSources = Set(files)
        guard (1...10).contains(sources.count), uniqueSources.count == files.count,
              uniqueSources.isSuperset(of: videoTrims.keys) else {
            if let draft { draft.owner.observeFailedSend(draft) }
            preparation?.finish()
            completion(false)
            return
        }
        let importers: [AttachmentImporter] = sources.map { source in
            {
                let mime = asFile ? "application/octet-stream" : source.mime
                switch source {
                case .file(let url):
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    let trim = videoTrims[url]
                    if trim != nil, !source.mime.hasPrefix("video/") {
                        throw self.failure("The selected video is unavailable.")
                    }
                    if source.mime.hasPrefix("video/"), trim != nil || compressVideos && !asFile {
                        if let manifest = try VideoCompression.importOnWorker(url, preparation: preparation,
                                                                             trim: trim, compress: compressVideos && !asFile, asFile: asFile) {
                            return manifest
                        }
                        if trim != nil { throw self.failure("The edited video could not be exported.") }
                        try preparation?.setStep(.importing)
                    }
                    if compressPhotos, !asFile,
                       let result = try Self.importCompressedPhoto(filename: source.name, mime: mime, preparation: preparation, encode: {
                           try PhotoCompression.encode(file: url, mime: mime, preparation: preparation)
                       }) {
                        return result
                    }
                    return try EncryptedObjectStore.shared.importFile(url, mime: mime, preparation: preparation)
                case .bytes(let bytes, let filename, _):
                    guard bytes.count <= 100 * 1024 * 1024 else { throw self.failure("Files cannot exceed 100 MB") }
                    if compressPhotos, !asFile,
                       let result = try Self.importCompressedPhoto(filename: filename, mime: mime, preparation: preparation, encode: {
                           try PhotoCompression.encode(bytes, mime: mime, preparation: preparation)
                       }) {
                        return result
                    }
                    return try EncryptedObjectStore.shared.importBytes(bytes, filename: filename, mime: mime, preparation: preparation)
                case .recording(let recording):
                    return try EncryptedObjectStore.shared.importRecording(recording.file, preparation: preparation, asFile: asFile)
                }
            }
        }
        importMedia(to: recipient, reply: reply, draft: draft, options: options, caption: caption,
                    preparation: preparation, completion: completion, importers: importers, asAlbum: asAlbum,
                    itemCaptions: itemCaptions)
    }

    private static func importCompressedPhoto(filename: String, mime: String, preparation: ObjectPreparation?,
                                              encode: () throws -> Data?) throws -> [String: Any]? {
        guard PhotoCompression.supports(mime) else { return nil }
        try preparation?.setStep(.encodingPhoto)
        if var compressed = try encode() {
            defer { compressed.resetBytes(in: 0..<compressed.count) }
            try preparation?.setStep(.importing)
            return try EncryptedObjectStore.shared.importBytes(compressed, filename: filename, mime: mime, preparation: preparation)
        }
        try preparation?.setStep(.importing)
        return nil
    }

    private typealias AttachmentImporter = @Sendable () throws -> [String: Any]

    private func importMedia(to recipient: String, reply: ChatMessage?, draft: DraftSendCommit?, options: SendOptions,
                             caption: String, preparation: ObjectPreparation?, completion: @escaping @Sendable (Bool) -> Void,
                             importers: [AttachmentImporter], asAlbum: Bool = false, itemCaptions: [String] = []) {
        let finish: @Sendable (Bool) -> Void = { saved in
            if let draft {
                if saved { draft.owner.observeCommittedSend(draft) }
                else { draft.owner.observeFailedSend(draft) }
            }
            preparation?.finish()
            completion(saved)
        }
        guard reserveImport() else { finish(false); return }
        perform(onFailure: { self.releaseImport(); finish(false) }) {
            if let draft {
                guard !draft.clearsText, draft.draft.peer == recipient, draft.draft.replyId == reply?.messageId,
                      draft.clearRevision > draft.draft.revision else { throw self.failure("The draft no longer matches the attachments") }
            }
            guard itemCaptions.isEmpty || itemCaptions.count == importers.count else {
                throw self.failure("The selected attachments have changed")
            }
            guard caption.utf8.count <= 48 * 1024, itemCaptions.allSatisfy({ $0.utf8.count <= 48 * 1024 }) else {
                throw self.failure("Caption is too long")
            }
            try preparation?.check()
            try self.requireAccepted(recipient)
            self.imports.async {
                defer { self.releaseImport() }
                var imported = [[String: Any]]()
                do {
                    for (index, importer) in importers.enumerated() {
                        try preparation?.startAttachment(index: index + 1, count: importers.count)
                        imported.append(try importer())
                    }
                    let albumId: String?
                    if asAlbum {
                        guard imported.count >= 2, imported.allSatisfy({ item in
                            guard let mime = item["mime"] as? String else { return false }
                            return mime.hasPrefix("image/") || mime.hasPrefix("video/")
                        }) else { throw self.failure("An album needs two or more photos or videos") }
                        albumId = UUID().uuidString.lowercased()
                    } else {
                        albumId = nil
                    }
                    let manifests = imported
                    self.perform(onFailure: {
                        self.discardAttachmentImports(manifests)
                        finish(false)
                    }) {
                        _ = try self.store.publicBundle()
                        let ids = manifests.map { _ in UUID().uuidString.lowercased() }.sorted()
                        let now = self.now
                        try preparation?.beginCommit()
                        try AppDatabase.shared.transaction(notifyChanged: true) { transaction in
                            try self.requireAccepted(recipient, in: transaction)
                            if let draft {
                                guard try !transaction.query("SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",
                                    values: [.text(recipient), .text(draft.draft.token)], map: { _ in true }).isEmpty else {
                                    throw self.failure("The conversation changed before the attachments were queued")
                                }
                            }
                            let firstReply = try self.makeReply(transaction, peer: recipient, original: reply, selection: draft?.draft.replyQuote)
                            for (index, manifest) in manifests.enumerated() {
                                let id = ids[index]
                                let itemCaption = itemCaptions.isEmpty ? (index == 0 ? caption : "") : itemCaptions[index]
                                let itemReply = index == 0 ? firstReply : nil
                                var body: [String: Any] = ["object": manifest, "caption": itemCaption]
                                let album: [String: Any]? = albumId.map {
                                    ["id": $0, "index": index, "count": manifests.count]
                                }
                                if let album { body["album"] = album }
                                if options.silent { body["silent"] = true }
                                if let itemReply { body["reply"] = itemReply }
                                try self.stampExpiration(&body, peer: recipient, in: transaction)
                                let event = SignalProtocolStore.Pending(eventID: id, recipient: recipient, kind: "chat.attachment", body: body, createdAt: now)
                                guard try self.store.enqueue(event, in: transaction) else {
                                    throw self.failure("The attachments could not be queued")
                                }
                                if try !self.holdScheduled(event, options: options, in: transaction) {
                                    try EncryptedObjectStore.remember(manifest, in: transaction, available: true)
                                    try self.insertAttachment(transaction, id: id, peer: recipient, mine: true, manifest: manifest,
                                        caption: itemCaption, timestamp: now, reply: body["reply"] as? [String: Any], album: album)
                                }
                            }
                            if let draft { try ConversationDraftStore.clearForCommittedSend(transaction, draft) }
                        }
                        finish(true)
                        do { try self.flush() } catch { self.report(error) }
                    }
                } catch {
                    self.discardAttachmentImports(imported)
                    finish(false)
                    if preparation?.wasCancelled != true { self.report(error) }
                }
            }
        }
    }

    private func discardAttachmentImports(_ manifests: [[String: Any]]) {
        for manifest in manifests {
            do { try EncryptedObjectStore.shared.discardCopy(manifest) }
            catch { NSLog("Staged attachment cleanup failed (%@)", String(describing: type(of: error))) }
        }
    }

    private func reserveImport() -> Bool {
        importLock.withLock {
            guard !importsClosed, pendingImports < 4 else { return false }
            pendingImports += 1
            importDrain.enter()
            return true
        }
    }

    private func releaseImport() {
        importLock.withLock {
            pendingImports -= 1
            importDrain.leave()
        }
    }

    func forward(sourcePeer: String?,ids: [String],recipients: [String],hideSender: Bool=false,hideCaption: Bool=false,options:SendOptions=SendOptions(),omitPreview:Bool=false,completion: @escaping @Sendable (Bool)->Void) {
        forwarding.enqueue(sourcePeer:sourcePeer,ids:ids,recipients:recipients,hideSender:hideSender,hideCaption:hideCaption,options:options,omitPreview:omitPreview,completion:completion)
    }
    private func processForwardTask(_ jobId: String,request: ForwardQueueRequest,cancellation: ForwardCancellation,completion: @escaping @Sendable (Bool)->Void) {
        let sourcePeer=request.sourcePeer;let ids=request.ids;let recipients=request.recipients;let hideSender=request.hideSender;let hideCaption=request.hideCaption
        guard reserveImport() else {completion(false);return}
        perform(onFailure:{self.releaseImport();completion(false)}) {
            guard (1...20).contains(recipients.count),Set(recipients).count==recipients.count else {throw self.failure("Select between 1 and 20 different recipients.")}
            for peer in recipients {try self.requireAccepted(peer)}
            let includePreview = request.omitPreview != true
            let source=try MessageActionsStore.shared.capture(peer:sourcePeer,ids:ids,includePreview:includePreview)
            try ForwardTaskQueue.requireSnapshot(source,request)
            try MessageActionsStore.shared.requireCopySpace(source,copies:recipients.count,includePreview:includePreview)
            self.imports.async {
                defer {self.releaseImport()}
                var prepared: [(String,[PreparedMessageAction])]=[]
                do {
                    let count=recipients.count*source.reduce(0) {$0+($1.manifest == nil ? 0 : 1)+(!includePreview || $1.previewManifest == nil ? 0 : 1)}
                    var index=0
                    for peer in recipients {
                        try cancellation.check()
                        let rows=try MessageActionsStore.shared.prepare(source,check:{try cancellation.check()},
                            preparation:cancellation.preparation,includePreview:includePreview,detectDamagedCache:true) {
                            index+=1
                            try cancellation.preparation.startAttachment(index:index,count:count)
                        }
                        prepared.append((peer,rows))
                    }
                    let batches=prepared
                    self.perform(onFailure:{for (_,rows) in batches {MessageActionsStore.shared.discard(rows)};completion(false)}) {
                        _=try self.store.publicBundle()
                        try AppDatabase.shared.transaction(notifyChanged:true) {db in
                            try cancellation.check()
                            guard try !db.query("SELECT 1 FROM forward_jobs WHERE id=? AND state='preparing'",values:[.text(jobId)],map:{_ in true}).isEmpty else {throw self.failure("Forwarding task is no longer pending.")}
                            try cancellation.preparation.beginCommit()
                            let timestamp=self.now
                            for (peer,rows) in batches {
                                try self.requireAccepted(peer,in:db)
                                let newIds=rows.map {_ in UUID().uuidString.lowercased()}.sorted()
                                for (index,row) in rows.enumerated() {
                                    try MessageActionsStore.shared.requireCurrent(db,row.source)
                                    let id=newIds[index]
                                    let manifest=try row.objectJSON.map {try MessageActionsStore.shared.object($0)}
                                    let caption=hideCaption ? "" : row.source.caption
                                    let card = row.source.contactAccount
                                    let point = row.source.location
                                    let kind: String
                                    var body: [String: Any]
                                    if let point {
                                        kind = "chat.location"
                                        body = ["latitude_e6":point.latitudeE6,"longitude_e6":point.longitudeE6,"label":row.source.text]
                                    } else if let card {
                                        kind = "chat.contact"
                                        body = ["account":card,"name":row.source.text]
                                    } else if let manifest {
                                        kind = "chat.attachment"
                                        body = ["object":manifest,"caption":caption]
                                    } else {
                                        kind = "chat.text"
                                        body = ["text":row.source.text]
                                    }
                                    if request.silent==true {body["silent"]=true}
                                    if row.source.isSticker {body["sticker"]=true}
                                    if includePreview, let preview=try MessageActionsStore.shared.previewBody(row) {body["preview"]=preview}
                                    if !hideSender {body["forward"]=MessageActionsStore.shared.forwardHeader(row.source)}
                                    try self.stampExpiration(&body, peer: peer, in: db)
                                    let pending=SignalProtocolStore.Pending(eventID:id,recipient:peer,kind:kind,body:body,createdAt:timestamp)
                                    guard try self.store.enqueue(pending,in:db) else {throw self.failure("The forwarded message could not be queued.")}
                                    if try !self.holdScheduled(pending,options:request.options,in:db) {
                                        if let manifest {
                                            try EncryptedObjectStore.remember(manifest,in:db,available:true)
                                            try self.insertAttachment(db,id:id,peer:peer,mine:true,manifest:manifest,caption:caption,timestamp:timestamp,reply:nil,sticker:row.source.isSticker)
                                        } else {try self.insertText(db,id:id,peer:peer,mine:true,text:row.source.text,timestamp:timestamp,reply:nil,preview:body["preview"] as? [String:Any],contactAccount:card,location:point)}
                                        try self.saveForward(db,id:id,forward:body["forward"] as? [String:Any])
                                    }
                                }
                            }
                            try db.execute("DELETE FROM forward_jobs WHERE id=?",values:[.text(jobId)])
                        }
                        completion(true)
                        do {try self.flush()} catch {self.report(error)}
                    }
                } catch let damage as DamagedForwardCache {
                    for (_, rows) in prepared { MessageActionsStore.shared.discard(rows) }
                    self.recoverForwardCache(jobId, damage: damage, recipients: recipients, cancellation: cancellation, completion: completion)
                } catch {for (_,rows) in prepared {MessageActionsStore.shared.discard(rows)};completion(false)}
            }
        }
    }

    private func recoverForwardCache(_ jobID: String, damage: DamagedForwardCache, recipients: [String],
                                     cancellation: ForwardCancellation, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let source = damage.source
            try AppDatabase.shared.transaction(notifyChanged: true) { db in
                try cancellation.check()
                let eligible = try db.query("""
                    SELECT 1 FROM forward_jobs WHERE id=? AND state='preparing' AND error_code IS NULL
                    """, values: [.text(jobID)]) { _ in true }
                guard !eligible.isEmpty else { throw CancellationError() }
                try ForwardTaskQueue.requireRecipients(db, recipients)
                try MessageActionsStore.shared.requireCurrent(db, source)
                guard try EncryptedObjectLifecycle.queueCacheReplacement(db, peer: source.peer, messageID: source.id,
                        now: self.now, previewOnly: damage.previewOnly),
                      try AttachmentQueue.setDownloadRequested(db, peer: source.peer, id: source.id, requested: true,
                        now: self.now, previewOnly: damage.previewOnly)
                else { throw self.failure("The forwarding cache could not be restored.") }
                try db.execute("UPDATE forward_jobs SET state='waiting',error_code='cache_repair_attempted' WHERE id=?",
                    values: [.text(jobID)])
            }
            NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: source.id)
            self.storageMaintenance.wake()
            completion(true)
        }
    }

    func editMessage(to recipient: String, messageID: String, text: String, completion: @escaping @Sendable (Bool) -> Void) {
        changeMessage(to: recipient, messageID: messageID, text: text, completion: completion)
    }
    func revokeMessage(to recipient: String, messageID: String, completion: @escaping @Sendable (Bool) -> Void) {
        changeMessage(to: recipient, messageID: messageID, text: nil, completion: completion)
    }
    func revokeSentMessages(to recipient: String, ids: Set<String>, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard (1...100).contains(ids.count) else { throw self.failure("Choose up to 100 messages.") }
            try AppDatabase.shared.transaction(notifyChanged: true) { db in
                try self.requireAccepted(recipient, in: db)
                let timestamp = self.now
                for id in ids {
                    let eligible = try db.query("""
                        SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1
                        AND isRevoked=0 AND status BETWEEN 1 AND 3 AND ext<>'live_location'
                        AND NOT EXISTS(SELECT 1 FROM message_expiration
                            WHERE message_id=messages.messageId AND expires_at<=?)
                        """, values: [.text(id), .text(recipient), .integer(timestamp)]) { _ in true }
                    guard !eligible.isEmpty else { throw self.failure("Some selected messages can no longer be revoked.") }
                    try self.enqueueMessageChange(db, recipient: recipient, messageID: id, text: nil, caption: false)
                }
            }
            completion(true)
            do { try self.flush() } catch { self.report(error) }
        }
    }
    func setMessagePinned(peer:String,messageID:String,pinned:Bool,notify:Bool=false,completion:@escaping @Sendable(Bool)->Void){perform(onFailure:{completion(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            try self.requireAccepted(peer,in:db)
            let body=try MessagePinsStore.body(db,peer:peer,local:KeychainManager.shared.myUuid,target:messageID,pinned:pinned,notify:notify)
            if try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:peer,kind:"chat.pin",body:body,createdAt:self.now),in:db),
               try !MessagePinsStore.apply(db,peer:peer,local:KeychainManager.shared.myUuid,sender:KeychainManager.shared.myUuid,body:body){throw self.failure("This message cannot be pinned.")}
        }
        completion(true);do{try self.flush()}catch{self.report(error)}
    }}
    func pinnedMessages(peer:String,offset:Int=0,completion:@escaping @Sendable(PinnedMessagePage?)->Void){perform(onFailure:{completion(nil)}){
        let page=try AppDatabase.shared.transaction{try MessagePinsStore.list($0,peer:peer,local:KeychainManager.shared.myUuid,offset:offset)}
        completion(page)
    }}
    private func changeMessage(to recipient: String, messageID: String, text: String?, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            if let text, text.utf8.count > 48 * 1024 { throw self.failure("Message text is too large") }
            let captionChanged = try AppDatabase.shared.transaction(notifyChanged: true) { transaction in
                try self.requireAccepted(recipient, in: transaction)
                guard let kind = try transaction.query("SELECT ext FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1 AND isRevoked=0",
                    values: [.text(messageID), .text(recipient)], map: { String(cString: sqlite3_column_text($0, 0)) }).first
                else { throw self.failure("This message cannot be changed") }
                let caption = try text != nil && kind == "attachment" && !transaction.query("""
                    SELECT 1 FROM message_attachments WHERE message_id=?1
                    AND NOT EXISTS(SELECT 1 FROM message_stickers WHERE message_id=?1)
                    """, values: [.text(messageID)], map: { _ in true }).isEmpty
                if let text, !caption, kind != "txt" || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    throw self.failure("This message cannot be edited")
                }
                if text != nil, try MessageExpirationStore.deadline(transaction, id: messageID) != nil {
                    throw self.failure("Messages set to auto-delete cannot be edited")
                }
                try self.enqueueMessageChange(transaction, recipient: recipient, messageID: messageID, text: text, caption: caption)
                return caption
            }
            if captionChanged { NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: messageID) }
            completion(true)
            do { try self.flush() } catch { self.report(error) }
        }
    }

    private func enqueueMessageChange(_ db: AppDatabase.Transaction, recipient: String, messageID: String, text: String?, caption: Bool) throws {
        let author = KeychainManager.shared.myUuid
        let previous = try savedChange(db, author: author, target: messageID)
        let revision = (previous?["revision"] as? Int64 ?? 0) + 1
        guard revision <= Int64(UInt32.max) else { throw failure("The message revision limit was reached") }
        var body: [String: Any] = ["message_id": messageID, "revision": revision]
        if let text { body["text"] = text }
        var change: [String: Any] = ["revision": revision, "revoked": text == nil, "text": text.map { $0 as Any } ?? NSNull()]
        if caption { body["caption"] = true; change["caption"] = true }
        guard try store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient,
            kind: text == nil ? "chat.revoke" : "chat.edit", body: body, createdAt: now), in: db)
        else { throw failure("The message change could not be queued.") }
        try saveChange(db, peer: recipient, author: author, mine: true, target: messageID, change: change)
    }

    func requestContact(_ recipient:String,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        guard recipient != KeychainManager.shared.myUuid else {throw self.failure("You cannot add your own identity")}
        _=try self.store.publicBundle()
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            let result=try ContactStateStore.apply(db,peer:recipient,action:["kind":"request","id":UUID().uuidString.lowercased()])
            guard let state=result["state"] as? [String:Any],let id=state["outbound_id"] as? String,let revision=state["outbound_revision"] else {throw SecurityCore.Failure.invalidResponse}
            try db.execute("DELETE FROM object_transfers WHERE direction=0 AND event_id IN (SELECT event_id FROM protocol_pending_events WHERE recipient=? AND kind='contact.request')",values:[.text(recipient)])
            try db.execute("DELETE FROM protocol_pending_events WHERE recipient=? AND kind='contact.request'",values:[.text(recipient)])
            var body=try UserProfileStore.body(db,selfID:KeychainManager.shared.myUuid,fallback:KeychainManager.shared.myNickname ?? "Unknown");body["request_id"]=id;body["request_revision"]=revision
            guard try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:recipient,kind:"contact.request",body:body,createdAt:self.now),in:db) else {throw self.failure("Contact request could not be queued")}
            try db.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,'Pending request','','') ON CONFLICT(uuid) DO NOTHING",values:[.text(recipient)])
        };completion?(true);do {try self.flush()}catch {self.report(error)}
    }}

    func acceptContact(_ recipient:String,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            guard let profile=try UserProfileStore.read(db,peer:recipient) else {throw self.failure("The contact request is unavailable")}
            let result=try ContactStateStore.apply(db,peer:recipient,action:["kind":"accept"])
            guard let state=result["state"] as? [String:Any],let id=state["inbound_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
            var body=try UserProfileStore.body(db,selfID:KeychainManager.shared.myUuid,fallback:KeychainManager.shared.myNickname ?? "Unknown");body["request_id"]=id
            guard try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:recipient,kind:"contact.accept",body:body,createdAt:self.now),in:db) else {throw self.failure("Contact acceptance could not be queued")}
            try db.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",values:[.text(recipient),.text(profile.nickname),.text(profile.avatarReference ?? "")])
        };completion?(true);do {try self.flush()}catch {self.report(error)};if self.ready {self.pull()}
    }}

    func clearMessages(_ recipient: String, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            try AppDatabase.shared.transaction(notifyChanged: true) { try self.clearHistory($0, peer: recipient) }
            NotificationCenter.default.post(name: CallHistory.didChange, object: nil)
            completion(true)
        }
    }

    func readReceiptsEnabled(completion:@escaping @Sendable(Bool?)->Void) {
        perform(onFailure:{completion(nil)}) {
            let enabled=try AppDatabase.shared.transaction {try self.readReceiptsEnabled(in:$0)}
            completion(enabled)
        }
    }
    func messageNotificationSettings(completion: @escaping @Sendable (MessageNotificationStore.Settings?) -> Void) {
        perform(onFailure: { completion(nil) }) {
            completion(try AppDatabase.shared.transaction { try MessageNotificationStore.settings($0) })
        }
    }
    func setMessageNotificationSettings(_ settings: MessageNotificationStore.Settings, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let previous = try AppDatabase.shared.transaction { db in
                let previous = try MessageNotificationStore.settings(db)
                try MessageNotificationStore.setSettings(settings, db)
                return previous
            }
            if !settings.enabled || (previous.showPreviews && !settings.showPreviews) {
                NotificationHelper.shared.clearMessageNotifications(onlyWhenActive: false)
            }
            completion(true)
        }
    }
    func automaticAttachmentDownloads(completion: @escaping @Sendable (Bool?) -> Void) {
        perform(onFailure: { completion(nil) }) {
            completion(try AppDatabase.shared.transaction { try AttachmentQueue.autoDownloadEnabled($0) })
        }
    }

    func attachmentCacheUsage(completion: @escaping @Sendable (AttachmentCacheUsage?) -> Void) {
        perform(onFailure: { completion(nil) }) {
            completion(try AppDatabase.shared.transaction { try EncryptedObjectLifecycle.cacheUsage($0) })
        }
    }

    func setAutomaticCacheLimit(_ bytes: Int64, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard EncryptedObjectLifecycle.automaticLimits.contains(bytes) else { completion(false); return }
            try AppDatabase.shared.execute("UPDATE privacy_preferences SET auto_download_limit_bytes=? WHERE id=1",
                                           values: [.integer(bytes)])
            self.attachments.signal()
            completion(true)
        }
    }

    func clearAttachmentCache(categories: Set<AttachmentCacheCategory>, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let count = try AppDatabase.shared.transaction {
                try EncryptedObjectLifecycle.queueCacheClear($0, categories: categories)
            }
            if count > 0 {
                self.storageMaintenance.wake()
                NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil)
                DispatchQueue.main.async { NotificationCenter.default.post(name: Self.attachmentCacheChanged, object: nil) }
            }
            completion(true)
        }
    }
    func requestAttachmentDownload(peer: String, messageID: String, requested: Bool = true,
                                   completion: (@Sendable (Bool) -> Void)? = nil) {
        perform(onFailure: { completion?(false) }) {
            let changed = try AppDatabase.shared.transaction { db in
                try AttachmentQueue.setDownloadRequested(db, peer: peer, id: messageID, requested: requested, now: self.now)
            }
            if !changed, requested { throw self.failure("This attachment is no longer waiting to download.") }
            if changed { NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: messageID) }
            self.attachments.signal()
            completion?(true)
        }
    }

    func setPreviewDownloadRequested(peer: String, messageID: String, reference: String,
                                requested: Bool, replacingCache: Bool = false,
                                completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard requested || !replacingCache else { throw SecurityCore.Failure.invalidResponse }
            try AppDatabase.shared.transaction { db in
                guard try WebPreviewStore.read(db, id: messageID)?.imageReference == reference else {
                    throw self.failure("The preview image is no longer waiting to download.")
                }
                if replacingCache {
                    guard try EncryptedObjectLifecycle.queueCacheReplacement(db, peer: peer, messageID: messageID,
                                                                            now: self.now, previewOnly: true) else {
                        throw self.failure("The preview image cannot be downloaded again.")
                    }
                }
                guard try AttachmentQueue.setDownloadRequested(db, peer: peer, id: messageID, requested: requested,
                                                                 now: self.now, previewOnly: true) else {
                    throw self.failure("The preview image is no longer waiting to download.")
                }
            }
            NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: messageID)
            if replacingCache { self.storageMaintenance.wake() }
            else { self.attachments.signal() }
            completion(true)
        }
    }

    func replaceAttachmentCache(peer: String, messageID: String, expectedReference: String? = nil,
                                completion: (@Sendable (Bool) -> Void)? = nil) {
        perform(onFailure: { completion?(false) }) {
            try AppDatabase.shared.transaction(notifyChanged: true) { db in
                if let expectedReference {
                    let matches = try db.query("SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND content=?",
                        values: [.text(messageID), .text(peer), .text(expectedReference)], map: { _ in true })
                    guard !matches.isEmpty else { throw self.failure("This attachment cannot be downloaded again.") }
                }
                guard try EncryptedObjectLifecycle.queueCacheReplacement(db, peer: peer, messageID: messageID, now: self.now),
                      try AttachmentQueue.setDownloadRequested(db, peer: peer, id: messageID, requested: true, now: self.now)
                else { throw self.failure("This attachment cannot be downloaded again.") }
            }
            NotificationCenter.default.post(name: Self.attachmentMetadataChanged, object: messageID)
            self.storageMaintenance.wake()
            completion?(true)
        }
    }

    func queueAttachmentCacheReplacement(peer: String, messageID: String, reference: String) async -> Bool {
        await withCheckedContinuation { continuation in
            replaceAttachmentCache(peer: peer, messageID: messageID, expectedReference: reference) {
                continuation.resume(returning: $0)
            }
        }
    }

    func setReadReceiptsEnabled(_ enabled:Bool,activity:ActivityPrivacy?=nil,autoDownload:Bool?=nil,completion:@escaping @Sendable(Bool)->Void) {
        perform(onFailure:{completion(false)}) {
            var dirty=[String]()
            try AppDatabase.shared.transaction {db in
                try db.execute("UPDATE privacy_preferences SET read_receipts=? WHERE id=1",values:[.integer(enabled ? 1 : 0)])
                if let autoDownload {
                    try db.execute("UPDATE privacy_preferences SET auto_download_attachments=? WHERE id=1",
                                   values: [.integer(autoDownload ? 1 : 0)])
                }
                if !enabled {
                    try db.execute("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) SELECT r.peer,r.message_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id")
                    try db.execute("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') UPDATE protocol_receipts SET pending=1 WHERE event_id IN (SELECT d.event_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id JOIN protocol_receipts d ON d.peer=r.peer AND d.message_id=r.message_id AND d.state='delivered')")
                    try db.execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read')")
                    try db.execute("UPDATE protocol_outbox SET delivered=-1 WHERE delivered IN (0,-2) AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.event_id=protocol_outbox.event_id AND r.state='read')")
                    try db.execute("UPDATE protocol_receipts SET pending=0 WHERE state='read' AND pending=1")
                }
                if let activity {
                    try db.execute("UPDATE privacy_preferences SET share_online=?,share_typing=? WHERE id=1",values:[.integer(activity.online ? 1:0),.integer(activity.typing ? 1:0)])
                    dirty=try db.query("SELECT peer FROM activity_peers WHERE json_extract(state_json,'$.published.body.online') IS NOT NULL OR json_extract(state_json,'$.published.body.typing')=1 LIMIT 128",map:{self.text($0,0)})
                }
            }
            if let activity {
                self.activityLock.lock()
                self.activityDirty.formUnion(dirty)
                while self.activityDirty.count > 128, let first = self.activityDirty.first { self.activityDirty.remove(first) }
                self.activityOnline = activity.online
                self.activityTyping = activity.typing
                self.activityLock.unlock()
                self.activityLoaded = true
                NotificationCenter.default.post(name: Self.peerActivityChanged, object: nil)
            }
            if autoDownload != nil { self.attachments.signal() }
            completion(true)
            do {try self.flush()}catch {self.report(error)}
        }
    }
    func changeConversation(_ peer:String,action:String,until:Int64?=nil,folderID:String?=nil,completion:(@Sendable(Bool)->Void)?=nil){perform(onFailure:{completion?(false)}){
        let tokens: [String] = try AppDatabase.shared.transaction(notifyChanged: true) { db in
            try ConversationIndexStore.change(db, peer: peer, action: action, until: until, folderID: folderID)
            guard action == "mute", folderID == nil,
                  try ConversationIndexStore.muteUntil(db, peer: peer) > self.now else { return [] }
            return try db.query("SELECT token FROM notification_targets WHERE peer=?", values: [.text(peer)],
                                map: { self.text($0, 0) })
        }
        NotificationHelper.shared.dismissMessageNotifications(tokens: tokens)
        completion?(true)
    }}

    func setConversationArchived(_ peer: String, expected: Bool, archived: Bool, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let changed = try AppDatabase.shared.transaction(notifyChanged: true) {
                try ConversationIndexStore.setArchived($0, peer: peer, expected: expected, archived: archived)
            }
            completion(changed)
        }
    }

    // Nil means the selection cannot be deleted. False means local deletion only.
    func deletionCanRevoke(peer: String, ids: Set<String>) throws -> Bool? {
        guard (1...100).contains(ids.count) else { return nil }
        let targets = String(decoding: try JSONSerialization.data(withJSONObject: Array(ids)), as: UTF8.self)
        return try AppDatabase.shared.transaction { db in
            let result = try db.query("""
                SELECT CASE
                    WHEN COUNT(*)<>?1 OR MAX(m.ext='live_location' OR (m.isMine=1 AND m.status=0))=1 THEN 0
                    WHEN MIN(m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN 1 AND 3)=1
                        AND EXISTS(SELECT 1 FROM contact_states WHERE uuid=?3 AND accepted=1 AND blocked=0) THEN 2
                    ELSE 1 END
                FROM messages m JOIN json_each(?2) s ON s.value=m.messageId
                WHERE m.conversationUuid=?3 AND NOT EXISTS(SELECT 1 FROM message_expiration
                    WHERE message_id=m.messageId AND expires_at<=?4)
                """, values: [.integer(Int64(ids.count)), .text(targets), .text(peer), .integer(now)]) {
                    sqlite3_column_int($0, 0)
                }.first
            guard let result, result != 0 else { return nil }
            return result == 2
        }
    }

    func deleteLocalMessages(peer: String, ids: Set<String>, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard (1...100).contains(ids.count) else { throw self.failure("Choose up to 100 messages.") }
            let targets = String(decoding: try JSONSerialization.data(withJSONObject: Array(ids)), as: UTF8.self)
            let tokens = try AppDatabase.shared.transaction(notifyChanged: true) { db in
                let rows = try db.query("""
                    SELECT m.isMine, m.status, m.ext, n.token
                    FROM messages m JOIN json_each(?) s ON s.value=m.messageId
                    LEFT JOIN notification_targets n ON n.message_id=m.messageId
                    WHERE m.conversationUuid=?
                    """, values: [.text(targets), .text(peer)]) { row -> String? in
                    let pending = sqlite3_column_int(row, 0) != 0 && sqlite3_column_int(row, 1) == 0
                    guard !pending, self.text(row, 2) != "live_location" else {
                        throw self.failure("A pending send or live location cannot be deleted from history.")
                    }
                    return sqlite3_column_type(row, 3) == SQLITE_NULL ? nil : self.text(row, 3)
                }
                guard rows.count == ids.count else { throw self.failure("Some selected messages are no longer available.") }
                let values: [DatabaseValue] = [.text(peer), .text(targets)]
                try db.execute("UPDATE messages SET replyToContent=NULL WHERE conversationUuid=? AND replyToId IN (SELECT value FROM json_each(?))", values: values)
                try db.execute("DELETE FROM message_changes WHERE conversation_uuid=? AND message_id IN (SELECT value FROM json_each(?))", values: values)
                try db.execute("DELETE FROM messages WHERE conversationUuid=? AND messageId IN (SELECT value FROM json_each(?))", values: values)
                return rows.compactMap { $0 }
            }
            self.notifyRemovedMessages(ids, tokens: tokens)
            self.notifySendQueue()
            completion(true)
        }
    }
    func moveConversationPin(scope: String, peer: String, target: String, before: Bool, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let moved = try AppDatabase.shared.transaction(notifyChanged: true) {
                try ConversationIndexStore.movePin($0, scope: scope, peer: peer, target: target, before: before)
            }
            completion(moved)
        }
    }

    func saveConversationFolder(_ folder:ConversationFolder,peers:[String],completion:@escaping @Sendable(Bool)->Void){perform(onFailure:{completion(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in try ConversationIndexStore.save(db,folder:folder,peers:peers)};completion(true)
    }}
    func deleteConversationFolder(_ id:String,completion:@escaping @Sendable(Bool)->Void){perform(onFailure:{completion(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){try $0.execute("DELETE FROM conversation_folders WHERE id=?",values:[.text(id)])};completion(true)
    }}
    func moveConversationFolder(_ id: String, targetID: String, before: Bool, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            let moved = try AppDatabase.shared.transaction(notifyChanged: true) {
                try ConversationIndexStore.move($0, id: id, targetID: targetID, before: before)
            }
            completion(moved)
        }
    }
    func markAllRead(_ peer:String){perform {
        let tokens = try AppDatabase.shared.transaction(notifyChanged:true){db in
            let disclose=try self.readReceiptsEnabled(in:db)
            while true {
                let ids = try db.query("""
                    SELECT m.messageId FROM messages m
                    WHERE m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                      AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?2)
                      AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                          AND m.timestamp<=?2-r.duration_ms)
                    ORDER BY m.timestamp,m.messageId LIMIT 100
                    """, values: [.text(peer), .integer(self.now)], map: { self.text($0, 0) })
                if ids.isEmpty {break}
                for id in ids {
                    if !disclose {try db.execute("INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) VALUES(?,?)",values:[.text(peer),.text(id)])}
                    try self.receipt(to:peer,message:id,state:"read",in:db);try db.execute("UPDATE messages SET isRead=1 WHERE messageId=? AND conversationUuid=? AND isMine=0",values:[.text(id),.text(peer)])
                }
            }
            try db.execute("UPDATE conversation_preferences SET manual_unread=0 WHERE peer=?",values:[.text(peer)])
            return try self.readNotificationTokens(db, peer: peer)
        }
        NotificationHelper.shared.dismissMessageNotifications(tokens: tokens)
        try self.flush()
    }}

    func markRead(_ recipient: String, visibleIDs ids: Set<String>, completion: @escaping @Sendable (Bool) -> Void) {
        guard !ids.isEmpty, ids.count <= 100 else { completion(false); return }
        perform(onFailure: { completion(false) }) {
            let (readIDs, tokens) = try AppDatabase.shared.transaction { db in
                let disclose = try self.readReceiptsEnabled(in: db)
                var readIDs = Set<String>()
                let at = self.now
                for id in ids {
                    guard try !db.query("""
                        SELECT 1 FROM messages m
                        WHERE m.messageId=? AND m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?3)
                          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                              AND m.timestamp<=?3-r.duration_ms)
                        """, values: [.text(id), .text(recipient), .integer(at)], map: { _ in true }).isEmpty else { continue }
                    if !disclose {try db.execute("INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) VALUES(?,?)",values:[.text(recipient),.text(id)])}
                    try self.receipt(to:recipient,message:id,state:"read",in:db)
                    try db.execute("UPDATE messages SET isRead=1 WHERE messageId=? AND conversationUuid=? AND isMine=0 AND isRead=0",values:[.text(id),.text(recipient)])
                    readIDs.insert(id)
                }
                return (readIDs, try self.readNotificationTokens(db, peer: recipient))
            }
            NotificationHelper.shared.dismissMessageNotifications(tokens: tokens)
            if !readIDs.isEmpty {
                NotificationCenter.default.post(name: MsgDao.visibleMessagesReadNotification,
                                                object: VisibleMessagesRead(peer: recipient, messageIDs: readIDs))
            }
            completion(true)
            do { try self.flush() } catch { self.report(error) }
        }
    }

    private func readNotificationTokens(_ db: AppDatabase.Transaction, peer: String) throws -> [String] {
        try db.query("""
            SELECT n.token FROM notification_targets n
            JOIN messages m ON m.messageId=n.message_id AND m.conversationUuid=n.peer
            WHERE n.peer=? AND m.isMine=0 AND m.isRead=1
            """, values: [.text(peer)], map: { self.text($0, 0) })
    }

    func updateProfile(nickname: String,avatarURL: URL?=nil,avatarBytes:Data?=nil,removeAvatar: Bool=false,completion: @escaping @Sendable (Bool)->Void) {
        guard reserveImport() else {completion(false);return}
        imports.async {
            var imported: [String:Any]?
            do {
                if var bytes=avatarBytes {
                    defer {bytes.resetBytes(in:0..<bytes.count)}
                    guard bytes.count<=512*1024,let source=CGImageSourceCreateWithData(bytes as CFData,nil),CGImageSourceGetCount(source)==1,
                          (CGImageSourceGetType(source) as String?) == "public.jpeg",
                          let image=CGImageSourceCreateImageAtIndex(source,0,nil),image.width>0,image.width<=512,image.height>0,image.height<=512 else {throw self.failure("Profile image could not be prepared")}
                    imported=try EncryptedObjectStore.shared.importBytes(bytes,filename:"avatar.jpg",mime:"image/jpeg")
                } else if let url=avatarURL {
                    let access=url.startAccessingSecurityScopedResource()
                    defer {if access {url.stopAccessingSecurityScopedResource()}}
                    guard let thumbnail=MediaStorageManager.shared.generateImageThumbnail(fileUrl:url,maxDimension:512),
                          let tiff=thumbnail.tiffRepresentation,let bitmap=NSBitmapImageRep(data:tiff),
                          var jpeg=bitmap.representation(using:.jpeg,properties:[.compressionFactor:0.82]),jpeg.count<=512*1024 else {throw self.failure("Profile image could not be prepared")}
                    defer {jpeg.resetBytes(in:jpeg.startIndex..<jpeg.endIndex)}
                    imported=try EncryptedObjectStore.shared.importBytes(jpeg,filename:"avatar.jpg",mime:"image/jpeg")
                }
                let avatar=imported
                self.perform(onFailure:{
                    if let avatar {try? EncryptedObjectStore.shared.discardCopy(avatar)}
                    self.releaseImport();completion(false)
                }) {
                    let profile=try AppDatabase.shared.transaction(notifyChanged:true) {db in
                        let profile=try UserProfileStore.saveLocal(db,selfID:KeychainManager.shared.myUuid,nickname:nickname,avatar:avatar,replaceAvatar:removeAvatar || avatar != nil)
                        let body=try UserProfileStore.body(db,selfID:KeychainManager.shared.myUuid,fallback:nickname)
                        let peers=try db.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0",map:{self.text($0,0)})
                        for peer in peers {_ = try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:peer,kind:"contact.profile",body:body,createdAt:self.now),in:db)}
                        return profile
                    }
                    KeychainManager.shared.installProfile(profile)
                    self.releaseImport();completion(true);self.attachments.signal()
                    do {try self.flush()} catch {self.report(error)}
                }
            } catch {
                if let imported {try? EncryptedObjectStore.shared.discardCopy(imported)}
                self.releaseImport();completion(false);self.report(error)
            }
        }
    }

    private func outgoingProfile() throws -> [String:Any] {
        try AppDatabase.shared.transaction {try UserProfileStore.body($0,selfID:KeychainManager.shared.myUuid,fallback:KeychainManager.shared.legacyNickname ?? "Unknown")}
    }

    func broadcastProfile() { perform {
        let peers = try AppDatabase.shared.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0", map: { self.text($0,0) })
        for peer in peers {
            try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: peer, kind: "contact.profile", body: try self.outgoingProfile(), createdAt: self.now))
        }
        try self.flush()
    } }

    func declineContact(_ recipient:String,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            let result=try ContactStateStore.apply(db,peer:recipient,action:["kind":"decline"])
            guard let state=result["state"] as? [String:Any],let id=state["inbound_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
            guard try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:recipient,kind:"contact.decline",body:["request_id":id],createdAt:self.now),in:db) else {throw self.failure("Contact decision could not be queued")}
        };completion?(true);do {try self.flush()}catch {self.report(error)}
    }}
    func withdrawContact(_ recipient:String,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            let result=try ContactStateStore.apply(db,peer:recipient,action:["kind":"withdraw"])
            guard let state=result["state"] as? [String:Any],let id=state["outbound_id"] as? String,let revision=state["outbound_revision"] else {throw SecurityCore.Failure.invalidResponse}
            try self.cancelContactQueue(db,peer:recipient)
            guard try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:recipient,kind:"contact.withdraw",body:["request_id":id,"request_revision":revision],createdAt:self.now),in:db) else {throw self.failure("Request withdrawal could not be queued")}
        };completion?(true);do {try self.flush()}catch {self.report(error)}
    }}
    func setContactBlocked(_ recipient:String,value:Bool,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in _=try ContactStateStore.apply(db,peer:recipient,action:["kind":value ? "block":"unblock"])}
        self.requested.removeValue(forKey:recipient);self.unavailable.removeValue(forKey:recipient)
        if value {self.endUnavailableContact(recipient)}
        self.attachments.signal();completion?(true)
    }}
    func deleteContact(_ recipient:String,completion:(@Sendable(Bool)->Void)?=nil){performContact(onFailure:{completion?(false)}){
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            let result=try ContactStateStore.apply(db,peer:recipient,action:["kind":"delete"])
            guard let old=result["previous"] as? [String:Any] else {throw SecurityCore.Failure.invalidResponse}
            try self.cancelContactQueue(db,peer:recipient)
            var kind="";var body:[String:Any]?
            if old["accepted"] as? Bool == true {kind="contact.delete";body=["relationship_ids":old["accepted_ids"] ?? []]}
            else if old["outgoing"] as? Bool == true,let id=old["outbound_id"] as? String,let revision=old["outbound_revision"] {kind="contact.withdraw";body=["request_id":id,"request_revision":revision]}
            else if old["incoming"] as? Bool == true,let id=old["inbound_id"] as? String {kind="contact.decline";body=["request_id":id]}
            if let body,try db.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(recipient)],map:{_ in true}).isEmpty {guard try self.store.enqueue(.init(eventID:UUID().uuidString.lowercased(),recipient:recipient,kind:kind,body:body,createdAt:self.now),in:db) else {throw self.failure("Contact change could not be queued")}}
            try db.execute("DELETE FROM user_profiles WHERE peer=?",values:[.text(recipient)]);try db.execute("DELETE FROM conversations WHERE uuid=?",values:[.text(recipient)]);try self.clearHistory(db,peer:recipient)
        }
        NotificationCenter.default.post(name: CallHistory.didChange, object: nil)
        self.requested.removeValue(forKey:recipient);self.unavailable.removeValue(forKey:recipient);self.endUnavailableContact(recipient);completion?(true);do {try self.flush()}catch {self.report(error)}
    }}
    private func endUnavailableContact(_ peer:String){claimedBundles.removeValue(forKey:peer);removePeerActivity(peer);DispatchQueue.main.async {if CallManager.shared.isCurrentPeer(peer){CallManager.shared.handleRemoteEnd()}}}

    private func cancelContactQueue(_ db:AppDatabase.Transaction,peer:String)throws{
        try db.execute("DELETE FROM protocol_pending_events WHERE recipient=?",values:[.text(peer)]);try db.execute("DELETE FROM protocol_outbox WHERE recipient=?",values:[.text(peer)])
        try db.execute("DELETE FROM protocol_key_requests WHERE recipient=?",values:[.text(peer)]);try db.execute("DELETE FROM object_transfers WHERE owner_peer=?",values:[.text(peer)])
        try db.execute("UPDATE protocol_receipts SET pending=0 WHERE peer=?",values:[.text(peer)])
    }

    private func apply(_ event: SignalProtocolStore.Incoming, transaction: AppDatabase.Transaction, onPinNotify: @escaping (String) -> Void) throws {
        if try !transaction.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(event.sender)],map:{ _ in true }).isEmpty { return }
        let arrival = now
        let deadline = (event.body["expire_after_ms"] as? NSNumber).map { min(arrival, event.createdAt) + $0.int64Value }
        if let deadline {
            try transaction.execute("""
                DELETE FROM message_changes WHERE author=? AND conversation_uuid=? AND message_id=?
                  AND (?<=? OR json_extract(state_json,'$.revoked')=0)
                """, values: [.text(event.sender), .text(event.sender), .text(event.eventID), .integer(deadline), .integer(arrival)])
            guard deadline > arrival else { return }
        }
        if try MessageExpirationStore.discardIncoming(transaction, peer: event.sender, kind: event.kind, created: event.createdAt, now: arrival) {
            try transaction.execute("DELETE FROM message_changes WHERE author=? AND conversation_uuid=? AND message_id=?",
                                    values: [.text(event.sender), .text(event.sender), .text(event.eventID)])
            try receipt(to: event.sender, message: event.eventID, state: "delivered", in: transaction)
            return
        }
        switch event.kind {
        case "call.offer", "call.answer", "call.ice", "call.challenge", "call.confirm", "call.reject", "call.end", "call.busy":
            break // Dispatch only after Signal and database state commit.

        case "contact.request":
            let requested=try ContactStateStore.apply(transaction,peer:event.sender,action:["kind":"receive_request","id":event.body["request_id"] ?? NSNull(),"revision":event.body["request_revision"] ?? NSNull()])
            guard requested["applied"] as? Bool == true else {return}
            let profile=try UserProfileStore.apply(transaction,peer:event.sender,body:event.body,event:event.eventID,created:event.createdAt)
            try transaction.execute("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES(?,?,?,'',?) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,avatarPath=excluded.avatarPath,timestamp=excluded.timestamp",values:[.text(event.sender),.text(profile.nickname),.text(profile.avatarReference ?? ""),.integer(event.createdAt)])
        case "contact.accept":
            let accepted=try ContactStateStore.apply(transaction,peer:event.sender,action:["kind":"receive_accept","id":event.body["request_id"] ?? NSNull()])
            guard accepted["applied"] as? Bool == true else {return}
            let profile=try UserProfileStore.apply(transaction,peer:event.sender,body:event.body,event:event.eventID,created:event.createdAt)
            try transaction.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",values:[.text(event.sender),.text(profile.nickname),.text(profile.avatarReference ?? "")])
        case "contact.decline":
            _=try ContactStateStore.apply(transaction,peer:event.sender,action:["kind":"receive_decline","id":event.body["request_id"] ?? NSNull()])
        case "contact.withdraw":
            _=try ContactStateStore.apply(transaction,peer:event.sender,action:["kind":"receive_withdraw","id":event.body["request_id"] ?? NSNull(),"revision":event.body["request_revision"] ?? NSNull()])
        case "contact.profile":
            guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",values:[.text(event.sender)],map:{_ in true}).isEmpty else {return}
            let profile=try UserProfileStore.apply(transaction,peer:event.sender,body:event.body,event:event.eventID,created:event.createdAt)
            try transaction.execute("UPDATE conversations SET alias=?,avatarPath=? WHERE uuid=?",values:[.text(profile.nickname),.text(profile.avatarReference ?? ""),.text(event.sender)])
        case "contact.delete":
            _=try ContactStateStore.apply(transaction,peer:event.sender,action:["kind":"receive_delete","ids":event.body["relationship_ids"] ?? []])
        case "chat.text", "chat.contact", "chat.location", "chat.location.start":
            let isContact = event.kind == "chat.contact"
            let isLocation = event.kind == "chat.location"
            let isLive = event.kind == "chat.location.start"
            let account = event.body["account"] as? String
            let location = isLocation ? LocationPoint(body: event.body) : nil
            let content = isLocation || isLive ? (event.body["label"] as? String ?? (isLive ? "Live location" : "Location")) :
                (event.body[isContact ? "name" : "text"] as? String)
            guard !isContact || account != nil, !isLocation || location != nil,
                  let value = content,
                  !value.isEmpty, value.utf8.count <= 48 * 1024 else { throw failure("Invalid message event") }
            if try isBlocked(event.sender, transaction) { return }
            let known = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", values: [.text(event.sender)], map: { _ in true })
            guard !known.isEmpty else { return }
            let reply = event.body["reply"] as? [String: Any]
            if let reply {
                guard let id = reply["event_id"] as? String, UUID(uuidString: id) != nil,
                      let author = reply["author"] as? String, [event.sender, KeychainManager.shared.myUuid].contains(author),
                      let preview = reply["preview"] as? String, preview.utf8.count <= 4096 else { throw failure("Invalid reply metadata") }
            }
            if isLive {
                guard try LiveLocationStore.apply(transaction, peer: event.sender, mine: false, kind: event.kind,
                    eventID: event.eventID, body: event.body, createdAt: event.createdAt, now: now) else { return }
            }
            try insertText(transaction, id: event.eventID, peer: event.sender, mine: false, text: value,
                           timestamp: min(event.createdAt, arrival), reply: reply, preview: event.body["preview"] as? [String:Any],
                           contactAccount: isContact ? account : nil, location: location, live: isLive, allowEdit: deadline == nil)
            try saveForward(transaction,id:event.eventID,forward:event.body["forward"] as? [String:Any])
            try receipt(to: event.sender, message: event.eventID, state: "delivered", in: transaction)
        case "chat.location.update", "chat.location.end":
            if try isBlocked(event.sender, transaction) { return }
            guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",
                values: [.text(event.sender)], map: { _ in true }).isEmpty else { return }
            _ = try LiveLocationStore.apply(transaction, peer: event.sender, mine: false, kind: event.kind,
                eventID: event.eventID, body: event.body, createdAt: event.createdAt, now: now)
        case "chat.attachment":
            guard let candidate = event.body["object"] as? [String: Any], let caption = event.body["caption"] as? String,
                  caption.utf8.count <= 48 * 1024 else { throw failure("Invalid attachment event") }
            if try isBlocked(event.sender, transaction) { return }
            guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", values: [.text(event.sender)], map: { _ in true }).isEmpty else { return }
            let reply = event.body["reply"] as? [String: Any]
            if let reply {
                guard let id = reply["event_id"] as? String, UUID(uuidString: id) != nil,
                      let author = reply["author"] as? String, [event.sender, KeychainManager.shared.myUuid].contains(author),
                      let preview = reply["preview"] as? String, preview.utf8.count <= 4096 else { throw failure("Invalid reply metadata") }
            }
            let checked = try SecurityCore.call(["operation": "validate_object_manifest", "manifest": candidate])
            guard let manifest = checked["manifest"] as? [String: Any] else { throw failure("Invalid attachment manifest") }
            try insertAttachment(transaction, id: event.eventID, peer: event.sender, mine: false, manifest: manifest, caption: caption, timestamp: min(event.createdAt, arrival), reply: reply,sticker:event.body["sticker"] as? Bool == true,album:event.body["album"] as? [String:Any],allowEdit:deadline==nil)
            try saveForward(transaction,id:event.eventID,forward:event.body["forward"] as? [String:Any])
            try receipt(to: event.sender, message: event.eventID, state: "delivered", in: transaction)
        case "chat.pin":
            if try isBlocked(event.sender,transaction){return}
            guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",values:[.text(event.sender)],map:{_ in true}).isEmpty else{return}
            try MessagePinsStore.apply(transaction,peer:event.sender,local:KeychainManager.shared.myUuid,sender:event.sender,body:event.body,onNotify:onPinNotify)
        case "chat.edit", "chat.revoke":
            if try isBlocked(event.sender, transaction) { return }
            guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", values: [.text(event.sender)], map: { _ in true }).isEmpty else { return }
            guard let target = event.body["message_id"] as? String, let revision = event.body["revision"] as? Int64 else { throw failure("Invalid message change") }
            let editing = event.kind == "chat.edit"
            if editing, try MessageExpirationStore.deadline(transaction, id: target) != nil { return }
            var change: [String: Any] = ["revision": revision, "revoked": !editing, "text": editing ? event.body["text"]! : NSNull()]
            if event.body["caption"] as? Bool == true { change["caption"] = true }
            try saveChange(transaction, peer: event.sender, author: event.sender, mine: false, target: target, change: change)
        default: throw failure("Unsupported encrypted event")
        }
        if let deadline {
            try MessageExpirationStore.record(transaction, id: event.eventID, deadline: deadline)
            nextExpiration = min(nextExpiration, deadline)
        }
    }

    private func applyDeliveryReceipt(_ event: SignalProtocolStore.Incoming, in db: AppDatabase.Transaction) throws -> MessageDeliveryChange? {
        guard let id = event.body["message_id"] as? String, let state = event.body["state"] as? String,
              state == "delivered" || state == "read" else { throw failure("Invalid receipt event") }
        let peer = event.sender
        let status: DatabaseValue = .integer(state == "read" ? 3 : 2)
        let target: [DatabaseValue] = [.text(id), .text(peer)]
        try acknowledgeActivity(db, peer: peer, id: id)
        guard try !db.query("SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1",
                            values: target, map: { _ in true }).isEmpty else { return nil }
        try db.execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0", values: target)
        try db.execute("DELETE FROM object_transfers WHERE event_id=? AND owner_peer=? AND direction=0", values: target)
        try db.execute("UPDATE messages SET status=?1 WHERE messageId=?2 AND conversationUuid=?3 AND isMine=1 AND isRevoked=0 AND status<?1",
                       values: [status] + target)
        let upgraded = try db.query("SELECT changes()", values: [], map: { sqlite3_column_int($0, 0) > 0 }).first == true
        return upgraded ? MessageDeliveryChange(peer: peer, messageID: id) : nil
    }

    private func makeReply(_ db: AppDatabase.Transaction, peer: String, original: ChatMessage?, selection: String?) throws -> [String: Any]? {
        guard let original else { return nil }
        guard original.conversationUuid == peer,
              let stored = try db.query("""
                SELECT isMine,content,ext FROM messages WHERE messageId=? AND conversationUuid=? AND isRevoked=0
                """, values: [.text(original.messageId), .text(peer)], map: { row in
                    (mine: sqlite3_column_int(row, 0) != 0, text: databaseText(row, 1), kind: databaseText(row, 2))
                }).first else { throw failure("The quoted message is unavailable") }
        let expiring = try MessageExpirationStore.deadline(db, id: original.messageId) != nil
        let preview: String
        if let selection {
            guard !expiring, stored.kind == "txt" else { throw failure("The selected quote is unavailable") }
            let value = try SecurityCore.call(["operation": "reply_quote_selection", "text": stored.text, "selection": selection])
            guard let excerpt = value["preview"] as? String else { throw SecurityCore.Failure.invalidResponse }
            preview = excerpt
        } else if expiring {
            preview = "Auto-deleting message"
        } else {
            let summary: String
            switch stored.kind {
            case "txt", "location", "live_location": summary = stored.text
            case "contact": summary = "Contact: " + stored.text
            default: summary = "[\(stored.kind)]"
            }
            let scalars = summary.unicodeScalars
            let end = scalars.index(scalars.startIndex, offsetBy: 512, limitedBy: scalars.endIndex) ?? scalars.endIndex
            preview = String(scalars[..<end])
        }
        return ["event_id": original.messageId, "author": stored.mine ? KeychainManager.shared.myUuid : peer, "preview": preview]
    }

    private func insertText(_ transaction: AppDatabase.Transaction, id: String, peer: String, mine: Bool, text: String, timestamp: Int64, reply: [String: Any]?,preview: [String:Any]?=nil,contactAccount: String?=nil,location:LocationPoint?=nil,live:Bool=false,allowEdit:Bool=true) throws {
        let contentType = live ? "live_location" : location != nil ? "location" : contactAccount == nil ? "txt" : "contact"
        try transaction.execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,?,?,?,?,?,?,0,0,0)", values: [.text(id),.text(peer),.integer(mine ? 1 : 0),.text(text),.text(contentType),.integer(timestamp),.integer(mine ? 1 : 0),optional(reply?["event_id"]),optional(reply?["author"]),optional(reply?["preview"])])
        if let contactAccount {
            try transaction.execute("INSERT INTO message_contact_cards(message_id,account) VALUES(?,?)", values: [.text(id),.text(contactAccount)])
        }
        if let location {
            try transaction.execute("INSERT INTO message_locations(message_id,latitude_e6,longitude_e6) VALUES(?,?,?)",
                                    values: [.text(id),.integer(Int64(location.latitudeE6)),.integer(Int64(location.longitudeE6))])
        }
        try applySavedChange(transaction, peer: peer, author: mine ? KeychainManager.shared.myUuid : peer, mine: mine, target: id, allowEdit: allowEdit)
        try WebPreviewStore.record(transaction,id:id,preview:preview,text:text,peer:peer,outgoing:mine,created:timestamp)
    }

    private func requireAccepted(_ recipient: String, in transaction: AppDatabase.Transaction) throws {
        guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(recipient)], map: { _ in true }).isEmpty else {
            throw failure("Accept the contact request before sending messages")
        }
    }
    private func requireAccepted(_ recipient: String) throws {
        try AppDatabase.shared.transaction { try requireAccepted(recipient, in: $0) }
    }
    private func insertAttachment(_ transaction: AppDatabase.Transaction, id: String, peer: String, mine: Bool, manifest: [String: Any], caption: String, timestamp: Int64, reply: [String: Any]?,sticker:Bool=false,album:[String:Any]?=nil,allowEdit:Bool=true) throws {
        try EncryptedObjectStore.remember(manifest, in: transaction)
        guard let objectID = manifest["object_id"] as? String else { throw failure("Invalid object identity") }
        try transaction.execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,'attachment',?,?,?,?,?,0,0,0)", values: [.text(id),.text(peer),.integer(mine ? 1 : 0),.text(EncryptedObjectStore.reference(objectID)),.integer(timestamp),.integer(mine ? 1 : 0),optional(reply?["event_id"]),optional(reply?["author"]),optional(reply?["preview"])])
        try transaction.execute("INSERT INTO message_attachments(message_id,object_id,caption) VALUES(?,?,?)", values: [.text(id), .text(objectID), .text(caption)])
        if let album, let albumId = album["id"] as? String,
           let position = album["index"] as? Int64, let count = album["count"] as? Int64 {
            let direction: DatabaseValue = .integer(mine ? 1 : 0)
            try transaction.execute("""
                INSERT OR IGNORE INTO message_album_items(message_id,peer,is_mine,album_id,position,item_count)
                SELECT ?,?,?,?,?,? WHERE NOT EXISTS (
                    SELECT 1 FROM message_album_items WHERE peer=? AND is_mine=? AND album_id=? AND item_count<>?
                )
                """, values: [.text(id), .text(peer), direction, .text(albumId), .integer(position), .integer(count),
                              .text(peer), direction, .text(albumId), .integer(count)])
        }
        let available = try transaction.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1", values: [.text(objectID)], map: { _ in true })
        if mine || available.isEmpty {
            try AttachmentQueue.record(transaction,event:id,objectID:objectID,outgoing:mine,peer:peer,created:timestamp)
        }
        if sticker {try transaction.execute("INSERT OR IGNORE INTO message_stickers(message_id) VALUES(?)",values:[.text(id)])}
        try applySavedChange(transaction, peer: peer, author: mine ? KeychainManager.shared.myUuid : peer, mine: mine, target: id, allowEdit: allowEdit)
    }

    private func saveForward(_ db: AppDatabase.Transaction,id: String,forward: [String:Any]?) throws {
        guard let forward,let date=forward["original_at"] as? Int64 else {return}
        try db.execute("INSERT INTO message_forwards(message_id,name,original_at) VALUES(?,?,?)",values:[.text(id),(forward["name"] as? String).map(DatabaseValue.text) ?? .null,.integer(date)])
    }

    private func clearHistory(_ transaction: AppDatabase.Transaction, peer: String) throws {
        try transaction.execute("DELETE FROM call_history WHERE peer=?",values:[.text(peer)])
        try transaction.execute("DELETE FROM live_location_states WHERE peer=?", values: [.text(peer)])
        try transaction.execute("DELETE FROM message_pin_clocks WHERE peer=?",values:[.text(peer)])
        try transaction.execute("DELETE FROM object_transfers WHERE event_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)", values: [.text(peer)])
        try transaction.execute("DELETE FROM message_attachments WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)", values: [.text(peer)])
        try transaction.execute("DELETE FROM message_changes WHERE conversation_uuid=?", values: [.text(peer)])
        try transaction.execute("DELETE FROM messages WHERE conversationUuid=?", values: [.text(peer)])
        try transaction.execute("DELETE FROM protocol_pending_events WHERE recipient=? AND kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.location.update','chat.location.end','chat.attachment','chat.edit','chat.revoke','chat.pin')", values: [.text(peer)])
    }

    func recordCallHistory(peer: String, callId: String, outgoing: Bool, video: Bool,
                           outcome: CallOutcome, duration: Int64, endedAt: Int64) {
        perform {
            try AppDatabase.shared.transaction(notifyChanged:true) { db in
                try CallHistory.insert(db,peer:peer,callId:callId,outgoing:outgoing,video:video,
                                       outcome:outcome,duration:duration,endedAt:endedAt)
            }
            NotificationCenter.default.post(name:CallHistory.didChange,object:nil)
        }
    }

    func removeCallHistory(_ entry: CallHistoryEntry?, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            try AppDatabase.shared.transaction { try CallHistory.remove($0, entry: entry) }
            NotificationCenter.default.post(name: CallHistory.didChange, object: nil)
            completion(true)
        }
    }

    private func savedChange(_ transaction: AppDatabase.Transaction, author: String, target: String) throws -> [String: Any]? {
        let value = try transaction.query("SELECT state_json FROM message_changes WHERE author=? AND message_id=?", values: [.text(author), .text(target)]) { String(cString: sqlite3_column_text($0, 0)) }.first
        guard let value else { return nil }
        guard let state = try JSONSerialization.jsonObject(with: Data(value.utf8)) as? [String: Any] else { throw failure("Invalid saved message change") }
        return state
    }
    private func saveChange(_ transaction: AppDatabase.Transaction, peer: String, author: String, mine: Bool, target: String, change: [String: Any]) throws {
        let existing = try transaction.query("SELECT conversationUuid,isMine,ext FROM messages WHERE messageId=?", values: [.text(target)]) {
            (peer: String(cString: sqlite3_column_text($0, 0)), mine: sqlite3_column_int($0, 1) != 0, ext: String(cString: sqlite3_column_text($0, 2)))
        }.first
        let expectedKind = change["caption"] as? Bool == true ? "attachment" : "txt"
        if let existing, existing.peer != peer || existing.mine != mine || (change["revoked"] as? Bool == false && existing.ext != expectedKind) { return }
        if existing == nil, !mine, try !transaction.query("SELECT 1 FROM protocol_inbox WHERE sender=? AND event_id=?", values: [.text(author), .text(target)], map: { _ in true }).isEmpty { return }
        let previous = try savedChange(transaction, author: author, target: target)
        let reduced = try SecurityCore.call(["operation": "reduce_message_change", "previous": previous.map { $0 as Any } ?? NSNull(),
            "change": change, "incoming": !mine])
        if reduced["conflict"] as? Bool == true { return }
        guard let state = reduced["change"] as? [String: Any] else { throw failure("Invalid message change result") }
        if reduced["changed"] as? Bool == true {
            let encoded = String(decoding: try JSONSerialization.data(withJSONObject: state, options: .sortedKeys), as: UTF8.self)
            if existing == nil {
                let count = try transaction.query("SELECT COUNT(*) FROM message_changes WHERE pending=1 AND author=?", values: [.text(author)]) { sqlite3_column_int64($0, 0) }.first ?? 0
                let bytes = try transaction.query("SELECT COALESCE(SUM(stored_bytes+1024),0) FROM message_changes WHERE pending=1") { sqlite3_column_int64($0, 0) }.first ?? 0
                let previousBytes = try transaction.query("SELECT COALESCE((SELECT stored_bytes+1024 FROM message_changes WHERE author=? AND message_id=? AND pending=1),0)", values: [.text(author), .text(target)]) { sqlite3_column_int64($0, 0) }.first ?? 0
                guard previous != nil || count < 256, bytes - previousBytes + Int64(encoded.utf8.count) + 1024 <= 16 * 1024 * 1024 else {
                    if !mine { throw SignalProtocolStore.PendingChangeLimit() }
                    throw failure("Pending message changes exceed the local history budget")
                }
            }
            try transaction.execute("INSERT INTO message_changes(author,message_id,conversation_uuid,state_json,pending,stored_bytes,pending_since) VALUES(?,?,?,?,?,?,?) ON CONFLICT(author,message_id) DO UPDATE SET state_json=excluded.state_json,pending=excluded.pending,stored_bytes=excluded.stored_bytes", values: [.text(author), .text(target), .text(peer), .text(encoded), .integer(existing == nil ? 1 : 0), .integer(Int64(encoded.utf8.count)), .integer(now)])
        }
        try applySavedChange(transaction, peer: peer, author: author, mine: mine, target: target)
    }
    private func applySavedChange(_ transaction: AppDatabase.Transaction, peer: String, author: String, mine: Bool, target: String, allowEdit: Bool = true) throws {
        guard let state = try savedChange(transaction, author: author, target: target) else { return }
        guard let original = try transaction.query("""
            SELECT ext,isRevoked,EXISTS(SELECT 1 FROM message_stickers WHERE message_id=messages.messageId)
            FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=?
            """, values: [.text(target), .text(peer), .integer(mine ? 1 : 0)], map: {
                (kind: String(cString: sqlite3_column_text($0, 0)), revoked: sqlite3_column_int($0, 1) != 0, sticker: sqlite3_column_int($0, 2) != 0)
            }).first else { return }
        let caption = state["caption"] as? Bool == true
        if state["revoked"] as? Bool == false,
           !allowEdit || original.revoked || original.sticker || original.kind != (caption ? "attachment" : "txt") {
            try transaction.execute("DELETE FROM message_changes WHERE author=? AND message_id=?", values: [.text(author),.text(target)])
            return
        }
        try transaction.execute("UPDATE message_changes SET pending=0 WHERE author=? AND message_id=?", values: [.text(author), .text(target)])
        if state["revoked"] as? Bool == true {

            try transaction.execute("UPDATE messages SET content='',isRevoked=1,isEdited=0 WHERE messageId=? AND conversationUuid=? AND isMine=?", values: [.text(target), .text(peer), .integer(mine ? 1 : 0)])
            try transaction.execute("UPDATE messages SET replyToContent='[Message revoked]' WHERE conversationUuid=? AND replyToId=?", values: [.text(peer), .text(target)])
            try transaction.execute("DELETE FROM object_transfers WHERE event_id=?", values: [.text(target)])
            try transaction.execute("DELETE FROM message_attachments WHERE message_id=?", values: [.text(target)])
            try transaction.execute("DELETE FROM message_contact_cards WHERE message_id=?", values: [.text(target)])
            try transaction.execute("DELETE FROM message_locations WHERE message_id=?", values: [.text(target)])
            if mine {
                try transaction.execute("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?", values: [.text(target), .text(peer)])
            }
        } else {
            guard let text = state["text"] as? String else { throw failure("Invalid edited message") }
            if caption {
                try transaction.execute("UPDATE message_attachments SET caption=? WHERE message_id=?", values: [.text(text),.text(target)])
                try transaction.execute("UPDATE messages SET isEdited=1 WHERE messageId=?", values: [.text(target)])
            } else {
                try transaction.execute("UPDATE messages SET content=?,isEdited=1 WHERE messageId=? AND conversationUuid=? AND isMine=? AND ext='txt' AND isRevoked=0", values: [.text(text), .text(target), .text(peer), .integer(mine ? 1 : 0)])
            }
        }
    }

    private func isBlocked(_ uuid: String, _ transaction: AppDatabase.Transaction) throws -> Bool {
        try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND blocked=1", values: [.text(uuid)], map: { _ in true }).isEmpty
    }

    private let eligibleRead="(SELECT read_receipts FROM privacy_preferences WHERE id=1)=1 AND NOT EXISTS(SELECT 1 FROM read_receipt_suppression s WHERE s.peer=r.peer AND s.message_id=r.message_id)"
    private func readReceiptsEnabled(in db:AppDatabase.Transaction)throws->Bool {
        try !db.query("SELECT 1 FROM privacy_preferences WHERE id=1 AND read_receipts=1",map:{_ in true}).isEmpty
    }
    private func canSendRead(_ db:AppDatabase.Transaction,peer:String,id:String)throws->Bool {
        guard try readReceiptsEnabled(in:db) else {return false}
        return try db.query("SELECT 1 FROM read_receipt_suppression WHERE peer=? AND message_id=?",values:[.text(peer),.text(id)],map:{_ in true}).isEmpty
    }
    private func receipt(to recipient: String, message: String, state: String, in transaction: AppDatabase.Transaction) throws {
        guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",values:[.text(recipient)],map:{ _ in true }).isEmpty else { return }
        if state=="read",try !canSendRead(transaction,peer:recipient,id:message) {return}
        try transaction.execute("INSERT OR IGNORE INTO protocol_receipts(peer,message_id,state,event_id,created_at) VALUES(?,?,?,?,?)",values:[.text(recipient),.text(message),.text(state),.text(UUID().uuidString.lowercased()),.integer(now)])
    }
    private func rescheduleReceipt(peer: String,message: String,in transaction: AppDatabase.Transaction) throws {
        guard try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",values:[.text(peer)],map:{ _ in true }).isEmpty else { return }
        if let read=try transaction.query("SELECT isRead FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=0",values:[.text(message),.text(peer)],map:{ sqlite3_column_int($0,0) != 0 }).first {
            let disclose=try canSendRead(transaction,peer:peer,id:message)
            try receipt(to:peer,message:message,state:read && disclose ? "read" : "delivered",in:transaction)
        }
        guard let row=try transaction.query("SELECT event_id,pending,next_retry FROM protocol_receipts r WHERE peer=? AND message_id=? AND (state!='read' OR (\(eligibleRead))) ORDER BY CASE state WHEN 'read' THEN 0 ELSE 1 END LIMIT 1",values:[.text(peer),.text(message)],map:{ (self.text($0,0),sqlite3_column_int($0,1),sqlite3_column_int64($0,2)) }).first, row.1==0 else { return }
        let time=now
        if row.2>time, row.2-time<=30_000 { return }
        try transaction.execute("UPDATE protocol_receipts SET pending=1 WHERE event_id=?",values:[.text(row.0)])
    }
    private func prepareReceipts() throws {
        try AppDatabase.shared.transaction { transaction in
            try transaction.execute("UPDATE protocol_receipts SET pending=0 WHERE pending=1 AND state='delivered' AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.peer=protocol_receipts.peer AND r.message_id=protocol_receipts.message_id AND r.state='read' AND (\(eligibleRead)))")
            let jobs=try transaction.query("SELECT r.peer,r.message_id,r.state,r.event_id,r.created_at FROM protocol_receipts r JOIN contact_states c ON c.uuid=r.peer WHERE r.pending=1 AND c.accepted=1 AND c.blocked=0 AND (r.state!='read' OR (\(eligibleRead))) ORDER BY r.rowid LIMIT 64",map:{ (self.text($0,0),self.text($0,1),self.text($0,2),self.text($0,3),sqlite3_column_int64($0,4)) })
            for (peer,id,state,event,created) in jobs {
                if try !transaction.query("SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",values:[.text(event),.text(peer)],map:{ _ in true }).isEmpty {
                    try transaction.execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND (delivered>=0 OR delivered=-2)",values:[.text(event),.text(peer)])
                } else { try self.store.enqueue(.init(eventID:event,recipient:peer,kind:"chat.receipt",body:["message_id":id,"state":state],createdAt:created),in:transaction) }
                try transaction.execute("UPDATE protocol_receipts SET pending=0,next_retry=? WHERE event_id=?",values:[.integer(self.now+30_000),.text(event)])
            }
        }
    }

    func messageRetention(peer: String, completion: @escaping @Sendable (MessageRetentionSettings?) -> Void) {
        perform(onFailure: { completion(nil) }) {
            completion(try AppDatabase.shared.transaction { try MessageExpirationStore.settings($0, peer: peer) })
        }
    }

    func setMessageRetention(peer: String, settings: MessageRetentionSettings, completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            try AppDatabase.shared.transaction { db in
                try self.requireAccepted(peer, in: db)
                try MessageExpirationStore.setSettings(db, peer: peer, settings: settings)
            }
            self.nextRetentionSweep = 0
            completion(true)
            do { try self.flush() } catch { self.report(error) }
        }
    }

    @discardableResult
    private func stampExpiration(_ body: inout [String: Any], peer: String, in db: AppDatabase.Transaction) throws -> Int64 {
        if var reply = body["reply"] as? [String: Any], let id = reply["event_id"] as? String {
            guard try !db.query("SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=?",
                                values: [.text(id), .text(peer)], map: { _ in true }).isEmpty else {
                throw failure("The quoted message is unavailable")
            }
            if try MessageExpirationStore.deadline(db, id: id) != nil {
                reply["preview"] = "Auto-deleting message"
                body["reply"] = reply
            }
        }
        let duration = try MessageExpirationStore.duration(db, peer: peer)
        if duration > 0 {
            body["expire_after_ms"] = duration
            nextExpiration = 0
        }
        return duration
    }

    private func removeExpiredMessages() throws {
        let time = now
        let sweep = nextRetentionSweep <= time
        var marked = 0
        let result = try AppDatabase.shared.transaction { db in
            if sweep { marked = try MessageExpirationStore.markDueRetention(db, now: time) }
            return try MessageExpirationStore.removeDue(db, now: time)
        }
        if sweep { nextRetentionSweep = marked >= 64 ? time : time + 60_000 }
        nextExpiration = result.next
        guard !result.ids.isEmpty else { return }
        NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil)
        notifySendQueue()
        notifyRemovedMessages(Set(result.ids), tokens: result.tokens)
    }

    private func notifyRemovedMessages(_ ids: Set<String>, tokens: [String]) {
        NotificationHelper.shared.dismissMessageNotifications(tokens: tokens)
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: Self.messagesRemoved, object: ids)
        }
    }

    private func flush(recoverReceipts: Bool = false) throws {
        if min(nextExpiration, nextRetentionSweep) <= now { try removeExpiredMessages() }
        guard nextExpiration > now else { return }
        guard ready, NWClient.shared.isCurrent(epoch) else { return }
        if recoverReceipts { try recoverMissingDeliveryReceipts() }
        nextRelayRetryAt = try AppDatabase.shared.transaction { db in
            let time = now
            try LiveLocationStore.pruneOutgoing(db, now: time)
            try db.execute("DELETE FROM protocol_relay_deferrals WHERE retry_at<=? OR retry_at>?",
                           values: [.integer(time), .integer(time + 30_000)])
            return try db.query("SELECT MIN(retry_at) FROM protocol_relay_deferrals") {
                sqlite3_column_type($0, 0) == SQLITE_NULL ? Int64.max : sqlite3_column_int64($0, 0)
            }.first ?? Int64.max
        }
        if !inFlight.isEmpty {
            let active=try AppDatabase.shared.transaction{db in try inFlight.filter {id in try !db.query("SELECT 1 FROM protocol_outbox WHERE event_id=? AND delivered=0",values:[.text(id)],map:{_ in true}).isEmpty}}
            inFlight.formIntersection(active)
        }
        if shouldPrepareActivity(){try prepareActivity()}
        try releaseScheduledSends()
        try prepareReceipts()
        attachments.signal()
        let cached=try store.pendingOutgoing(leaseSession:attachments.leaseSession,minimumLease:attachments.minimumLease,limit:8)
        for event in cached where !inFlight.contains(event.eventID) {if try !attemptSend(event){return}}
        var slots=8-cached.count
        if slots<=0 {return}
        let waitingForKeys = Set(requested.keys).union(unavailable.filter { $0.value > Date() }.keys)
            .subtracting(claimedBundles.keys)
        for event in try store.pendingEvents(leaseSession:attachments.leaseSession,minimumLease:attachments.minimumLease,
                                             waitingForKeys:waitingForKeys){
            if slots<=0 {break}
            var claim = claimedBundles[event.recipient]
            if let expired = claim, expired.expiresAt <= now / 1000 {
                try store.finishKeyRequest(recipient: event.recipient, id: expired.request)
                claimedBundles.removeValue(forKey: event.recipient)
                claim = nil
            }
            if try store.hasSession(with:event.recipient) || claim != nil {
                let sealed=try store.prepare(eventID:event.eventID,recipient:event.recipient,kind:event.kind,body:event.body,createdAt:event.createdAt,bundle:claim?.bundle,selectedPrekey:claim?.selected)
                if let claim {try store.finishKeyRequest(recipient:event.recipient,id:claim.request);claimedBundles.removeValue(forKey:event.recipient)}
                if try !attemptSend(sealed){return};slots-=1
            }else if requested[event.recipient]==nil,(unavailable[event.recipient] ?? .distantPast)<Date(){
                let id=try store.keyRequestID(for:event.recipient)
                let bytes=try JSONSerialization.data(withJSONObject:["request_id":id,"uuid":event.recipient])
                if self.send(GhostPacket(type:GhostPacket.typeV2KeyClaim,targetMailboxId:KeychainManager.shared.myMailboxId,encryptedData:bytes)){requested[event.recipient]=(id,Date())}
            }
        }
    }

    private func keyResponse(_ packet: GhostPacket) throws {
        guard let bytes = packet.encryptedData, bytes.count <= 192 * 1024,
              let response = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let recipient = response["uuid"] as? String, let id = response["request_id"] as? String
        else { throw failure("Invalid key directory response") }
        guard requested[recipient]?.id == id else { return }
        requested.removeValue(forKey: recipient)
        if response["found"] as? Bool != true {
            if let proof=response["revocation"] as? String { try markPeerRevoked(recipient,proof:proof); try flush(); return }
            unavailable[recipient] = Date().addingTimeInterval(300)
            throw failure("The recipient has no currently available key bundle. Your message remains encrypted in the local vault.")
        }
        guard let bundle = response["bundle"] as? [String: Any] else { throw failure("Missing public key bundle") }
        let verified = try SecurityCore.call(["operation": "verify_bundle", "bundle": bundle, "expected_uuid": recipient])
        guard let body = verified["bundle"] as? [String: Any], let expiresAt = body["expires_at"] as? Int64
        else { throw failure("Missing verified prekey bundle expiry") }
        let selected = response["selected_prekey"] as? Int64
        guard selected == nil || body["directory_epoch"] as? String == directoryEpoch else {
            throw failure("One-time key is from an older directory epoch")
        }
        claimedBundles[recipient]=(bundle,selected,id,expiresAt)
        if claimedBundles.count>128,let first=claimedBundles.keys.first {claimedBundles.removeValue(forKey:first)}
        try flush()
    }

    private func markPeerRevoked(_ peer: String,proof: String) throws {
        guard proof.utf8.count==152 else { throw failure("Invalid revocation proof") }
        let checked=try SecurityCore.call(["operation":"verify_revocation","proof":proof])
        guard checked["uuid"] as? String==peer else { throw failure("Revocation does not match recipient") }
        let fresh=try AppDatabase.shared.transaction(notifyChanged:true) { transaction in
            let fresh=try transaction.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(peer)],map:{ _ in true }).isEmpty
            try transaction.execute("INSERT OR IGNORE INTO protocol_revocations(peer,proof) VALUES(?,?)",values:[.text(peer),.text(proof)])
            try transaction.execute("DELETE FROM message_sends WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)",values:[.text(peer)])
            try transaction.execute("DELETE FROM activity_watchers WHERE peer=?",values:[.text(peer)])
            try transaction.execute("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=?",values:[.text(peer)])
            try transaction.execute("UPDATE contact_states SET accepted=0,incoming=0,outgoing=0 WHERE uuid=?",values:[.text(peer)])
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?",values:[.text(peer)])
            try transaction.execute("UPDATE messages SET status=-1 WHERE conversationUuid=? AND isMine=1 AND status IN (0,1)",values:[.text(peer)])
            try transaction.execute("UPDATE protocol_outbox SET delivered=-1 WHERE recipient=? AND delivered IN (0,-2)",values:[.text(peer)])
            try transaction.execute("DELETE FROM protocol_pending_events WHERE recipient=?",values:[.text(peer)])
            try transaction.execute("DELETE FROM protocol_key_requests WHERE recipient=?",values:[.text(peer)])
            try transaction.execute("""
                DELETE FROM protocol_outbox WHERE recipient=?1 AND event_id IN
                    (SELECT event_id FROM protocol_receipts WHERE peer=?1)
                """,values:[.text(peer)])
            try transaction.execute("DELETE FROM protocol_receipts WHERE peer=?",values:[.text(peer)])
            try transaction.execute("DELETE FROM live_location_outgoing WHERE peer=?",values:[.text(peer)])
            try transaction.execute("DELETE FROM object_transfers WHERE owner_peer=?",values:[.text(peer)])
            return fresh
        }
        removePeerActivity(peer)
        claimedBundles.removeValue(forKey:peer);requested.removeValue(forKey:peer); unavailable.removeValue(forKey:peer); inFlight.removeAll()
        if fresh { ContactDirectoryStore.invalidate();DispatchQueue.main.async {
            if CallManager.shared.isCurrentPeer(peer) { CallManager.shared.handleRemoteEnd() }
            Task { @MainActor in NoticeCenter.shared.show("The recipient reset their identity. Pending delivery is unconfirmed; retries stopped.") }
        } }
    }

    private func retry() {
        guard ready, NWClient.shared.isCurrent(epoch) else { return }
        inFlight.removeAll()
        requested = requested.filter { Date().timeIntervalSince($0.value.sent) < 30 }
        do {
            try AppDatabase.shared.transaction { db in try SignalProtocolStore.pruneIncoming(db, now: now) }
            if Date() >= nextMaintenance {
                try AppDatabase.shared.transaction { db in try LiveLocationStore.pruneOrphans(db, now: now) }
                guard let directoryEpoch else { throw failure("Missing relay directory epoch") }
                let bundle = try store.publicBundle(directoryEpoch: directoryEpoch)
                nextMaintenance = Date().addingTimeInterval(3600)
                if publishedBody != bundle["body"] as? String {
                    let bytes = try JSONSerialization.data(withJSONObject: bundle)
                    if self.send(GhostPacket(type: GhostPacket.typeV2KeyPublish, targetMailboxId: KeychainManager.shared.myMailboxId, encryptedData: bytes)) {
                        publishedBody = bundle["body"] as? String
                    }
                }
            }
            try flush(recoverReceipts: true); pull()
        } catch { report(error) }
    }

    private func recoverMissingDeliveryReceipts() throws {
        let time = now
        try AppDatabase.shared.transaction { db in
            let due = try db.query("""
                SELECT r.message_id,m.conversationUuid,a.created_at,
                       r.retry_at>?1 AND COALESCE(b.delivered,0)=1 AND m.status=1 AND m.isMine=1 AND m.isRevoked=0
                       AND EXISTS(SELECT 1 FROM contact_states c WHERE c.uuid=m.conversationUuid AND c.accepted=1 AND c.blocked=0)
                       AND NOT EXISTS(SELECT 1 FROM protocol_revocations v WHERE v.peer=m.conversationUuid)
                       AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=r.message_id AND e.expires_at<=?2)
                       AND NOT EXISTS(SELECT 1 FROM message_send_objects ref WHERE ref.message_id=r.message_id
                           AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.object_id AND o.local_state=1))
                FROM message_delivery_retries r
                JOIN message_sends a ON a.message_id=r.message_id
                JOIN messages m ON m.messageId=r.message_id
                LEFT JOIN protocol_outbox b ON b.event_id=r.message_id AND b.recipient=m.conversationUuid
                WHERE r.retry_at>0 AND r.retry_at<=?2
                ORDER BY r.retry_at,r.message_id LIMIT 8
                """, values: [.integer(time - Self.deliveryRetryWindow), .integer(time)]) { row in
                    (message: String(cString: sqlite3_column_text(row, 0)), peer: String(cString: sqlite3_column_text(row, 1)),
                     created: sqlite3_column_int64(row, 2), eligible: sqlite3_column_int(row, 3) != 0)
                }
            for job in due {
                guard job.eligible else {
                    try db.execute("UPDATE message_delivery_retries SET retry_at=0 WHERE message_id=?", values: [.text(job.message)])
                    continue
                }
                let objects = try db.query("SELECT object_id FROM message_send_objects WHERE message_id=?", values: [.text(job.message)]) {
                    String(cString: sqlite3_column_text($0, 0))
                }
                for objectID in objects {
                    try AttachmentQueue.record(db, event: job.message, objectID: objectID, outgoing: true, peer: job.peer, created: job.created)
                }
                try db.execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND delivered=1",
                               values: [.text(job.message), .text(job.peer)])
            }
        }
    }
    private func pull() {
        guard let nonce = syncNonce ?? eventBytes(UUID().uuidString) else { return }
        if send(GhostPacket(type: GhostPacket.typeV2Sync, targetMailboxId: KeychainManager.shared.myUuid, nonceOrIv: nonce)) {
            syncNonce = nonce
        }
    }
    private func send(_ packet: GhostPacket) -> Bool { NWClient.shared.sendPacket(packet, epoch: epoch) }
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func eventID(_ data: Data?) -> String? {
        guard let data, data.count == 16, data[6] >> 4 == 4, data[8] >> 6 == 2 else { return nil }
        return data.withUnsafeBytes { bytes in
            let uuid = bytes.loadUnaligned(as: uuid_t.self)
            return UUID(uuid: uuid).uuidString.lowercased()
        }
    }
    private func eventBytes(_ id: String) -> Data? { guard var uuid = UUID(uuidString: id)?.uuid else { return nil }; return withUnsafeBytes(of: &uuid) { Data($0) } }
    private func optional(_ value: Any?) -> DatabaseValue { (value as? String).map(DatabaseValue.text) ?? .null }
    private func text(_ statement: OpaquePointer, _ column: Int32) -> String { sqlite3_column_text(statement,column).map { String(cString:$0) } ?? "" }
    private func failure(_ detail: String) -> MsgATDatabaseError { MsgATDatabaseError(operation: "Secure messaging", detail: detail) }
    private func performContact(onFailure:(@Sendable()->Void)?=nil,_ work:@escaping @Sendable()throws->Void){perform(onFailure:onFailure){try work();ContactDirectoryStore.invalidate()}}
    private func perform(onFailure: (@Sendable () -> Void)? = nil, _ work: @escaping @Sendable () throws -> Void) {
        queue.async { guard !self.stopped else { onFailure?(); return }; do { try work() } catch { onFailure?(); if !(error is CancellationError){self.report(error)} } }
    }
    private struct PeerActivity {let online:Bool?;let expires:TimeInterval;let typingExpires:TimeInterval}
    private let activityLock=NSLock()
    private var activityForeground=false
    private var activityOnline=false
    private var activityTyping=false
    private var activityLoaded=false
    private var activityCursor=0
    private var activityRevision:UInt64=0
    private var activityChat:String?
    private var activityTypingUntil:TimeInterval=0
    private var activityDirty=Set<String>()
    private var activityReceived=[String:PeerActivity]()
    private var activityOrder=[String]()
    private var activityTimer:DispatchSourceTimer?
    func peerActivity(_ peer: String) -> (status: PeerActivityStatus, expires: TimeInterval)? {
        activityLock.lock()
        let value = activityReceived[peer]
        let showOnline = activityOnline
        activityLock.unlock()
        guard let value else { return nil }
        let time = ProcessInfo.processInfo.systemUptime
        if value.typingExpires > time { return (.typing, value.typingExpires) }
        if showOnline, let online = value.online, value.expires > time {
            return (online ? .online : .offline, value.expires)
        }
        return nil
    }

    private func removePeerActivity(_ peer: String) {
        activityLock.lock()
        let removed = activityReceived.removeValue(forKey: peer)
        activityOrder.removeAll { $0 == peer }
        activityLock.unlock()
        if removed != nil { NotificationCenter.default.post(name: Self.peerActivityChanged, object: peer) }
    }
    func setForeground(_ value:Bool){activityLock.lock();guard activityForeground != value else {activityLock.unlock();return};activityForeground=value;activityRevision &+= 1;if !value {activityTypingUntil=0};let share=activityOnline || activityTyping;activityLock.unlock();if share {signalActivity()}}
    func setActiveChat(_ peer:String?,typing:Bool=false){
        activityLock.lock();let time=ProcessInfo.processInfo.systemUptime
        let changed=activityChat != peer || (activityTypingUntil>time) != typing
        let share=activityOnline || activityTyping
        if share,let old=activityChat,old != peer {activityDirty.insert(old)}
        activityRevision &+= 1;activityChat=peer;activityTypingUntil=typing && peer != nil ? time+8:0
        if activityDirty.count>128,let first=activityDirty.first {activityDirty.remove(first)}
        activityLock.unlock();if changed,share {signalActivity()}
    }
    func clearActiveChat(_ peer:String){activityLock.lock();guard activityChat==peer else {activityLock.unlock();return};activityRevision &+= 1;let share=activityOnline || activityTyping;if share {activityDirty.insert(peer)};while activityDirty.count>128,let first=activityDirty.first {activityDirty.remove(first)};activityChat=nil;activityTypingUntil=0;activityLock.unlock();if share {signalActivity()}}
    private var activityQueued=false
    private func signalActivity(){
        activityLock.lock();if activityQueued {activityLock.unlock();return};activityQueued=true;activityLock.unlock()
        queue.async {
            self.activityLock.lock();let revision=self.activityRevision;self.activityLock.unlock()
            defer {self.activityLock.lock();self.activityQueued=false;let changed=self.activityRevision != revision;self.activityLock.unlock();if changed,!self.stopped {self.signalActivity()}}
            guard !self.stopped else {return}
            do {try self.flush()}catch {NSLog("Activity update retained for retry (%@)",String(describing:type(of:error)))}
        }
    }
    func activityPrivacy(completion:@escaping @Sendable(ActivityPrivacy?)->Void){perform(onFailure:{completion(nil)}){
        try AppDatabase.shared.transaction {try self.loadActivityPrivacy($0)}
        completion(ActivityPrivacy(online:self.activityOnline,typing:self.activityTyping))
    }}
    private func loadActivityPrivacy(_ db:AppDatabase.Transaction)throws{
        guard let flags=try db.query("SELECT share_online,share_typing FROM privacy_preferences WHERE id=1",map:{(sqlite3_column_int($0,0) != 0,sqlite3_column_int($0,1) != 0)}).first else {throw SecurityCore.Failure.invalidResponse}
        activityLock.lock();activityOnline=flags.0;activityTyping=flags.1;activityLock.unlock();activityLoaded=true
    }
    private func activityState(_ db:AppDatabase.Transaction,peer:String)throws->[String:Any]{
        guard let value=try db.query("SELECT state_json FROM activity_peers WHERE peer=?",values:[.text(peer)],map:{self.text($0,0)}).first else {return [:]}
        guard let state=try JSONSerialization.jsonObject(with:Data(value.utf8)) as? [String:Any] else {throw SecurityCore.Failure.invalidResponse};return state
    }
    private func changeActivity(_ db:AppDatabase.Transaction,peer:String,state:[String:Any],action:[String:Any])throws->[String:Any]{
        let result=try SecurityCore.call(["operation":"activity_transition","state":state,"action":action])
        guard let next=result["state"] as? [String:Any] else {throw SecurityCore.Failure.invalidResponse}
        let text=String(decoding:try JSONSerialization.data(withJSONObject:next,options:[.sortedKeys]),as:UTF8.self)
        try db.execute("INSERT INTO activity_peers(peer,state_json) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET state_json=excluded.state_json",values:[.text(peer),.text(text)]);return result
    }
    private func deleteActivityReceipt(_ db:AppDatabase.Transaction,peer:String,id:String)throws{
        try db.execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered')",values:[.text(peer),.text(id)])
        try db.execute("DELETE FROM protocol_outbox WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered')",values:[.text(peer),.text(id)])
        try db.execute("DELETE FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered'",values:[.text(peer),.text(id)])
    }
    private func receiveActivity(_ event:SignalProtocolStore.Incoming,in db:AppDatabase.Transaction)throws->[String:Any]?{
        guard try !db.query("SELECT 1 FROM contact_states c WHERE c.uuid=? AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",values:[.text(event.sender)],map:{_ in true}).isEmpty else {return nil}
        let result=try changeActivity(db,peer:event.sender,state:activityState(db,peer:event.sender),action:["kind":"receive","event_id":event.eventID,"body":event.body,"created_at":event.createdAt,"now":now])
        if let state=result["state"] as? [String:Any],state["received_id"] as? String == event.eventID {try receipt(to:event.sender,message:event.eventID,state:"delivered",in:db)}
        if let replaced=result["replaced"] as? String {try deleteActivityReceipt(db,peer:event.sender,id:replaced)}
        guard let observation=result["observation"] as? [String:Any] else {return nil}
        if !activityLoaded {try loadActivityPrivacy(db)}
        if activityOnline,observation["request"] as? Bool == true {
            try db.execute("INSERT INTO activity_watchers(peer,until) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET until=excluded.until",values:[.text(event.sender),.integer(now+45_000)])
            try db.execute("DELETE FROM activity_watchers WHERE peer IN (SELECT peer FROM activity_watchers ORDER BY until DESC,peer LIMIT -1 OFFSET 128)")
        }
        return observation
    }
    private func activityNumber(_ value:[String:Any],_ key:String)->Int64 {(value[key] as? NSNumber)?.int64Value ?? 0}
    private func publishActivity(_ peer:String,value:[String:Any]){
        let time=ProcessInfo.processInfo.systemUptime
        let entry=PeerActivity(online:value["online"] as? Bool,expires:time+Double(activityNumber(value,"valid_for"))/1000,typingExpires:time+Double(activityNumber(value,"typing_for"))/1000)
        activityLock.lock();activityReceived[peer]=entry;activityOrder.removeAll{$0==peer};activityOrder.append(peer)
        let evictedPeer = activityOrder.count > 128 ? activityOrder.removeFirst() : nil
        if let evictedPeer { activityReceived.removeValue(forKey: evictedPeer) }
        activityLock.unlock()
        NotificationCenter.default.post(name: Self.peerActivityChanged, object: evictedPeer == nil ? peer : nil)
    }
    private func acknowledgeActivity(_ db:AppDatabase.Transaction,peer:String,id:String)throws{
        let state=try activityState(db,peer:peer)
        guard let event=state["inflight"] as? [String:Any],event["event_id"] as? String == id else {return}
        let result=try changeActivity(db,peer:peer,state:state,action:["kind":"acknowledge","event_id":id])
        if result["acknowledged"] as? String != nil {try db.execute("DELETE FROM protocol_outbox WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)])}
    }
    private func shouldPrepareActivity()->Bool {activityLock.lock();defer{activityLock.unlock()};return !activityLoaded || activityOnline || activityTyping || !activityDirty.isEmpty}
    private func prepareActivity()throws{try AppDatabase.shared.transaction {db in
        if !self.activityLoaded {try self.loadActivityPrivacy(db)}
        let time=self.now
        self.activityLock.lock();let foreground=self.activityForeground;let chat=self.activityChat;let typingUntil=self.activityTypingUntil;let dirty=Array(self.activityDirty);let revision=self.activityRevision;self.activityLock.unlock()
        try db.execute("DELETE FROM activity_watchers WHERE until<=? OR until>?",values:[.integer(time),.integer(time+45_000)])
        let watchers=self.activityOnline ? try db.query("SELECT w.peer FROM activity_watchers w LEFT JOIN activity_peers a ON a.peer=w.peer ORDER BY COALESCE(json_extract(a.state_json,'$.published.created_at'),0),w.peer LIMIT 128",map:{self.text($0,0)}) : []
        var seen=Set<String>()
        let rest=(dirty+watchers).filter{$0 != chat && seen.insert($0).inserted}
        let start=rest.isEmpty ? 0:self.activityCursor%rest.count
        var peers=[chat].compactMap{$0}
        for index in 0..<min(rest.count,31){peers.append(rest[(start+index)%rest.count])}
        if !rest.isEmpty {self.activityCursor=(start+31)%rest.count}
        for peer in peers {
            guard try !db.query("SELECT 1 FROM contact_states c WHERE c.uuid=? AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",values:[.text(peer)],map:{_ in true}).isEmpty else {self.activityLock.lock();if self.activityRevision==revision {self.activityDirty.remove(peer)};self.activityLock.unlock();continue}
            let online:Bool?=self.activityOnline ? foreground:nil
            let typing=foreground && self.activityTyping && chat==peer && typingUntil>ProcessInfo.processInfo.systemUptime
            let request=foreground && self.activityOnline && chat==peer
            var state=try self.activityState(db,peer:peer);var inflight=state["inflight"] as? [String:Any]
            if let event=inflight,let id=event["event_id"] as? String,let body=event["body"] as? [String:Any],
               try db.query("SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)],map:{_ in true}).isEmpty,
               self.activityNumber(body,"expires_at")<=time || body["online"] as? Bool != online || body["typing"] as? Bool != typing || body["request"] as? Bool != request {
                try db.execute("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)])
                guard let next=try self.changeActivity(db,peer:peer,state:state,action:["kind":"unprepare"])["state"] as? [String:Any] else {throw SecurityCore.Failure.invalidResponse};state=next;inflight=nil
            }
            if let event=inflight,let body=event["body"] as? [String:Any],let id=event["event_id"] as? String {
                let permitted=(self.activityOnline || (body["online"] as? Bool == nil && body["request"] as? Bool != true)) && (self.activityTyping || body["typing"] as? Bool != true)
                let retry=self.activityNumber(event,"retry_at")
                if permitted,(retry<=time || retry-time>30_000){try db.execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND delivered>=0",values:[.text(id),.text(peer)]);_ = try self.changeActivity(db,peer:peer,state:state,action:["kind":"retried","now":time])}
                continue
            }
            let result=try self.changeActivity(db,peer:peer,state:state,action:["kind":"publish","event_id":UUID().uuidString.lowercased(),"online":online.map{$0 as Any} ?? NSNull(),"typing":typing,"request":request,"now":time])
            if let event=result["send"] as? [String:Any],let id=event["event_id"] as? String,let body=event["body"] as? [String:Any] {_ = try self.store.enqueue(.init(eventID:id,recipient:peer,kind:"chat.activity",body:body,createdAt:self.activityNumber(event,"created_at")),in:db)}
            self.activityLock.lock();if self.activityRevision==revision {self.activityDirty.remove(peer)};self.activityLock.unlock()
        }
    }}

    private var nextScheduledSend=Int64.max
    private var scheduleDeadlineLoaded=false
    private var scheduleTimer:DispatchSourceTimer?
    private func holdScheduled(_ event:SignalProtocolStore.Pending,options:SendOptions,in db:AppDatabase.Transaction)throws->Bool{
        guard let due=options.sendAt else {return false}
        guard due>=0,due<=253402214399999 else {throw failure("Invalid scheduled time")}
        try db.execute("INSERT INTO scheduled_sends(event_id,recipient,send_at,created_at) VALUES(?,?,?,?)",values:[.text(event.eventID),.text(event.recipient),.integer(due),.integer(event.createdAt)])
        try db.execute("UPDATE protocol_pending_events SET held=1 WHERE event_id=?",values:[.text(event.eventID)])
        let manifests=[event.body["object"] as? [String:Any],(event.body["preview"] as? [String:Any])?["image"] as? [String:Any]].compactMap{$0}
        for manifest in manifests {
            try EncryptedObjectStore.remember(manifest,in:db,available:true)
            guard let id=manifest["object_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
            try db.execute("INSERT OR IGNORE INTO scheduled_objects(event_id,object_id) VALUES(?,?)",values:[.text(event.eventID),.text(id)])
        }
        scheduleDeadlineLoaded=false;return true
    }
    private func requireScheduledMedia(_ id: String, in db: AppDatabase.Transaction) throws {
        let missing = try db.query("""
            SELECT 1 FROM protocol_pending_events p,
                json_each(json_array(json_extract(p.body_json,'$.object.object_id'),
                                     json_extract(p.body_json,'$.preview.image.object_id'))) ref
            WHERE p.event_id=? AND ref.value IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)
            LIMIT 1
            """, values: [.text(id)], map: { _ in true })
        if !missing.isEmpty { throw failure("Attachment is unavailable") }
    }

    private func releaseScheduledSends()throws{
        if scheduleDeadlineLoaded,nextScheduledSend>now {return}
        let changed=try AppDatabase.shared.transaction {db in
            var changed=false
            let rows=try db.query("SELECT s.event_id,s.recipient,p.kind,p.body_json FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id WHERE s.state='waiting' AND s.send_at<=? ORDER BY s.send_at,s.created_at,s.event_id LIMIT 16",values:[.integer(self.now)],map:{(self.text($0,0),self.text($0,1),self.text($0,2),self.text($0,3))})
            for (id,peer,kind,encoded) in rows {
                try db.execute("SAVEPOINT release_scheduled")
                do {
                    try self.requireAccepted(peer,in:db)
                    guard try db.query("SELECT 1 FROM protocol_outbox WHERE event_id=?",values:[.text(id)],map:{_ in true}).isEmpty,
                          let body=try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any] else {throw SecurityCore.Failure.invalidResponse}
                    try self.requireScheduledMedia(id, in: db)
                    let created=self.now
                    try db.execute("UPDATE protocol_pending_events SET held=0,created_at=? WHERE event_id=?",values:[.integer(created),.text(id)])
                    if kind=="chat.text",let text=body["text"] as? String {try self.insertText(db,id:id,peer:peer,mine:true,text:text,timestamp:created,reply:body["reply"] as? [String:Any],preview:body["preview"] as? [String:Any])}
                    else if kind=="chat.contact",let name=body["name"] as? String,let account=body["account"] as? String {
                        try self.insertText(db,id:id,peer:peer,mine:true,text:name,timestamp:created,reply:body["reply"] as? [String:Any],contactAccount:account)
                    }
                    else if kind=="chat.location",let location=LocationPoint(body:body) {
                        try self.insertText(db,id:id,peer:peer,mine:true,text:body["label"] as? String ?? "Location",
                                            timestamp:created,reply:body["reply"] as? [String:Any],location:location)
                    }
                    else {
                        guard kind=="chat.attachment",let manifest=body["object"] as? [String:Any],let caption=body["caption"] as? String else {throw SecurityCore.Failure.invalidResponse}
                        try self.insertAttachment(db,id:id,peer:peer,mine:true,manifest:manifest,caption:caption,timestamp:created,reply:body["reply"] as? [String:Any],sticker:body["sticker"] as? Bool == true,album:body["album"] as? [String:Any])
                    }
                    try self.saveForward(db,id:id,forward:body["forward"] as? [String:Any])
                    if let duration = body["expire_after_ms"] as? NSNumber {
                        self.nextExpiration = min(self.nextExpiration, created + duration.int64Value)
                    }
                    try db.execute("DELETE FROM scheduled_sends WHERE event_id=?",values:[.text(id)])
                    try db.execute("RELEASE SAVEPOINT release_scheduled");changed=true
                } catch {
                    try db.execute("ROLLBACK TO SAVEPOINT release_scheduled");try db.execute("RELEASE SAVEPOINT release_scheduled")
                    if error is CancellationError {throw error}
                    try db.execute("UPDATE scheduled_sends SET state='failed' WHERE event_id=?",values:[.text(id)])
                    NSLog("Scheduled release retained (%@)",String(describing:type(of:error)))
                }
            }
            self.nextScheduledSend=try db.query("SELECT MIN(send_at) FROM scheduled_sends WHERE state='waiting'",map:{sqlite3_column_type($0,0)==SQLITE_NULL ? Int64.max:sqlite3_column_int64($0,0)}).first ?? Int64.max
            self.scheduleDeadlineLoaded=true;return changed
        }
        if changed {NotificationCenter.default.post(name:MsgDao.didChangeNotification,object:nil)}
        notifySendQueue()
    }
    private func attemptSend(_ event:SignalProtocolStore.Outgoing)throws->Bool{
        let changed = try AppDatabase.shared.transaction { db -> Int32? in
            let at = now
            let expired = try db.query("""
                SELECT 1 FROM message_expiration WHERE expires_at<=?1 AND message_id IN (
                    SELECT ?2 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=?2
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=?2 AND peer=?3
                )
                """, values: [.integer(at), .text(event.eventID), .text(event.recipient)], map: { _ in true })
            guard expired.isEmpty else { return nil }
            try db.execute("UPDATE message_sends SET first_attempt_at=?1 WHERE first_attempt_at IS NULL AND message_id IN (SELECT ?2 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=?2)",values:[.integer(at),.text(event.eventID)])
            return try db.query("SELECT changes()",map:{sqlite3_column_int($0,0)}).first ?? 0
        }
        guard let changed else { return true }
        if changed>0 {notifySendQueue()}
        guard send(GhostPacket(type:GhostPacket.typeV2Submit,targetMailboxId:event.recipient,nonceOrIv:eventBytes(event.eventID),encryptedData:event.wire)) else {return false}
        inFlight.insert(event.eventID);return true
    }
    func sendQueue(peer: String? = nil, offset: Int = 0, messageID: String? = nil, scheduled: Bool? = nil, limit: Int = 50, completion:@escaping @Sendable([SendQueueEntry]?)->Void){perform(onFailure:{completion(nil)}){
        guard offset >= 0, (1...51).contains(limit) else {throw self.failure("Invalid queue page")}
        let filter = scheduled.map { DatabaseValue.integer($0 ? 1 : 0) } ?? .null
        let rows=try AppDatabase.shared.transaction {db in try db.query("SELECT q.id,q.peer,q.kind,q.body,q.send_at,q.state,q.cancel,q.retry,c.alias,CASE WHEN q.state='failed' THEN CASE WHEN q.retry=0 THEN 'recipient' WHEN EXISTS(SELECT 1 FROM json_each(json_array(json_extract(q.body,'$.object.object_id'),json_extract(q.body,'$.preview.image.object_id'))) ref WHERE ref.value IS NOT NULL AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)) THEN 'media' END END AS failure,q.relay_retry_at FROM (SELECT s.event_id AS id,s.recipient AS peer,p.kind AS kind,p.body_json AS body,s.send_at AS send_at,s.state AS state,1 AS cancel,CASE WHEN s.state='failed' AND EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=s.recipient AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END AS retry,s.created_at AS created,NULL AS relay_retry_at FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id UNION ALL SELECT a.message_id,m.conversationUuid,a.kind,a.body_json,NULL,CASE WHEN m.status<0 THEN 'failed' WHEN a.first_attempt_at IS NULL THEN 'queued' ELSE 'sending' END,CASE WHEN a.first_attempt_at IS NULL AND NOT EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=a.message_id UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=a.message_id) THEN 1 ELSE 0 END,CASE WHEN EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=m.conversationUuid AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END,a.created_at,CASE WHEN m.status=0 THEN d.retry_at END FROM message_sends a JOIN messages m ON m.messageId=a.message_id LEFT JOIN protocol_relay_deferrals d ON d.recipient=m.conversationUuid WHERE m.isMine=1 AND m.status<2 AND m.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?6)) q LEFT JOIN conversations c ON c.uuid=q.peer WHERE (?1 IS NULL OR q.peer=?1) AND (?2 IS NULL OR q.id=?2) AND (?3 IS NULL OR (q.send_at IS NOT NULL)=?3) ORDER BY COALESCE(q.send_at,q.created),q.id LIMIT ?4 OFFSET ?5",values:[peer.map(DatabaseValue.text) ?? .null, messageID.map(DatabaseValue.text) ?? .null, filter, .integer(Int64(limit)), .integer(Int64(offset)), .integer(self.now)],map:{row in
            let body=try JSONSerialization.jsonObject(with:Data(self.text(row,3).utf8)) as? [String:Any]
            let text: String?
            switch self.text(row,2) {
            case "chat.text": text=(body?["text"] as? String).map { SecurityCore.messageSummary($0, limit: 160) }
            case "chat.attachment":
                let name = (body?["object"] as? [String: Any])?["filename"] as? String ?? ""
                let caption = SecurityCore.messageSummary(body?["caption"] as? String ?? "", limit: 160)
                text = caption.isEmpty ? name : "\(name) · \(caption)"
            case "chat.contact": text=body?["name"] as? String
            case "chat.location": text=body?["label"] as? String ?? "Location"
            case "chat.location.start": text=body?["label"] as? String ?? "Live location"
            default: text=(body?["object"] as? [String:Any])?["filename"] as? String
            }
            let failure = sqlite3_column_type(row, 9) == SQLITE_NULL ? nil : self.text(row, 9)
            let canEdit = sqlite3_column_type(row,4) != SQLITE_NULL &&
                body.flatMap { Self.scheduledEditField(kind: self.text(row,2), body: $0) } != nil
            return SendQueueEntry(id:self.text(row,0),peer:self.text(row,1),summary:String((text ?? "").prefix(160)),
                sendAt:sqlite3_column_type(row,4)==SQLITE_NULL ? nil:sqlite3_column_int64(row,4),
                silent:body?["silent"] as? Bool == true,state:self.text(row,5),canCancel:sqlite3_column_int(row,6) != 0,
                canRetry:sqlite3_column_int(row,7) != 0 && failure == nil,
                peerAlias:sqlite3_column_type(row,8)==SQLITE_NULL ? nil:self.text(row,8),failure:failure,
                relayRetryAt:sqlite3_column_type(row,10)==SQLITE_NULL ? nil:sqlite3_column_int64(row,10),
                canEdit: canEdit)
        })};completion(rows)
    }}

    private static func scheduledEditField(kind: String, body: [String: Any]) -> String? {
        if kind == "chat.text" { return "text" }
        if kind == "chat.attachment", body["sticker"] as? Bool != true { return "caption" }
        return nil
    }

    private func scheduledBody(id: String, peer: String, at: Int64, in db: AppDatabase.Transaction) throws
        -> (kind: String, body: [String: Any])? {
        guard let row = try db.query("""
            SELECT p.kind,p.body_json FROM scheduled_sends s
            JOIN protocol_pending_events p ON p.event_id=s.event_id AND p.recipient=s.recipient
            WHERE s.event_id=? AND s.recipient=? AND s.send_at=? AND p.held=1
                AND NOT EXISTS(SELECT 1 FROM protocol_outbox o WHERE o.event_id=p.event_id)
            """, values: [.text(id), .text(peer), .integer(at)], map: { (self.text($0,0), self.text($0,1)) }).first,
              let body = try JSONSerialization.jsonObject(with: Data(row.1.utf8)) as? [String: Any] else { return nil }
        return (row.0, body)
    }

    func scheduledMessageDraft(id: String, peer: String, at: Int64,
                               completion: @escaping @Sendable (ScheduledMessageDraft?) -> Void) {
        perform(onFailure: { completion(nil) }) {
            let draft = try AppDatabase.shared.transaction { db -> ScheduledMessageDraft? in
                guard let row = try self.scheduledBody(id: id, peer: peer, at: at, in: db),
                      let field = Self.scheduledEditField(kind: row.kind, body: row.body),
                      let text = row.body[field] as? String else { return nil }
                return ScheduledMessageDraft(text: text, isCaption: field == "caption")
            }
            completion(draft)
        }
    }

    func editScheduledMessage(id: String, peer: String, at: Int64, original: ScheduledMessageDraft, replacement: String,
                              completion: @escaping @Sendable (Bool) -> Void) {
        perform(onFailure: { completion(false) }) {
            guard replacement.utf8.count <= 48 * 1024,
                  original.isCaption || !replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw self.failure("Invalid scheduled message text.")
            }
            try AppDatabase.shared.transaction { db in
                guard let row = try self.scheduledBody(id: id, peer: peer, at: at, in: db),
                      let field = Self.scheduledEditField(kind: row.kind, body: row.body),
                      (field == "caption") == original.isCaption, row.body[field] as? String == original.text else {
                    throw self.failure("The scheduled message has changed.")
                }
                guard replacement != original.text else { return }
                var body = row.body
                if let preview = body["preview"] as? [String: Any], let url = preview["url"] as? String,
                   try !WebPreviewFetcher.candidates(replacement).contains(url) {
                    body.removeValue(forKey: "preview")
                    if let object = (preview["image"] as? [String: Any])?["object_id"] as? String {
                        try db.execute("DELETE FROM scheduled_objects WHERE event_id=? AND object_id=?",
                                       values: [.text(id), .text(object)])
                    }
                }
                body[field] = replacement
                let encoded = try JSONSerialization.data(withJSONObject: body)
                try db.execute("UPDATE protocol_pending_events SET body_json=? WHERE event_id=? AND recipient=? AND held=1",
                               values: [.text(String(decoding: encoded, as: UTF8.self)), .text(id), .text(peer)])
            }
            self.notifySendQueue()
            completion(true)
        }
    }

    func manageSend(_ id: String, action: String, sendAt: Int64? = nil, expectedPeer: String? = nil,
                    expectedScheduledAt: Int64? = nil,
                    completion: @escaping @Sendable (Bool) -> Void) { perform(onFailure: { completion(false) }) {
        try AppDatabase.shared.transaction(notifyChanged:true){db in
            let planned = try db.query("SELECT recipient,send_at FROM scheduled_sends WHERE event_id=?",
                                       values: [.text(id)], map: { (peer: self.text($0,0), sendAt: sqlite3_column_int64($0,1)) }).first
            if let expectedScheduledAt, planned?.sendAt != expectedScheduledAt {
                throw self.failure("The scheduled message has changed.")
            }
            if let peer = planned?.peer {
                guard expectedPeer == nil || expectedPeer == peer else { throw self.failure("Message is no longer pending in this conversation.") }
                if action=="cancel" {try db.execute("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)])}
                else {
                    try self.requireAccepted(peer,in:db)
                    guard try db.query("SELECT 1 FROM protocol_outbox WHERE event_id=?",values:[.text(id)],map:{_ in true}).isEmpty else {throw self.failure("Message may already have been transmitted")}
                    try self.requireScheduledMedia(id, in: db)
                    switch action {
                    case "send_now":try db.execute("UPDATE scheduled_sends SET send_at=?,state='waiting' WHERE event_id=?",values:[.integer(self.now),.text(id)])
                    case "reschedule":guard let sendAt,sendAt>self.now,sendAt<=253402214399999 else {throw self.failure("Choose a future time")};try db.execute("UPDATE scheduled_sends SET send_at=?,state='waiting' WHERE event_id=?",values:[.integer(sendAt),.text(id)])
                    case "retry":try db.execute("UPDATE scheduled_sends SET state='waiting' WHERE event_id=?",values:[.text(id)])
                    default:throw self.failure("Unsupported send action")
                    }
                }
            }else{
                guard let row=try db.query("SELECT m.conversationUuid,a.kind,a.body_json,a.created_at,a.first_attempt_at FROM message_sends a JOIN messages m ON m.messageId=a.message_id WHERE a.message_id=? AND m.isMine=1 AND m.isRevoked=0 AND m.status<2 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)",values:[.text(id), .integer(self.now)],map:{(self.text($0,0),self.text($0,1),self.text($0,2),sqlite3_column_int64($0,3),sqlite3_column_type($0,4) != SQLITE_NULL)}).first,
                      let body=try JSONSerialization.jsonObject(with:Data(row.2.utf8)) as? [String:Any] else {throw self.failure("Message is no longer pending")}
                let peer=row.0
                guard expectedPeer == nil || expectedPeer == peer else { throw self.failure("Message is no longer pending in this conversation.") }
                switch action {
                case "cancel":
                    guard !row.4,try db.query("SELECT 1 FROM protocol_outbox b WHERE b.event_id=?1 UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=?1",values:[.text(id)],map:{_ in true}).isEmpty else {throw self.failure("This message may already have been sent; it cannot be cancelled")}
                    try db.execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM send_related_events WHERE message_id=?)",values:[.text(id)])
                    try db.execute("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)]);try db.execute("DELETE FROM object_transfers WHERE event_id=? AND direction=0",values:[.text(id)]);try db.execute("DELETE FROM messages WHERE messageId=? AND isMine=1",values:[.text(id)])
                case "retry":
                    try self.requireAccepted(peer,in:db)
                    _ = try self.store.enqueue(.init(eventID:id,recipient:peer,kind:row.1,body:body,createdAt:row.3),in:db)
                    try db.execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=?",values:[.text(id),.text(peer)])
                    try db.execute("UPDATE messages SET status=MAX(status,0) WHERE messageId=?",values:[.text(id)])
                    for manifest in [body["object"] as? [String:Any],(body["preview"] as? [String:Any])?["image"] as? [String:Any]].compactMap({$0}) {
                        guard let objectID=manifest["object_id"] as? String,try !db.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(objectID)],map:{_ in true}).isEmpty else {throw self.failure("Attachment is unavailable")}
                        try AttachmentQueue.record(db,event:id,objectID:objectID,outgoing:true,peer:peer,created:row.3)
                    }
                    try db.execute("UPDATE object_transfers SET attempts=0,next_retry=0 WHERE event_id=? AND direction=0",values:[.text(id)])
                    if let change = try self.savedChange(db, author: KeychainManager.shared.myUuid, target: id),
                       change["revoked"] as? Bool != true, let revision = change["revision"], let text = change["text"] as? String {
                        var edit: [String: Any] = ["message_id": id, "revision": revision, "text": text]
                        if change["caption"] as? Bool == true { edit["caption"] = true }
                        _ = try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: peer,
                            kind: "chat.edit", body: edit, createdAt: self.now), in: db)
                    }
                default:throw self.failure("Unsupported send action")
                }
                self.requested.removeValue(forKey:peer);self.unavailable.removeValue(forKey:peer)
            }
            self.scheduleDeadlineLoaded=false
        }
        self.attachments.signal();completion(true);do {try self.flush()}catch {self.report(error)}
    }}

    private func report(_ error: Error) { Task { @MainActor in NoticeCenter.shared.show(error.localizedDescription) } }
}
