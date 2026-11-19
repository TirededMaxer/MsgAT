# V2 protocol implementation contract

This is the reconstruction contract, not a claim that every platform has completed integration. See [REBUILD_STATUS.md](REBUILD_STATUS.md) for actual runtime evidence and remaining gates.

## Identity and local state

- The existing 32-byte root seed remains the account identity. Its Ed25519 public key hashes to the lowercase 64-character account ID.
- No identity export/import, recovery, device migration or same-account multi-device login is offered.
- Hardware protection is required before creating an identity. The hardware key wraps the seed; the seed is briefly available inside the application when used. This does not mean an arbitrary Ed25519 seed resides permanently inside Secure Enclave or TPM hardware.
- HKDF-SHA256 uses salt `MsgAT/v2/root-key-hierarchy` and separate purpose labels. `protocol/identity` creates the libsignal identity. `wrap/database` protects the random SQLCipher database key.
- Protocol prekeys and ratchets retain their required independent randomness. They belong to the root identity through its signatures and encrypted local database.

## Signed public directory

The outer JSON contains exactly `root_public_key`, `body` and `signature`, all Base64 strings. The root signs `MsgAT/v2/signal-bundle\0` followed by the exact decoded body bytes. Verification must not reserialize those bytes before checking the signature.

The body contains protocol version 2, the directory epoch, registration ID, expiration, the protocol identity certificate, a signed EC prekey, a signed ML-KEM-1024 prekey and up to 100 one-time EC prekeys. The identity certificate signs `MsgAT/v2/protocol-identity\0` followed by the serialized Signal identity public key. Its root must match both the directory owner and the peer account ID.

Only the authenticated root owner can publish a bundle. Clients additionally verify the root, certificate, protocol signatures and expiry. A server-provided display name or selected key never substitutes for those checks.

Key claims use a canonical UUID request ID bound to the authenticated requester and target. The server atomically reserves one available EC prekey and persists the complete response. Retrying that request ID returns the same valid reservation. When the directory has no bundle, the client backs off for five minutes and keeps its request ID. The relay rechecks that miss under the original binding and updates the same row. Send preparation can skip recipients waiting for keys while continuing with other peers. Republishing a bundle cannot restore a claimed key, and an existing key ID cannot be reassigned to different public bytes.

Prekey IDs increase monotonically. Refresh preserves the seed, certificate, protocol identity and registration ID. Clients retain old receive keys for 15 days: a seven-day public bundle can produce ciphertext immediately before expiry, followed by up to seven days of queue retention. Counters survive key consumption and pruning.

The relay creates a fresh random 16-byte epoch at process startup. On the established authenticated TLS control connection, `0x06` carries that epoch in the nonce field. The optional Android push capability stays in the payload and bulk authentication keeps the zero nonce. New directory publications must include the current epoch inside the root-signed body as canonical Base64. Each client generates fresh key IDs before its first publication in a different epoch and checks the signed epoch before using a claimed one-time key.

On an ordinary restart or a cold restoration of an older directory database, cached reservations from a previous epoch lose eligibility. A still-valid older bundle may provide its signed EC and last-resort ML-KEM prekeys with `selected_prekey: null`. The relay only allocates one-time keys from current-epoch publications. A missing or expired bundle remains unavailable until its owner republishes. The relay still enforces the reservation quota and consults the durable revocation store. Control clients and the relay must be deployed together for this revision.

## Message encryption and transactions

The shared Rust core is pinned to libsignal v0.106.2, commit `850942581c06fa65c0323fd52029afa02fa1a1ba`. Hosts use its C ABI directly (Mac/Windows) or through the bounded Android JNI adapter. There is no public-identifier encryption fallback in V2.

The binary Protobuf envelope contains version, recipient account ID, root-bound sender certificate, Signal ciphertext type and ciphertext. Single-chat types are Signal message 2 or prekey message 3, using Signal wire version 4. Legacy message versions and plaintext/sender-key types are rejected on this single-chat path. The envelope is limited to 262000 bytes.

