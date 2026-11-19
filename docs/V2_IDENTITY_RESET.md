# V2 identity reset — implementation checkpoint

Reset Identity is an explicit, confirmed destructive action in Settings. It is not a migration, logout, session repair or automatic response to a failed key unlock. The root seed format and account ID derivation are unchanged.

## Local state transition

1. While the old protected root is still available, sign a permanent cancellation authorization. Persist a public reset intent atomically before destroying anything. The intent stores only that proof and the configured relay host, port, certificate pin and explicit development-certificate policy; it contains no seed, protocol key, database key, nickname, conversation or TURN password.
2. Once the marker is committed, close the current process and drain its service owners through shutdown. A shutdown error does not return to normal use. Key destruction and file cleanup take place at startup, after the previous process has released the profile.
3. On Android and Mac, reopening acquires the vault's exclusive process lease before touching the marker. Windows restarts and acquires the application instance mutex first. Before opening any identity or database, publish the signed proof into the private retry directory, destroy the hardware protection reference and old key envelopes, then remove the old databases and journals, app-managed media, encrypted objects and scoped caches. A failure retains the marker for the next startup.
4. Remove the marker only after local erasure succeeds. New identity provisioning then follows the usual mandatory hardware checks. No old account ID, private key or conversation state is copied into the new identity.
5. A separate bounded worker delivers old signed revocations without the destroyed root. It keeps up to 256 public pending intents and attempts up to eight per batch, rotating through their sorted filenames so repeated failures cannot starve later entries. Settings shows how many are awaiting relay acknowledgement. A full queue rejects another signed reset before committing a new marker. The saved endpoint policy is used, even if the new identity has different settings. Only a correlated TLS acknowledgement containing the proof hash deletes the pending intent. If no relay was configured, the local reset still proceeds without a delivery endpoint.

Android stores the intent under `noBackupFilesDir/revocations`; Mac under the isolated application-support directory's `revocations`; Windows under the current profile's MsgAT data directory's `revocations`. These public retry intents are outside the content-destruction list. Debug client paths remain isolated from release data. No real identity has been reset as part of this source/build checkpoint.

## Recovery when the old identity cannot be opened

If opening an existing identity fails before the vault and network workers start, the startup error offers a separate, confirmed local reset. Cancel preserves the existing data. Confirm durably records a local-only intent and exits. The next process completes the same idempotent hardware-key and file removal before it can provision a new identity. Windows relaunches automatically. On Android and Mac, reopen the app to continue.

The local-only intent carries no proof or endpoint. The confirmation explains that the old relay identity cannot be revoked without its signing key. An already committed reset marker takes precedence over this recovery action, so an interrupted signed reset keeps its original proof. A first launch on unsupported hardware still fails at the mandatory hardware check.

On macOS, Keychain access and database-key unwrapping failures now reach the secure-storage startup screen. A missing envelope for an existing vault preserves that vault. Android distinguishes missing hardware protection, an unavailable security component and an identity or vault open error in the selected interface language.

## Exact cancellation proof

The shared native core implements `create_revocation` and `verify_revocation`. It signs this fixed-width byte sequence with the existing Ed25519 root:

- Domain: ASCII `MsgAT/v2/revoke-identity` followed by one zero byte.
- Signed header: version byte `2`, root public key (32 bytes), independently random token (16 bytes).
- Wire proof: that 49-byte header followed by its Ed25519 signature (64 bytes), total **113 bytes**.

The random token makes independently prepared intents distinct; replay of any valid proof is deliberately idempotent. There is no wall-clock expiration: an offline reset must remain deliverable after destroying the key. Possession of this proof authorizes permanent cancellation of only its signed root. It is never a login credential. Host storage uses Base64; wire transport carries exactly the 113 binary bytes. The verifier returns the root-derived account ID and SHA-256 of the complete proof.

## Relay behavior

A TLS connection may submit `0x4A` (`V2_REVOKE`) before account authentication. This exception is necessary because the signing root has already been destroyed. The server verifies the fixed purpose/signature, derives the identity itself and commits a permanent tombstone and its original public signature proof to `msgat_v2_revocations.db` with SQLite WAL and `synchronous=FULL`. It never trusts a caller-supplied target identity. Each connection handles one cancellation and closes. A global request budget limits this public endpoint to 32 attempts per second; the tombstone database has a 256 MiB page budget. Exhaustion/failure produces no success acknowledgement, so the client retains its intent.

After durable commit, close all tracked control/bulk channels of that identity. New authentication and active work are ordered against revocation by a fair read/write gate. Revoked roots cannot authenticate, republish a bundle or upload objects. Ciphertext addressed to a revoked root is rejected; offline replay filters revoked senders. Directory claims for a revoked target return `found:false, revoked:true`, including when an earlier reservation response was cached; request-ID target binding is still checked. New uploads and downloads of objects owned by a revoked root are blocked before background deletion finishes. Up to eight authenticated sockets are tracked per root.

Reply `0x4B` (`V2_REVOKED`) echoes the request nonce and contains SHA-256 of the complete proof. It acknowledges permanent revocation, **not completion of all file deletion**. Every five seconds, cleanup advances up to eight due identities, at most 1000 directory keys and 1000 claim records, 1000 queued envelopes and 16 attachment objects per identity. Unfinished work rejoins the queue after work already due. An I/O or database error defers that identity with backoff up to five minutes while the other identities in the batch continue. The schedule survives restart, and repeating a signed revocation does not change its cleanup schedule. Cleanup removes directory registrations/reservations, envelopes to or from that root, and the root's owned encrypted objects. Permanent tombstones remain after cleanup. Server shutdown drains connection/application/bulk workers and scheduled cleanup before closing stores.

## Limits and acceptance still required

The latest recovery and queue changes have yet to be built. Actual reset/reopen, local-only recovery, offline intent delivery, rotating delivery across relays, repeated ACK/retry, interrupted-reset recovery, tombstone persistence and attempted old-identity authentication require genuine-client acceptance. Initial terminal sender feedback requires the root-signed proof in a key-claim response or `0x4C` submit rejection, verifies its root ID and shows an unconfirmed-delivery error on all three clients. See [V2_PROTOCOL.md](V2_PROTOCOL.md).

Removing keys and application-managed files does not erase recipient copies, user exports, SSD physical remnants, system snapshots or all historical backups. Mac's unsigned-app-compatible Secure Enclave representation is device-bound, but deleting its opaque key blob does not invalidate a previously copied blob on the same hardware; this is a boundary of the approved ordinary-device design, not a claim of hardware-enforced revocation of every historical copy. A fully controlled operating system remains outside the user-approved threat model. Server tombstones cannot force an actively malicious server administrator to erase their own copies; message and attachment contents remain end-to-end encrypted.
