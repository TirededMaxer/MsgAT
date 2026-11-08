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

The body contains protocol version 2, registration ID, expiration, the protocol identity certificate, a signed EC prekey, a signed ML-KEM-1024 prekey and up to 100 one-time EC prekeys. The identity certificate signs `MsgAT/v2/protocol-identity\0` followed by the serialized Signal identity public key. Its root must match both the directory owner and the peer account ID.

Only the authenticated root owner can publish a bundle. Clients additionally verify the root, certificate, protocol signatures and expiry. A server-provided display name or selected key never substitutes for those checks.

Key claims use a canonical UUID request ID bound to the authenticated requester and target. The server atomically reserves one available EC prekey and persists the complete response. Retrying that request ID returns the same reservation. Republishing a bundle cannot restore a claimed key, and an existing key ID cannot be reassigned to different public bytes.

Prekey IDs increase monotonically. Refresh preserves the seed, certificate, protocol identity and registration ID. Clients retain old receive keys for 15 days: a seven-day public bundle can produce ciphertext immediately before expiry, followed by up to seven days of queue retention. Counters survive key consumption and pruning.

Directory admission/rate controls, revocation and explicit handling of a fresh server directory epoch remain completion gates. A fresh directory must not accidentally assign already-consumed keys from an old signed bundle.

## Message encryption and transactions

The shared Rust core is pinned to libsignal v0.106.2, commit `850942581c06fa65c0323fd52029afa02fa1a1ba`. Hosts use its C ABI directly (Mac/Windows) or through the bounded Android JNI adapter. There is no public-identifier encryption fallback in V2.

The binary Protobuf envelope contains version, recipient account ID, root-bound sender certificate, Signal ciphertext type and ciphertext. Single-chat types are Signal message 2 or prekey message 3, using Signal wire version 4. Legacy message versions and plaintext/sender-key types are rejected on this single-chat path. The envelope is limited to 262000 bytes.

The encrypted application event contains version 2, canonical lowercase UUIDv4 `event_id`, `kind`, `body` and millisecond `created_at`. Plaintext is limited to 64 KiB. The outer transport event ID must match the decrypted event ID; the outer authenticated sender must match the root certificate.

The transaction rules apply on every client:

1. Save the pending application event and its local business effect in the encrypted vault before attempting directory lookup or transport.
2. Encrypt once, then commit the updated session and exact outbox ciphertext together. Send only after commit. Retry that ciphertext instead of advancing the ratchet again.
3. On receive, commit session updates, consumed prekeys, PQ key-use fingerprints, inbox deduplication, business effects and pending encrypted receipts in one transaction.
4. Send a transport acknowledgement only after that transaction commits. A rejected event rolls back the cryptographic and business changes together.
5. Deduplicate both exact wire hashes and `(sender,event_id)`. Reusing an event ID for different plaintext is an error.

The ML-KEM last-resort key is not deleted after the first incoming session. Its key/base-key use fingerprint is persisted to reject reuse where libsignal requires it.

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
| `0x48` sync | Client → server | Own account ID | Unused | Empty |
| `0x49` sync end | Server → client | Own account ID | Unused | Empty |

V2 queues are stored in `msgat_v2_relay.db`. The server stores ciphertext and routing metadata, with no message decryption key. A stored acknowledgement follows SQLite commit (`WAL`, `synchronous=FULL`). A recipient acknowledgement deletes only the matching authenticated recipient/sender/event tuple. Delivery is at least once; the client owns deduplication.

The current bounded store uses a persistent SQLite connection with a 2 MiB page cache, a 64 MiB per-mailbox budget and a 2 GiB aggregate budget. Each row incurs an additional 1024-byte accounting charge so tiny malicious payloads cannot evade storage limits through metadata overhead. Expiry removes up to 1000 rows per minute after seven days. These are initial resource controls, not measured production capacity or completed abuse prevention.

Replay reads eight envelopes at a time and waits for socket completion before continuing. Recipient lock stripes coordinate replay completion with live enqueueing. Slow sockets are closed at the existing 4 MiB pending-write limit; their unacknowledged ciphertext remains durable for a later sync.

## User-visible receipts

- Grey single check: committed as sent to the relay; recipient delivery is unconfirmed.
- Green single check: the recipient has committed the message and sent an E2EE delivery receipt.
- Green double check: the recipient has sent an E2EE read receipt.

A server storage acknowledgement cannot produce delivered/read status. Receipt updates are monotonic and scoped to the sender's own messages in that peer conversation. Local unread counters clear through the same transaction that schedules the read receipt.

## Remaining cutover requirements

- Validate the newly wired Android coordinator, implement the Windows coordinator, and accept native deployment and all peer directions on actual clients.
- Replace remaining legacy media, call, edit/revoke and account lifecycle paths; add all group and content operations from the full plan.
- Domain-bind the authentication transcript, finish directory epoch/replenishment/revocation handling, and implement admission/rate controls.
- Strengthen per-application access isolation. Hardware device binding alone does not prove protection against another process running under the same OS user; in particular, the current Windows TPM wrapper is not yet an application isolation boundary.
- Retire legacy profile decryption, fixed server storage keys and public-key fallback handlers before releasing V2.
- Finish native UI, encrypted media/storage, OS privacy integration, actual-client acceptance and final cloud packaging.
