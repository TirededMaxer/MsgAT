// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct ConversationDraft: Sendable {
    let peer: String
    let token: String
    let revision: Int64
    let text: String
    let replyId: String?
    var replyQuote: String? = nil
}

final class DraftSendCommit: @unchecked Sendable {
    let draft: ConversationDraft
    let clearRevision: Int64
    let clearsText: Bool
    let updatesDraft: Bool
    let owner: ConversationDraftStore
    fileprivate let finished = DispatchGroup()
    fileprivate init(_ draft: ConversationDraft, revision: Int64, owner: ConversationDraftStore, clearsText: Bool, updatesDraft: Bool) {
        self.draft = draft; self.clearRevision = revision; self.owner = owner; self.clearsText = clearsText; self.updatesDraft = updatesDraft
        finished.enter()
    }
}

/// One coalescing writer; draft contents stay inside the existing SQLCipher vault.
final class ConversationDraftStore: @unchecked Sendable {
    static let shared = ConversationDraftStore()
    private struct Key: Hashable { let peer: String; let token: String }
    private let gate = NSLock()
    private let queue = DispatchQueue(label: "org.msgat.conversation-drafts", qos: .utility)
    private var pending: [Key: ConversationDraft] = [:]
    private var latest: [Key: Int64] = [:]
    private var sending: [String: [Int64: DraftSendCommit]] = [:]
    private var revision: Int64 = 0
    private var initialized = false
    private var scheduled = false
    private var closed = false
    private var failureReported = false
    private init() {}

    func open(_ peer: String, completion: @escaping @Sendable (Result<ConversationDraft, Error>) -> Void) {
        queue.async { self.openOnQueue(peer, completion: completion) }
    }

    private func openOnQueue(_ peer: String, completion: @escaping @Sendable (Result<ConversationDraft, Error>) -> Void) {
        do {
            if let active = try locked({ () throws -> DraftSendCommit? in
                guard !closed else { throw failure() }
                return sending[peer]?.values.first
            }) {
                active.finished.notify(queue: queue) { self.openOnQueue(peer, completion: completion) }
                return
            }
            if !initialized {
                let maximum = try AppDatabase.shared.query("SELECT COALESCE(MAX(revision),0) FROM conversation_drafts", map: { sqlite3_column_int64($0, 0) }).first ?? 0
                locked { revision = maximum }
                initialized = true
            }
            let value = try AppDatabase.shared.transaction { db in
                try db.execute("INSERT OR IGNORE INTO conversation_drafts(peer,token,revision,text,reply_id) SELECT uuid,?,0,'',NULL FROM conversations WHERE uuid=?",
                    values: [.text(UUID().uuidString.lowercased()), .text(peer)])
                guard let result = try db.query("SELECT token,revision,text,reply_id,reply_quote FROM conversation_drafts WHERE peer=?", values: [.text(peer)], map: { row in
                    ConversationDraft(peer: peer, token: String(cString: sqlite3_column_text(row, 0)), revision: sqlite3_column_int64(row, 1),
                        text: String(cString: sqlite3_column_text(row, 2)), replyId: databaseOptionalText(row, 3),
                        replyQuote: databaseOptionalText(row, 4))
                }).first else { throw self.failure() }
                return result
            }
            let selected = try locked { () throws -> ConversationDraft? in
                guard !closed else { throw failure() }
                revision = max(revision, value.revision)
                if sending[peer]?.isEmpty == false { return nil }
                let key = Key(peer: peer, token: value.token)
                if let local = pending[key], local.revision > value.revision { return local }
                if (latest[key] ?? 0) > value.revision { return nil }
                return value
            }
            if let selected { completion(.success(selected)) }
            else { queue.async { self.openOnQueue(peer, completion: completion) } }
        } catch { completion(.failure(error)) }
    }

    func update(_ previous: ConversationDraft, text: String, replyId: String?, replyQuote: String? = nil) throws -> ConversationDraft {
        guard text.utf8.count <= 1024 * 1024 else { throw failure() }
        let quote = replyQuote.flatMap { replyId != nil && $0.utf8.count <= 128 ? $0 : nil }
        if previous.text == text && previous.replyId == replyId && previous.replyQuote == quote { return previous }
        return try locked {
            guard !closed, revision < Int64.max else { throw failure() }
            revision += 1
            let value = ConversationDraft(peer: previous.peer, token: previous.token, revision: revision, text: text,
                                          replyId: replyId, replyQuote: quote)
            let key = Key(peer: value.peer, token: value.token)
            pending[key] = value; latest[key] = value.revision
            scheduleLocked(after: 0)
            return value
        }
    }

