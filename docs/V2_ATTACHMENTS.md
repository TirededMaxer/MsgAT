# V2 attachment implementation contract

This is an unfinished reconstruction contract. It describes the new source paths, not completed three-client acceptance. No V2 release has been published.

## Encrypted objects

The shared Rust core provides `create_object`, `validate_object_manifest`, `seal_object_chunk`, `open_object_chunk`, `check_object_chunk`, `import_object_file` and `read_object_range` through C ABI 2. All platforms use this implementation rather than separate cryptographic formats.

- An object has an independent random 32-byte identifier, encoded as 64 lowercase hexadecimal characters.
- The unchanged 32-byte identity seed feeds the root HKDF-SHA256 hierarchy. Object content keys use the `objects/content/` purpose, object identifier and an independent secret random 32-byte salt. The salt is discarded. A public object identifier and a later root leak alone therefore do not reconstruct historical network object keys.
- The resulting content key survives in the owner's SQLCipher manifest and in encrypted Signal application events. Recipients keep their copy in their own root-protected vault. No object key is sent to the relay.
- Root seed buffers are needed only while creating the content key, before file import or playback. No root export/import or identity recovery path is added.
- Content uses RustCrypto `aes-gcm-siv` 0.12.1, AES-256-GCM-SIV, as specified in [RFC 8452](https://www.rfc-editor.org/rfc/rfc8452.html). This is a named AEAD construction, not a claim that this application has completed an independent cryptographic audit.

Each plaintext chunk contains at most 65536 bytes. The last chunk is zero-padded to 65536 bytes. Empty files still have one authenticated padded chunk. Every encrypted chunk is exactly 65552 bytes, including its 16-byte authentication tag. Files concatenate encrypted chunks without a plaintext filename or size header.

The 12-byte nonce is eight zero bytes followed by the big-endian 32-bit chunk index. Associated data is the exact concatenation:

1. ASCII `MsgAT/v2/object-chunk` followed by a zero byte.
2. One version byte, `2`.
3. The raw 32-byte object identifier.
4. The exact plaintext object size, big-endian unsigned 64-bit.
5. The chunk size `65536`, big-endian unsigned 64-bit.
6. The chunk index, big-endian unsigned 32-bit.

Decryption checks the expected chunk index, length and zero padding. The encrypted manifest supplies complete chunk count and whole-file hashes. A chunk cannot be transplanted into a different object, position or file size. Retries resend the original ciphertext; they do not re-encrypt mutable content under a reused object identity.

## Manifest and local storage

The manifest contains exactly:

| Field | Meaning |
| --- | --- |
| `version` | Integer `2` |
| `object_id` | Random 64-character lowercase hexadecimal identifier |
| `key` | Base64 content key, 32 decoded bytes |
| `read_token` | Independent random retrieval capability, 32 decoded bytes |
| `read_token_hash` | SHA-256 of the capability, lowercase hexadecimal |
| `size` | Exact plaintext size |
| `chunk_size`, `chunk_count`, `cipher_size` | Fixed chunk policy and derived encrypted size |
| `sha256`, `cipher_sha256` | Whole-file plaintext and ciphertext SHA-256 |
| `filename` | Display filename, at most 1024 UTF-8 bytes, no control characters or path separators |
| `mime` | Bounded ASCII MIME type |

Shared-core size support is bounded at 2 GiB. Current client import policy is 100 MiB. The relay independently enforces its configured file and storage limits.

Local ciphertext paths use `objects/v2/<first two identifier characters>/<remaining identifier>.obj`. Android places this under `noBackupFilesDir`; Mac excludes its object root from backup. Original filenames, keys, capabilities, exact sizes and captions reside in SQLCipher, not sidecar JSON files. The UI reference is `msgat-object://<identifier>`; it is interpreted only for a typed attachment message, not arbitrary chat text.

Native regular-file import writes only encrypted staging/output. Android non-seekable document providers are read twice and encrypted in bounded chunks without a plaintext staging file. Windows clipboard images are encoded in bounded memory and written directly as encrypted chunks. Explicit user export is a separate operation that writes plaintext to the selected destination.

`read_object_range` authenticates bounded ranges and returns at most 1 MiB per call. Android and Windows stream adapters keep at most one 64 KiB plaintext chunk per reader and clear it on replacement/close. These readers support native decoders and seeking; they are not a decrypted disk cache.

## Signal event and durable jobs

Each attachment uses `kind: chat.attachment` with body `object` (manifest), `caption` and optional `reply`. Each caption is limited to 48 KiB UTF-8. In an album, the first member can carry the reply and its caption is rendered under the group. Each later member can carry its own caption. A recipient needs a core that accepts captions on those members. The normal Signal event ID, sender certificate, replay checks and transaction rules apply. Only accepted, unblocked contacts may create an incoming attachment message.

Messages use local type `attachment`. `media_objects` stores immutable manifests and availability. `message_attachments` stores each message's object relationship and caption. `object_transfers` stores pending upload/download jobs with explicit message/profile ownership. Message deletion removes message-owned jobs; avatar jobs do not require a chat bubble. Active transfer pins retain files until a running operation finishes. See [encrypted profiles](V2_PROFILES.md). Object IDs may not be rebound to conflicting manifests.

The Windows source now commits the outgoing message, pending Signal event and upload job together after encrypted file import. A separate bulk worker uploads the encrypted object before the Signal actor encrypts/sends the manifest. The readiness filter runs in SQL before pagination, so a page of blocked media cannot prevent subsequent text/receipt traffic. Retries keep the same object and Signal ciphertext.

Upload leases are tied to the current control-connection session. Reconnect rechecks/renews the remote object; a fresh relay cannot be assumed to retain an earlier upload. The relay refreshes completed object retention on an authenticated matching owner reservation and starts final retention at upload completion. Clients require enough remaining lease to cover the seven-day message queue plus a margin. A short lease or clock disagreement uses bounded retry, not a tight upload loop.

On receive, Signal state, the attachment manifest, message, download job and delivered receipt commit atomically before the transport ACK. This delivered state means the encrypted message/manifest was committed; file download progress is shown separately. Download authenticates each encrypted chunk and checks the final ciphertext hash before marking the local object available.

Android and Mac synchronize the object directory chain after publishing verified ciphertext and before setting the availability flag. A complete file recovered from an interrupted publication follows the same path. Host byte imports also synchronize the published object path before returning its manifest for a vault commit.

If all ciphertext blocks are already on disk when a worker resumes, it verifies and finalizes the partial file locally. The transfer connection is opened only when blocks are missing. The space check counts whole reusable blocks, and an oversized partial counts as zero. A partial file that fails verification is discarded so the next attempt can fetch fresh ciphertext.

All three clients offer Download again in the chat menu for a received attachment with a local copy. After confirmation, the existing cache journal removes the selected ciphertext before the existing download queue starts. The message and attachment manifest remain in the vault. The operation excludes objects with an active transfer or other owners such as saved copies. It is useful when a completed file later becomes unreadable and the relay still has it.

When opening received media from chat, paging through received previews, or opening or exporting received media from Shared Media, the clients compare the local ciphertext length and SHA-256 digest with the encrypted manifest. The checks stream ciphertext on a background worker before full rendering or playback. Missing files and mismatches enter the same replacement queue. The transaction checks the original message and object reference before replacing the cache. Media readers still authenticate individual chunks.

The attachment-opening UI indicates that a check is running. Cancelling during the scan stops at a block boundary, before initiating any replacement.

Chat and shared media display the persisted download intent alongside live worker progress. An explicitly requested download can be paused while queued, offline, or in progress. The pause survives restart and preserves encrypted chunks for resumption. Resuming makes the existing job eligible again. The active worker observes a pause at its next transfer checkpoint. The original message, attachment manifest, and media ownership are unchanged by these controls.

The three chat clients show a progress bar for an individual attachment while its worker reports a known upload or download size. Status labels follow the interface language, and pausing a download hides the active progress bar while retaining the encrypted chunks.

Shared media also shows known transfer progress on thumbnails and file rows. Progress events update the visible status, while a committed pause or resume refreshes the attachment metadata before the action becomes available again. Retries and paused items keep their distinct labels.

## Separate authenticated transfer channel

Control and bulk transfer use the same configured TCP/TLS port with separate connections. TLS 1.2 or 1.3 is required. The fixed request marker is `MTA2` for control and `MTB2` for bulk. A bulk connection never replaces the account's control routing entry and does not replay its message queue.

Authentication challenges contain version byte `2`, role byte (`0` control, `1` bulk), the client's 16-byte nonce and the connected TLS leaf certificate's SHA-256 digest. The separate frame nonce contains the server's fresh 16-byte nonce. The root signs `MsgAT/v2/relay-auth\0`, version, role, raw 32-byte mailbox, client nonce, server nonce and certificate digest. Bare-nonce V1 proof and role/endpoint substitutions are rejected. Attempts expire after 30 seconds.

This is purpose/nonce/TLS-certificate endpoint binding. It is not a TLS-exporter channel binding; that distinction remains relevant to the broader authentication review.

| Type | Request/response |
| --- | --- |
| `0x50` begin | Target object ID; JSON containing only `cipher_size`, `cipher_sha256`, `read_token_hash` |
| `0x51` status | Echoed request target/nonce; `next_chunk`, `complete`, `expires_at` (Unix seconds) |
| `0x52` put | One raw binary encrypted chunk; first eight nonce bytes contain its big-endian index |
| `0x53` finish | Empty body; verify full encrypted size/hash and publish object |
| `0x54` get | Raw 32-byte capability; chunk index in first eight nonce bytes |
| `0x55` data | One raw binary encrypted chunk |
| `0x56` delete | Empty body; authenticated owner only |
| `0x57` deleted | Deletion acknowledgement |
| `0x58` error | Bounded generic unavailable response; no filesystem/parser details |

All responses echo the full request nonce. Bulk wire data is binary, not JSON Base64. Base64 used by the bounded local FFI is not the transfer encoding.

## Relay resources and recovery

`msgat_v2_objects.db` stores ownership, capability hashes, durable cursors, encrypted size/hash and expiration. Files are encrypted `.part`/`.obj` objects in random shards. The server does not hold content keys, filenames or exact plaintext sizes.

Full encrypted size plus metadata overhead is reserved against quotas before accepting a new object. After a new chunk is flushed, the same SQLite write advances the durable cursor and extends the incomplete upload's deadline by 24 hours. Repeated chunks must match stored bytes and leave the deadline unchanged. Resume truncates bytes beyond the durable cursor. Completion verifies the full ciphertext hash, atomically renames and commits completion with the configured retention lease; a rename completed before a crash can be reconciled on retry.

An upload reservation is renewed as complete only when its ciphertext file exists at the expected size. When the durable prefix is missing or truncated, or completion detects a hash mismatch, the owner can restart the same upload from chunk zero. The object ID, manifest hashes, capability and quota reservation stay bound to the existing owner. Replaying a chunk after rename but before the completion commit compares against the renamed ciphertext.

Bulk file work uses two dedicated executor threads with bounded queues. Control handlers keep their own executor. The existing 4 MiB pending-write cap applies; bulk handlers also bound per-connection request rate. The same expiry index covers completed objects and incomplete uploads without progress for 24 hours. Cleanup handles up to 16 objects per pass, returning after two seconds for a full batch or one minute otherwise.

JAR-adjacent JSON defaults: `attachmentMaxBytes` 100 MiB, `attachmentOwnerQuotaBytes` 512 MiB, `attachmentGlobalQuotaBytes` 8 GiB, `attachmentRetentionDays` 14. Retention accepts 8–30 days so file availability can cover the current seven-day ciphertext message queue. The configured data directory is also resolved relative to the JAR.

## 附件另存目录

三端的设置可以搜索「附件保存目录」，选择文件夹或恢复系统默认。设置只决定手动另存时系统对话框尝试打开的位置。每次都由用户确认文件名和目标，聊天、收藏和共享媒体共用该设置。偏好写入各端原有的受保护本机存储。

Android 保存文件夹 URI 作为系统选择器的位置提示，不持久保留文件夹授权。macOS 和 Windows 把目录交给原生另存窗口。系统可以在目录不可用时选择其他起始位置。消息和自动下载的附件继续保存在应用内加密库中。

## Incomplete work

- All three clients now have source-wired transfer workers, attachment events and encrypted preview adapters. Actual interoperability, native decoder/player behavior and privacy-cache observation are still required; source parity is not acceptance.
- Windows native DLL rebuild, full WPF compilation and development publish passed. Its final preflight/timestamp edits require a matching publish before actual runtime work. Android APK/lint and the Mac attachment UI/transport compiled; Android device installation was rejected and is awaiting the user. Private-renderer runtime/cache observation and real transfers remain acceptance work.
- The checkpoint predates album, transfer retry/cancel, forwarding and persisted thumbnail work. The three clients now derive bounded, independently encrypted thumbnail objects through authenticated object reads. SQLCipher holds the cache associations. See [media behavior](V2_MEDIA_CONFIRMATION.md) for invalidation, accounting and shutdown. The complete media UI and its runtime behavior still require unified acceptance.
- Complete orphan/cache collection, Reset Identity cleanup of object/job data and platform process isolation before release.
- Accept actual cross-client image/file/video use, offline/reconnect/resume, renderer/decoder errors, export and shutdown. Compilation is not evidence of those outcomes.