The encrypted application event contains version 2, canonical lowercase UUIDv4 `event_id`, `kind`, `body` and millisecond `created_at`. Plaintext is limited to 64 KiB. Timestamps are bounded to `0..253402214399999`, leaving a day of timezone margin below the desktop date formatter's year-9999 limit. The outer transport event ID must match the decrypted event ID; the outer authenticated sender must match the root certificate.

The transaction rules apply on every client:

1. Save the pending application event and its local business effect in the encrypted vault before attempting directory lookup or transport.
2. Encrypt once, then commit the updated session and exact outbox ciphertext together. Send only after commit. Retry that ciphertext instead of advancing the ratchet again.
3. On receive, commit session updates, consumed prekeys, PQ key-use fingerprints, inbox deduplication, business effects and pending encrypted receipts in one transaction.
4. Send a transport acknowledgement only after a durable disposition. An authenticated, unambiguously correlated V2 event rejected by the application schema commits the Signal state and an exact-wire discard marker in the same transaction. It creates no business message, notification or delivered/read receipt.
5. Deduplicate by exact wire hash and by `(sender,event_id)`. A second authenticated payload for an accepted event ID leaves the original content in place and receives an exact-wire discard marker.

Wire authentication or decryption failures, ambiguous or unmatched event IDs, unsupported event versions or kinds, and exhausted pending-change capacity defer that delivery. The client keeps its existing ratchet and prekeys, skips the envelope for the current pass and processes later frames. The vault remembers the wire hash and routing tuple with retry delays of 30 seconds up to five minutes. Retrying uses the existing mailbox pull. The client withholds the transport acknowledgement, so relay retention governs the remaining lifetime of the ciphertext. Failures of local storage or the native component retain the transaction failure path.

The encrypted disposition table keeps up to 1,024 retry rows and 3,072 exact-wire discard markers. Retry rows last up to seven days and discard markers last up to 15 days. The table stores hashes, IDs and scheduling fields. Exact discard replays can repeat the transport acknowledgement without reapplying the event or scheduling a receipt.

The ML-KEM last-resort key is not deleted after the first incoming session. Its key/base-key use fingerprint is persisted to reject reuse where libsignal requires it.

## Delivery receipts and terminal recipient outcomes

Read/delivered receipts refer to one exact message event ID in one authenticated conversation. A read receipt no longer upgrades every older timestamp: an out-of-order or future-dated message cannot act as a read watermark. The current source accepts only concrete visible incoming IDs from a foreground, unobscured chat viewport; viewing a partial history does not mark the entire conversation read. Read-receipt creation, duplicate backfill and retry selection also honor the encrypted local privacy switch and permanent per-message suppression metadata. Locally viewed messages still clear local unread counts when disclosure is disabled. Clearing history must not erase the suppression metadata or allow a later duplicate to disclose a hidden read. See [read privacy](V2_READ_PRIVACY.md) for source status, window gates and remaining acceptance.

All hosts persist `protocol_receipts(peer,message_id,state,event_id,created_at,pending,next_retry)` inside SQLCipher. Each logical delivered/read receipt owns a stable UUIDv4 event and original timestamp. This metadata is committed with the incoming business message or local read transition. A flush stages at most 64 pending receipts; a known, currently permitted read supersedes an unstaged delivered receipt. These jobs use the normal Signal pending/outbox path rather than blocking message commits on transport.

An exact previously authenticated wire replay, or a newly decrypted envelope with the same authenticated event ID and plaintext hash, may reschedule the existing highest-state receipt in that same receive transaction. Duplicate content is never inserted or notified again. If already encrypted, retry the original outbox bytes and ratchet step; if still unprepared/cleared, reconstruct the same immutable event ID/body/timestamp. Replays are coalesced with a thirty-second per-receipt cooldown, tolerating a large local clock rollback. Receipt events themselves have no reciprocal receipt jobs, so there is no ACK loop. Cleared history is not recreated; clear-history cancels unprepared content/edit/revoke events but keeps already-generated metadata-only receipts. Minimal existing dedup/receipt metadata can remain until the final storage-retention policy. Long-offline/prekey expiry and final metadata garbage collection remain separate acceptance gates.