    func prepareSend(_ value: ConversationDraft, clearsText: Bool = true) throws -> DraftSendCommit {
        try locked {
            guard !closed, revision < Int64.max else { throw failure() }
            revision += 1
            let updatesDraft = (latest[Key(peer: value.peer, token: value.token)] ?? 0) <= value.revision
            let commit = DraftSendCommit(value, revision: revision, owner: self, clearsText: clearsText, updatesDraft: updatesDraft)
            sending[value.peer, default: [:]][revision] = commit
            return commit
        }
    }

    static func clearForCommittedSend(_ db: AppDatabase.Transaction, _ commit: DraftSendCommit) throws {
        guard commit.updatesDraft else { return }
        try db.execute("UPDATE conversation_drafts SET text=CASE WHEN ?=1 THEN '' WHEN revision<=? THEN ? ELSE text END,reply_id=NULL,reply_quote=NULL,revision=? WHERE peer=? AND token=? AND revision<?",
            values: [.integer(commit.clearsText ? 1 : 0), .integer(commit.draft.revision), .text(commit.draft.text), .integer(commit.clearRevision), .text(commit.draft.peer), .text(commit.draft.token), .integer(commit.clearRevision)])
    }

    func observeCommittedSend(_ commit: DraftSendCommit) {
        locked {
            let key = Key(peer: commit.draft.peer, token: commit.draft.token)
            if commit.updatesDraft {
                latest[key] = max(latest[key] ?? 0, commit.clearRevision)
                if let value = pending[key], value.revision <= commit.draft.revision { pending.removeValue(forKey: key) }
            }
            finishLocked(commit)
        }
    }

    func observeFailedSend(_ commit: DraftSendCommit) { locked { finishLocked(commit) } }
    private func finishLocked(_ commit: DraftSendCommit) {
        if sending[commit.draft.peer]?.removeValue(forKey: commit.clearRevision) != nil { commit.finished.leave() }
        if sending[commit.draft.peer]?.isEmpty == true { sending.removeValue(forKey: commit.draft.peer) }
    }

    private func scheduleLocked(after delay: Double) {
        guard !scheduled, !closed, !pending.isEmpty else { return }
        scheduled = true
        queue.asyncAfter(deadline: .now() + delay) { self.drain() }
    }

    private func write(_ values: [ConversationDraft]) throws {
        for value in values {
            try AppDatabase.shared.execute("UPDATE conversation_drafts SET text=?,reply_id=?,reply_quote=?,revision=? WHERE peer=? AND token=? AND revision<?",
                values: [.text(value.text), value.replyId.map(DatabaseValue.text) ?? .null, value.replyQuote.map(DatabaseValue.text) ?? .null,
                    .integer(value.revision),
                    .text(value.peer), .text(value.token), .integer(value.revision)])
            locked {
                let key = Key(peer: value.peer, token: value.token)
                if pending[key]?.revision == value.revision { pending.removeValue(forKey: key) }
            }
        }
    }

    private func drain() {
        let batch = locked { closed ? [] : Array(pending.values) }
        var failed = false
        do { try write(batch); failureReported = false }
        catch {
            failed = true
            if !failureReported {
                failureReported = true
                Task { @MainActor in NoticeCenter.shared.show("The draft could not be saved. It will be retried.") }
            }
        }
        locked { scheduled = false; scheduleLocked(after: failed ? 2 : 0) }
    }

    func close() throws {
        let first = locked {
            if closed { return false }
            closed = true
            for active in sending.values { for commit in active.values { commit.finished.leave() } }
            sending.removeAll()
            return true
        }
        if first { try queue.sync { try write(locked { Array(pending.values) }) } }
    }

    private func locked<T>(_ work: () throws -> T) rethrows -> T { gate.lock(); defer { gate.unlock() }; return try work() }
    private func failure() -> Error { MsgATDatabaseError(operation: "Draft", detail: "The saved draft is unavailable.") }
}
