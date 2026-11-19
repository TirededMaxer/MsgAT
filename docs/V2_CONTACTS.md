# V2 contacts, profiles and verification

Chat contact cards carry a display name and public identity ID inside an encrypted application event. Forwarding retains both fields. A saved card is an independent local snapshot, and deleting it removes its associated identity field. Opening a card does not modify contacts. Users can explicitly request a connection or open an existing chat.

Status: source implementation, 2026-11-14. The latest layouts and workflows await the unified hosted build and actual-client session.

## Identity and trust

A contact ID is the existing SHA-256 identifier of its root public key. Adding a contact does not trust a relay-supplied nickname: the common core verifies the root signature on its protocol identity and binds that root to the requested ID. The root seed, hardware protection and no-migration policy are unchanged.

Safety codes use the project's pinned [libsignal fingerprint implementation](https://github.com/signalapp/libsignal/blob/850942581c06fa65c0323fd52029afa02fa1a1ba/rust/protocol/src/fingerprint.rs), version 2 with 5200 iterations, using each account ID as its stable identifier and the root-verified protocol identity key. Both clients display the same 60 digits; scannable fingerprints retain libsignal's opposite-party comparison. No new fingerprint algorithm is invented.

`contact_verifications` stores the compared display code and timestamp in SQLCipher. Directory verification reads only public certificates from the protocol rows; root seeds and private ratchet snapshots are not passed to the directory/QR helpers. Directory operations have owned/drained lifetimes on each client, including queued work, before vault closure or identity protection destruction. Manual verification requires matching the other party's code through another trusted channel. QR verification compares the scanned fingerprint against the current local certificates inside the verification transaction. A stale displayed code cannot silently verify changed keys. Revoked identities cannot be verified, requested, accepted or unblocked. A verification mark does not migrate/export an identity and does not override Signal's trusted-key checks.

## Request state machine

All three hosts persist `contact_links.state_json` in SQLCipher and call the same Rust transition reducer. Its flags are committed with `contact_states`, request rows and outgoing Signal events. Existing established development contacts can retain their current flags; earlier unreleased pending requests without request bindings need a new request before acceptance.

| Event | Additional encrypted fields | Meaning |
| --- | --- | --- |
| `contact.request` | `request_id`, `request_revision`, plus the existing profile | A UUIDv4 request with a monotonically increasing per-peer sender counter. Explicit resend supersedes the earlier request. Transport retries retain the original event. |
| `contact.accept` | `request_id`, plus the existing profile | Accept exactly the currently pending outgoing request. |
| `contact.decline` | `request_id` | Reject that request without changing a newer request or an independently established relationship. |
| `contact.withdraw` | `request_id`, `request_revision` | Withdraw that request. The receiver keeps a terminal revision even when withdrawal arrives first. |
| `contact.delete` | `relationship_ids` | Remove the established relationship whose accepted request bindings match. An old deletion cannot tear down a later relationship. |
| `contact.profile` | Existing versioned profile | Update an accepted, unblocked contact's profile; it grants no relationship authority. |

Two simultaneous requests can each contribute one accepted binding. Withdrawal removes only its own binding; a delete affecting the current relationship closes both. Older/lower request revisions, mismatched decisions, duplicate terminal requests and unbound acceptances do not establish contacts. Decisions are not ordered using a peer's wall clock.

The relay continues to carry opaque Signal ciphertext. These fields, profiles and safety marks are not added to public routing metadata.

## Block, deletion and media ownership

Blocking is local and sends no block announcement. It closes request flags, stops queued outgoing control/chat work and unconfirmed outbox retries, disables pending receipt transmission, and ends the corresponding active call through the messaging layer. Sending, incoming message/profile application and call dispatch continue to check accepted/unblocked state. Unconfirmed messages become failed/unconfirmed; already delivered/read states remain factual.

Incoming media jobs remain owned but paused while unauthorized, so an unblock or a later authorized request can resume existing encrypted bytes. Outgoing jobs whose envelope was canceled are removed. Local contact deletion additionally clears its conversation/history, profile relationship and all its transfer jobs. Saved/forwarded copies retain their independent ownership. A remote delete retains local history rather than allowing another user to erase it.

Bulk queues now re-check the exact transfer row, message/profile owner, contact permissions and connection epoch before each new chunk, and re-check ownership/permissions in the final availability/lease transaction. Invalidation pauses the job without a failure retry loop. A current network exchange cannot be recalled; no next chunk is initiated after its checkpoint observes the block. Ciphertext partial files remain protected by the existing transfer pin and reference rules. The normal eligible-job scan/retry timer handles resumption. Canceled outbox IDs are also pruned from the bounded in-memory send window.

## Native controls and public QR payloads

All three clients expose profile editing and avatar removal, contact name/ID search with 50-row pages, a blocked filter, resend/withdraw/accept/decline, block/unblock/delete, numeric comparison and verification removal. Contact-specific changes refresh the open directory and selected profile. The directory shows round avatars and a separate search field. Android navigates from the directory to the selected profile and can return to the previous search and scroll position. macOS and Windows use native two-column directory windows with the selected profile on the right. The friend request list uses compact icon actions with accessible labels. While a decision is being submitted, only the affected row's decision controls are disabled.

The chat header avatar opens the profile for that exact contact. The profile groups the avatar, name and relationship status above actions for calls and shared media. It also shows the public ID with a copy icon, the conversation's notification options and a safety-code page. The avatar can open a larger in-app preview. Block and delete actions live in the overflow menu and require confirmation. The chat title and connection indicator remain separate from the profile button. Safety-code scans stay scoped to that contact. Contact-request QR codes are handled in the directory.

`msgat://contact/<account-id>` contains only a public account identifier. A scanned contact ID requires confirmation before a request is sent. `msgat://verify/<base64url-fingerprint>` contains a public libsignal scannable fingerprint, and verifies only the selected contact. Neither payload can select/change a relay address, expose a private key or transfer an identity. Foreign QR payloads are rejected rather than opened as URLs.

Android uses ZXing Core 3.5.4 and the bounded RAM image reader, including a system camera preview with explicit camera permission. Windows uses ZXing.Net 0.16.11 with WPF pixel buffers. Mac uses Core Image generation and Vision QR recognition. Both desktop clients select an image through their native picker; all three generate public identity and safety QR images without app-created plaintext temporary files. Input images are bounded to 16 MiB and decoding/scanning dimensions are bounded. ZXing's Apache license/notice source is included through the package resource configuration; see `shared/licenses` and `THIRD_PARTY_NOTICES.md`.

## Unified acceptance still required

Actual acceptance must cover every client direction, crossed requests, resend and out-of-order/duplicate decisions, withdraw/accept races, offline restart, block during an active media transfer or call, unblock/resume, local versus remote deletion, exact numeric comparison, opposite-party QR scans, invalid/foreign images, camera permission denial, profile/avatar updates and legacy development state. New source/configuration review does not prove any of those runtime results. No build, installation, runtime test or release was started for this checkpoint.


## 发送控制联动（2026-11-10，源码）

关系暂停不再直接抹除所有已准备密文：outbox 用 `-2` 标记暂停，保留原字节供重新获准后的明确消息重试；不会在解除拉黑时自动重发自己的内容。未准备事件删除会级联取消定时持有项并释放其媒体所有权。允许的事实回执可恢复，但隐私取消的 Read 和根签名撤销仍是终止状态；关闭已读同样覆盖暂停的未发 Read。根撤销还清理不可再重试的原始正文副本。详见[发送控制](V2_SEND_QUEUE.md)；这些联动尚未构建或验收。