A relay assertion such as `revoked:true` alone never justifies permanent client failure. The server now retains the original 113-byte root-signed cancellation proof in its permanent tombstone. A key-claim response may include its Base64 `revocation`; a refused ciphertext submit uses `0x4C`, echoes the original event UUID bytes, targets the intended recipient ID and carries exactly that binary proof. A submit rejection must match a known recipient/outbox event. The client invokes the shared signature/purpose verifier and requires its derived root ID to equal the intended recipient before recording `protocol_revocations(peer,proof)`.

A verified cancellation atomically disables that contact, marks still-unconfirmed outgoing messages (`status` 0/1) as `-1`, stops unrelayed outbox retries, removes unprepared events/key requests and cancels attachment jobs for that conversation. It also deletes receipts addressed to the canceled root and their prepared outbox copies. The receive-side deduplication records remain available for delayed authenticated receipts. Enqueue guards prevent late workers from resurrecting work for that root. Previously delivered/read messages retain their factual status. A later genuine, authenticated receipt can still prove delivery/read of its own exact message; a server storage ACK cannot overwrite the unconfirmed/failed state. Active calls to that identity are closed. The three UIs display a small error indicator with “recipient identity reset; delivery unconfirmed”; this does not claim a prior recipient could not already have seen the message. The old account's history is retained locally and never reassigned to a new root ID.

Source/native-host compilation passes are not actual receipt-recovery or revocation acceptance. Required real-use cases include reordered messages, duplicate/reconnected delivery, clear-history interaction, late storage and peer receipts, signed rejection after offline reset, and refusal of a forged or mismatched cancellation proof. No mock harness was added.

## Local scheduling and send control

Current source accepts an optional boolean `silent` in encrypted text/attachment bodies. Client-only scheduled holds exclude their events from Signal preparation until release; their media is not uploaded early. New unconfirmed-send metadata retains immutable retry bodies and original media, tracks related edit handoffs and refuses to claim cancellation once disclosure may have begun. Peer Delivered/Read ends the corresponding content retry even if a relay storage ACK was lost. This module is **partial and unaccepted**: UI, notification semantics, preparation cancellation and forwarding options remain open. See [send-control contract](V2_SEND_QUEUE.md).

## Encrypted activity

The current source adds `chat.activity` inside the existing Signal envelope: a monotonic per-peer application counter, nullable online state, typing/request flags and a maximum thirty-second expiry. One outstanding event per peer waits for the recipient's encrypted Delivered receipt; relay storage ACKs do not advance that application window. Retries reuse its exact ciphertext. Short-lived status observations never authorize another action. Online/typing sharing defaults off; pending and cached-outbox paths both honor privacy. See [activity contract](V2_ACTIVITY.md) for lifetime, coalescing, resource bounds, native host integration and the unbuilt/unaccepted source status.

## Application parsing and message changes

All three hosts call the shared `validate_application_event` operation on the original decoded plaintext bytes before using a host JSON parser. The shared parser rejects duplicate properties at every depth, fractional event numbers, unknown properties, malformed UUIDv4 identifiers and unsupported event kinds. It validates the exact contact, text, attachment, receipt, edit, revoke and call body shapes. Outgoing events pass through the same validation before entering the pending queue and before Signal encryption. This source checkpoint requires rebuilt native libraries on every platform; an old ABI-2 binary does not implement these added operations.

Single-chat changes use fresh application event IDs:

| Kind | Exact body |
| --- | --- |
| `chat.edit` | `message_id`, `revision`, `text` |
| `chat.revoke` | `message_id`, `revision` |

`message_id` is the original UUIDv4 message event ID. A revision is an integer in `1..4294967295`. Only the original author may change that author's message in the same conversation; an edit applies only to text. Local changes require an accepted, unblocked contact. The old two-minute UI/clock gate is removed: author identity, current message state and revision determine permission. A display timestamp does not order revisions or grant authority.

The shared `reduce_message_change` operation selects the highest edit revision and treats revoke as terminal. A conflicting incoming revision preserves the previously committed text, and a conflicting local change fails before send. A valid revoke wins over an edit even when it arrives late. Once revoked, a later edit cannot restore the message. All hosts persist the selected change in SQLCipher and apply it in the same transaction as the incoming Signal state or outgoing event. They also apply a previously received change when its original message arrives later.

Pending changes without a base message are indexed and bounded at 256 per author and 16 MiB total, including 1024 bytes of overhead per record. Replacing an orphan edit counts toward the same budget. Orphans expire 15 days after first being saved, or leave the pending set when their original arrives. An incoming change deferred by the budget can be applied on a later pull if space becomes available. A change for an already-cleared, deduplicated base message is ignored without recreating history.

Revoke clears the visible text, updates local quote previews to a revoked marker, cancels its attachment job and removes an unreferenced object manifest/key. The encrypted object file may remain for ciphertext garbage collection; active decoders and independently retained recipient copies are not magically erased. Historical database-page erasure still belongs to storage lifecycle acceptance.

Clear-history operations run on the messaging actor, so an older in-memory pending snapshot cannot race them and recreate a cleared send. They delete local message/change plaintext, unprepared chat events, attachment jobs/references and object manifests no longer used by another conversation. Already-prepared outbox ciphertext is retained for the normal exact-wire retry/ratchet rules; cancelling arbitrarily many prepared ratchet steps would break receiving sessions. The server/recipient may already possess it. Full object garbage collection and Reset Identity remain separate completion gates.

## Identity reset and permanent revocation

Three clients now create purpose-signed public revocation intents before explicit identity destruction. A pending reset resumes before normal identity/database startup; a separate worker can deliver the proof after the old root has been erased. The relay persists permanent tombstones, refuses revoked roots across control/bulk operations and resumes bounded cleanup. Exact `0x4A`/`0x4B` fields, crash transitions, storage paths, ordinary-device limits and remaining acceptance gates are in [V2_IDENTITY_RESET.md](V2_IDENTITY_RESET.md). The old V1 account-delete sender/ACK wait paths have been removed. This is an implementation/build checkpoint, not accepted end-to-end reset behavior.

## One-to-one call signaling checkpoint

Call control now uses the same root-authenticated Signal sessions, transactional inbox/outbox and exact ciphertext retries as single-chat messages. It no longer uses the V1 ECDH call frames or legacy conversation public-key field. Only accepted, unblocked contacts may send/receive call control. Native callbacks run after the receive transaction commits; transport observer failures cannot roll back ratchet state.

Every call has a new lowercase UUIDv4 `call_id`. Every control body also has `expires_at`, bounded to more than its event's `created_at` and at most 120 seconds later. These are validated event fields, not authorization based on synchronized remote clocks. Host queues reject locally expired unsent signals. Already sealed ciphertext is retained for ratchet-safe retries.

| Kind | Additional exact body fields |
| --- | --- |
| `call.offer` | `sdp` (at most 48 KiB), `video` (boolean) |
| `call.answer` | `sdp` (at most 48 KiB) |
| `call.ice` | `sdp_mid` (at most 64 bytes), `mline_index` (0–255), `candidate` (1–4096 bytes) |
| `call.challenge`, `call.confirm` | `nonce` (Base64 of 32 random bytes) |
| `call.reject`, `call.end`, `call.busy` | none |

An offer alone never rings. An idle recipient retains one bounded invitation and generates a fresh challenge. The caller confirms only while still owning that outgoing peer/call pair. The recipient rings only after a matching confirmation within its own ten-second monotonic deadline. Early ICE is buffered up to 256 candidates, including while the challenge is pending. This prevents an offline or replayed offer from ringing after its caller has ended/restarted. Answer, ICE and terminal events must match both the active peer and call ID. Local send queues are bounded at 128; failed signaling releases the active media state. Hangup does not wait for network acknowledgment before stopping media.

The existing WebRTC audio engines remain in use. Local monotonic elapsed time determines the call record; remote terminal messages carry no trusted duration. The caller emits the record through normal encrypted text messaging. Obsolete time-sync frames are removed. The `video` field reserves the schema; current engines offer audio only and reject video invitations rather than silently downgrading them. Video implementation, actual all-pair audio acceptance and network traversal acceptance remain unfinished.

## Durable ciphertext relay

V2 currently uses the existing bounded TLS/GhostPacket frame. All control traffic still travels over TLS. The frame is limited to 256 KiB and padded to a minimum of 4096 bytes. It is not a constant-size traffic-analysis defense.

| Type | Direction | Target field | Nonce field | Payload |
| --- | --- | --- | --- | --- |
| `0x40` submit | Client → server | Recipient account ID | UUIDv4 event bytes | Binary E2EE envelope |
| `0x41` publish keys | Client → server | Own authenticated mailbox | Unused | Signed bundle JSON |
| `0x42` published | Server → client | Own mailbox | Unused | Publication acknowledgement |
| `0x43` claim keys | Client → server | Own mailbox | Unused | Request ID and target account JSON |
| `0x44` claim response | Server → client | Own mailbox | Unused | Signed bundle and selected prekey |
| `0x45` stored | Server → sender | Recipient account ID | Original event bytes | Empty |
| `0x46` delivery | Server → recipient | Authenticated sender ID | Original event bytes | Original E2EE envelope |
| `0x47` received | Recipient → server | Sender account ID | Original event bytes | Empty |
| `0x48` sync | Client → server | Own account ID | Optional correlation | Empty |
| `0x49` sync end | Server → client | Own account ID | Echoed correlation | Empty |
| `0x4C` submit rejected | Server → sender | Revoked recipient account ID | Original event bytes | 113-byte root-signed revocation proof |
| `0x4D` push update | Android → server | Own account ID | Correlation | Operation and subscription generation, plus registration or confirmation data |
| `0x4E` push result | Server → Android | Own account ID | Echoed correlation | Generation, status and expiry |
| `0x4F` submit deferred | Server → sender | Intended recipient account ID | Original event bytes | Empty |

When the operator configures ntfy, the authenticated control connection advertises `UP1` in the existing `0x06` completion frame. Android sends push subscription commands only on a connection with verified server TLS. Registration includes the endpoint and Web Push key set plus a key derived solely for authenticating wake-ups. A short-lived Web Push challenge must be confirmed before the relay sends mailbox wake-ups. Android uses a correlated sync when handling a wake-up so the completion of an earlier sync cannot end the job.

V2 queues are stored in `msgat_v2_relay.db`. The server stores ciphertext and routing metadata, with no message decryption key. A stored acknowledgement follows SQLite commit (`WAL`, `synchronous=FULL`). A recipient acknowledgement deletes only the matching authenticated recipient/sender/event tuple. Delivery is at least once; the client owns deduplication.

The store uses a persistent SQLite connection with a 2 MiB page cache. The default budgets are 64 MiB per mailbox and 2 GiB in aggregate, including a 1024-byte charge per row. The operator may choose a retention period of one to seven days. Each admission records its deadline, and unexpired retries retain it. The relay excludes expired entries from delivery and wake-ups, then removes them in batches of up to 128 rows. When admission reclaims expired rows, that cleanup commits even if the incoming envelope still exceeds the remaining quota. The sender receives a storage acknowledgement only for a committed envelope. See [relay configuration](RELAY_DEPLOYMENT.md) for defaults and limits.

If quota prevents admission, the relay returns `0x4F` on the same control connection. Each client verifies that the recipient and event identify a still-pending transmission on that connection. It retains the original encrypted envelope, waits 30 seconds before making that recipient eligible again, and continues processing other conversations. The local retry deadline survives reconnects and uses the existing send scheduler. The sending queue displays the active deferral for messages awaiting relay storage. A deferral does not advance the message receipt state.

Replay reads eight envelopes at a time and waits for socket completion before continuing. Each sync captures the recipient's current mailbox cursor. The relay echoes that request's correlation after sending eligible envelopes through the captured cursor, then continues with newly queued envelopes. Clients accept a completion only when it matches the local account and a pending request on the current connection. Ordinary retries reuse their pending nonce until completion. An Android push pull has a separate nonce. Overlapping sync requests share the ongoing replay and receive separate correlated completions. Recipient lock stripes coordinate replay completion with live enqueueing. Slow sockets that exceed the 4 MiB pending-write limit are closed. Their unacknowledged ciphertext remains durable for a later sync.

## User-visible receipts

- Grey single check: committed as sent to the relay; recipient delivery is unconfirmed.
- Green single check: the recipient has committed the message and sent an E2EE delivery receipt.
- Green double check: the recipient has sent an E2EE read receipt.

Clients apply a storage acknowledgement only when its empty payload and recipient/event pair match a locally prepared outbox row. A storage acknowledgement cannot produce delivered/read status. Receipt updates are monotonic and scoped to the sender's own messages in that peer conversation. Local unread counters clear through the same transaction that schedules the read receipt.

## Unified acceptance

- Build the three native clients and Java relay from the same revision. Exercise all six peer directions, both call modes, account lifecycle, media, encrypted storage and native UI.
- Cover directory restart, loss and restoration, old receive keys, cached reservations, revocation, quotas, long-offline retries and visible receipt state.
- Cover deferred and discarded deliveries, real storage failures, clock changes, replay after interrupted acknowledgement, and pending-change capacity becoming available.
- Verify hardware binding and the application isolation available on each operating system. The Windows TPM wrapper binds hardware protection to the device and current profile. Same-user process access is a separate operating-system boundary.

## Encrypted web-preview extension (source only, 2026-11-10)

`chat.text` now optionally carries `preview: {url,title,summary,site,image}`. The source URL must occur in the text and the shared validator bounds every field. `image` is null or a JPEG object manifest up to 512 KiB. It uses the same Signal encryption, object upload lease and recipient-only decryption as other attachments. There is no relay preview-fetch API or receive-side website request. Message and saved-item preview references participate in object lifetime, forwarding and cleanup. See [V2 web previews](V2_WEB_PREVIEWS.md) for the source behavior and uncompleted actual-client gate.

## Bound contact requests and local verification

New `contact.request` events include UUIDv4 `request_id` and per-peer `request_revision` in addition to profile fields. `contact.accept`/`contact.decline` bind that exact request ID; `contact.withdraw` also carries the request revision. `contact.delete.relationship_ids` binds an established relationship to its accepted request IDs. The common core validates these fields and reduces the same state on all hosts; profile-only events cannot smuggle request controls. A legacy unbound delete affects only a legacy relationship without modern bindings.

See [V2 contacts](V2_CONTACTS.md) for crossed requests, revision tombstones, queue/media cancellation, local block semantics, public QR payloads and libsignal verification. These additions do not expose request/profile contents to the relay and have not yet been built or accepted.


## Encrypted message pins

`chat.pin` is an encrypted single-chat control with message_id, original author, sender-local sequence, pinned boolean and up to two observed actor/sequence pairs. It uses authenticated participant identity and bounded observed-remove registers, not wall-clock ordering. Native application, unknown-target limits, lifecycle and pending-send binding are described in [message pins](V2_MESSAGE_PINS.md). All three native integrations are source-only until the unified compilation/acceptance phase.

## 附件相册

`chat.attachment` 可包含 `album` 对象。该对象的 `id` 为小写 UUIDv4，`count` 为 2 至 10，`index` 为 0 至 `count - 1`。同一相册的成员共用 `id` 和 `count`，每条消息仍有独立的事件 ID 和对象清单。只有图片和视频可加入相册，说明和回复属于序号 0。相册信息和对象清单同在 Signal 加密正文内。

收件端按会话、消息方向和相册 ID 保存相册位置。重复位置或冲突的数量不会覆盖已保存的成员，冲突附件按独立消息显示。关联表从附件记录级联删除，媒体所有权仍由既有附件及队列记录管理。相册字段要求发送和接收端均使用支持该字段的客户端。
