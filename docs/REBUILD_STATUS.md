# V2 reconstruction execution status

The complete scope is defined in RECONSTRUCTION_PLAN.md. This ledger records implementation evidence and outstanding work; it does not redefine completion.

## Accepted execution decisions

- Started on branch codex/v2-secure-rebuild on 2026-11-08.
- The user authorizes local builds and temporary dependency restoration for this reconstruction; return to GitHub-hosted builds afterward.
- The user accepts libsignal and AGPL obligations for clients. The Java server remains MIT.
- Hardware protection is mandatory: verified Android TEE/StrongBox, Mac Secure Enclave and Windows TPM; unsupported devices cannot create identities and have no software fallback.
- Identity remains rooted in a 32-byte seed, with no export, transfer, recovery or same-identity multi-device login.
- After all implementation, actual-client acceptance and artifact delivery are complete, make the GitHub repository private. Visibility has not been changed.

## Current implementation work

- Added matching HKDF-SHA256 purpose derivation and AES-256-GCM data-key envelopes on all three clients. The V2 databases use separate filenames, preserving old runtime files until an explicit reset or deployment cutover.
- Android and Mac now obtain their SQLCipher keys from root-owned envelopes; Windows settings format 3 does the same and refuses automatic identity replacement when its vault is missing or unreadable.
- Removed the startup migrations that automatically enabled unauthenticated TLS; V2 defaults require certificate validation or a configured pin.
- Android explicitly excludes all data from cloud and device-transfer backup. Identity loss guards and reset key cleanup are being implemented. Mac now wraps the root seed with a device-only Secure Enclave key, keeps its opaque hardware blob outside the Keychain key-item mechanism, and removes the current blob/root envelope during Reset Identity. Actual startup and reopen passed. Android now verifies the Keystore security level (TEE/StrongBox), and Windows defaults to the TPM/CNG envelope. All platforms refuse hardware failures. Mac and Win11 virtual-TPM startup have now been exercised; Android physical-device acceptance and full reset behavior remain pending.
- Added an initial shared Rust/C ABI security core with root-signed libsignal identity bundles and ML-KEM-1024 prekey provisioning. The core now performs actual libsignal encrypt/decrypt operations with transactional state deltas, root-bound identity certificates and a bounded Protobuf wire envelope. Mac and Windows C ABI bindings compile; live platform integration is still outstanding.
- libsignal pinned for integration: v0.106.2, resolved commit 850942581c06fa65c0323fd52029afa02fa1a1ba (release tag object d5c8bb84459a46945273f11b8a7e067c5e872b33). Rust toolchain: 1.98.1.
- Windows core build passed with zero warnings and errors. Android Kotlin and resource compilation passed (existing audio API deprecation warnings). The new Mac key hierarchy passed Swift type checking.
- The shared Rust library compiled successfully with the fixed libsignal/SPQR sources. GitHub binary download 504s were worked around for the Protobuf compiler using the official Maven artifact; version 3.25.5, SHA-256 c990aaabeda2fe040ba6f9f93f719abfece30fd20bfb07ad094b14629f2c066b.
- Full Mac debug build passed after retrieving the official SQLCipher binary via the GitHub asset API and checking its package-declared SHA-256. The Secure Enclave code also compiled in the full application; the later actual-client hardware startup checks are described below. Full chat acceptance has not been performed.
- Repeatable locked native build entry point: python3 scripts/native/security_core.py. A local protoc path may be supplied with --protoc; cross-target builds require their actual linker environment.
- Debug clients now use separate real application identities/data: Android package org.msgat.development, Mac org.msgat.mac.development service and storage, Windows development storage/WebView2 profile and singleton. No separate mock client or synthetic test suite was introduced.
- Full Windows WPF cross-compilation, Android debug manifest/Kotlin compilation, and Mac SwiftPM compilation passed with those isolation paths. The Mac Xcode app also built successfully after adding the new source references and a separate debug bundle identifier.
- V2 production vault initialization rejects plaintext database substitution. Windows no longer carries its obsolete plaintext migration or Java Properties identity fallback.
- Server work has begun: removed the debugger-triggered destructive KillSwitch and its unused wipe helpers, added a 30-second one-use authentication challenge, retained authenticated public identity on the channel for the future key directory, and prevented one cleanup exception from permanently stopping scheduled expiry cleanup.
- Added a Windows TPM/CNG RSA-2048 OAEP/SHA-256 key wrapper with AES-256-GCM vault encryption and native key deletion for Reset Identity. P-256 ECDH was rejected by the real VM provider, so the implementation uses a supported TPM algorithm; there is no software private-key fallback. It is activated as the production settings default, validates the CNG key export policy, and supports a one-time in-place migration from old DPAPI storage. Hardware policy is confirmed; the actual WPF client now passes a real TPM private-operation check and reaches Setup Identity. Reopen and remaining runtime acceptance are tracked below. Windows settings no longer expose secrets through the record's default string representation.
- The current obfuscated server JAR packaged successfully and was actually launched with ordinary JVM verification, using an isolated data directory and temporary port 49378. It reached its listening state, shut down, and its temporary configuration/data were removed. This proves startup only, not V2 protocol acceptance.

## Immediate next work

1. Complete platform libsignal stores and live integration. The Mac SQLCipher store now atomically persists session changes, consumed keys, inbox effects and outbox ciphertext; implement equivalent Android/Windows hosts and prekey refresh/retention.
2. Bind the shared core into Android, Mac and Windows and replace the live legacy ECDH/fallback message paths. The existing chat code still uses those legacy paths today.
3. Continue server V2 integration. A root-authenticated signed bundle directory with bounded JSON parsing, transactional one-time key reservations, immutable key IDs and idempotent claim requests now compiles. Client publication/claim integration, abuse controls, revocation/expiry lifecycle and ciphertext-only relay remain pending. Retire legacy profile/friend payload decryption and the embedded storage-key design during cutover.
4. Hardware policy is activated. Finish full offline Reset Identity/cryptographic erasure, signing setup, and all remaining UI/media/group/call/acceptance phases from the full plan.

Current runtime checkpoint (2026-11-08): an initial Mac permanent Secure Enclave Keychain-item design was blocked by signing entitlements (-34018). The user has no Developer certificate and requested an alternative. Replaced it with CryptoKit SecureEnclave.P256.KeyAgreement device-bound opaque key data (284-byte blob, mode 0600, backup-excluded) plus ECDH/HKDF/AES-GCM wrapping of the unchanged root seed held encrypted in Keychain. The actual unsigned debug app now reaches Setup Identity and reopens successfully after Quit/relaunch; its SQLCipher vault is created with mode 0600. No software private-key fallback was introduced. The Win11 VM has a Parallels virtual TPM enabled (CIM reports enabled/activated); Get-Tpm itself returns a buffer-size error, so that command does not establish TPM readiness. The initial WPF app correctly refused unsupported TPM P-256 operations without creating an identity. Switching its TPM wrapper to RSA-OAEP/SHA-256 was then validated in the actual WPF app: a real TPM encrypt/decrypt capability check passes and Setup Identity appears. This is a Parallels virtual TPM; physical Windows hardware and host-independent VM non-clonability are not established. A temporary Windows staging directory is /Users/mac/Desktop/Transfer/MsgAT-v2-development-esl4ms3o; remove it after the actual-app check. No live client interoperability has been accepted, no Git commit/push/release has been made for V2, and repository visibility has not been changed.

## Outstanding completion gates

- Native device-bound identity protection and complete Reset Identity lifecycle.
- Encrypted database, media, thumbnails, search, drafts and protocol state with transactional recovery.
- Shared libsignal integration and removal of legacy public-key fallback encryption.
- Authenticated server key directory, durable ciphertext relay, attachment transfer and identity revocation.
- Three native Telegram-inspired interfaces, normal desktop interactions, link previews and supported local sticker imports.
- Groups, content operations, calls, background delivery and resource behavior from the full scope.
- Actual use of all three clients and server, including each peer direction; build success alone is insufficient.
- Four reproducible packages, documentation, hashes and distribution; only then the repository visibility change.

No production data has been reset, and no packages have been released for this reconstruction.

Additional build evidence: Android compileDebugKotlin, full WPF Debug (zero warnings/errors), Mac SwiftPM Debug and Xcode Debug application, and the obfuscated Java JAR all pass after current changes. Actual Mac startup exposed and fixed a debug UserDefaults crash before reaching the expected signing error. No new mock/unit-test harness was added.

Android Reset Identity now writes a durable pending-reset marker, deletes old databases, clears encrypted preferences/keysets and destroys the old Keystore wrapping key before creating a fresh hardware-backed identity. Startup resumes an interrupted erasure. Kotlin compilation passed; actual reset acceptance and offline UI flow remain pending.

Windows hardware persistence acceptance: after replacing unsupported P-256 with TPM RSA-OAEP/SHA-256, the actual WPF app reaches Setup Identity. Stopping/restarting the isolated app preserves the vault bytes (SHA-256 comparison equal), and the re-opened UI again reaches Setup Identity. The host stages are /Users/mac/Desktop/Transfer/MsgAT-v2-development-esl4ms3o and guest %TEMP%/MsgAT-v2-development-esl4ms3o; the running development client and its new hardware-backed development identity remain available for continuing reconstruction. No real user profile was used.

## 2026-11-08 V2 live-message implementation checkpoint

- Shared core now refreshes signed/ML-KEM/one-time prekeys with increasing IDs while preserving the existing root/protocol identity and registration ID. Mac and Android stores retain old receive keys for 15 days and persist counters separately from consumed keys.
- Added a V2 ciphertext-only WAL queue with commit-before-ACK, exact-ciphertext retries, authenticated recipient ACKs, bounded replay, byte/row-overhead budgets and scheduled expiry. It is separate from the legacy encrypted queue; legacy handlers are not yet retired.
- Mac now uses the real shared core, signed directory and V2 relay for contact requests/acceptance, text, delivery/read receipts and nickname profile events. Pending events, session changes, inbox effects and receipts are transactional. Text no longer uses the old ECDH/public-identifier fallback path. Media, edits/revokes, calls and Reset Identity still need their V2 cutover.
- Actual native Mac clients, with separate hardware-protected identities, successfully added each other, exchanged text in both directions and displayed delivered/read receipts. The receiver unread count appeared and cleared upon viewing. No test harness or mocked transport was used.
- Actual operation exposed the missing Enter-send behavior. The native composer now sends unmodified Return, preserves Shift+Return newline, and leaves marked-text input-method confirmation to AppKit. Actual Return sending and Shift+Return multiline composition passed after updating the existing two client copies in place.
- The sender reopened its existing encrypted vault/session and sent two more messages while the receiver was closed. The server persisted two 458-byte envelopes. The server was gracefully stopped and restarted with the same TLS certificate and database; both rows remain durable. After QA Two was manually authorized, it reopened and decrypted both queued messages; the unread count was two and cleared when opened. All four messages remained visible after restart.
- Mac unsigned/ad-hoc updates can trigger a Keychain access confirmation because the application signature changes. QA One was manually authorized and reopened. QA Two was subsequently manually authorized and reopened. Computer Use explicitly rejected controlling SecurityAgent; do not attempt an automation bypass.
- Android now has a bounded JNI adapter and Room/SQLCipher transactional protocol host. Native libraries built for ARM64 and ARMv7; exported JNI symbols and ARM64 16 KiB ELF segment alignment were checked. A debug APK containing both libraries assembled successfully. Kotlin compilation passed again after adding the store. Android live coordinator and physical-device acceptance remain pending.
- Implementation contract and unresolved security gates are in V2_PROTOCOL.md. Hardware binding alone is not proof of application isolation; Windows same-user process isolation and authentication transcript/domain binding remain mandatory gates.

Runtime cleanup: the two Mac QA app copies and relay were stopped, their process exits verified, and the entire temporary runtime directory (including app binaries, JAR, server databases and TLS certificate/private key) was removed. No validation clients or relay remain running. The unused base Mac application was also closed/deleted. Windows host/guest staging MsgAT-v2-development-esl4ms3o was deleted and the VM was stopped. Preserve actual user profiles; clean up all generated QA application/profile/certificate/data files at completion.

Final evidence from this live pass: the receiver decrypted and displayed the two offline messages after both relay and client restart. Read receipts for both messages reached the sender after manually reconnecting it through Settings. This exposed a pre-existing transport bug: Network.framework waiting states were logged but did not schedule retry when an endpoint was refused. The source now routes waiting through bounded backoff; Swift compilation passed, but this specific fix has not yet been deployed into the QA app for another actual-use check. Do not claim automatic reconnect acceptance yet.

All three SQLCipher configurations now explicitly require synchronous FULL for critical session/outbox commits; Mac additionally requests fullfsync/checkpoint_fullfsync. Builds passed. Android/Mac settings DTO defaults were also corrected to keep untrusted-certificate acceptance off unless explicitly enabled.

QA client profile data (encrypted database, device blob, preferences and corresponding Keychain items) remains scoped to org.msgat.mac.development.qaone/qatwo and the original development identity. These are retained solely for the next normal-upgrade/session-continuity check, and must be destroyed during final QA cleanup. The Windows development profile is likewise test-only and must be removed at completion; no production profiles are authorized for incidental cleanup.

## Android coordinator and hosted-build cutover

- Added the Android serial V2 coordinator and wired real contact, text, quoted reply and delivered/read actions to the transactional Signal store. Connection generations fence stale callbacks; bounded work/write queues close overloaded transports and retain durable messages for retry. This latest wiring has not yet passed compilation or actual-device use.
- Android notifications are emitted only after a newly accepted chat event commits. They contain no sender name or message body, stay local to the phone and are hidden on the lock screen. Notification permission failure cannot prevent the transport ACK. Actual notification behavior is pending device acceptance.
- Removed the Mac legacy business packet handlers, matching Android's V2-only receive path. Media, calls and other remaining operations need their new V2 implementations before complete application acceptance; there is no legacy decryption fallback.
- The latest supplied project instructions restore hosted-only compilation and packaging. No local build was run in this continuation. Both workflows now build the pinned Rust core first; Android creates both JNI targets, Mac links its static library, and Windows stages its native DLL for packaging. Protobuf compiler downloads have pinned platform-specific SHA-256 hashes. Hosted workflow execution remains to be verified.
- CI performs app compilation/resource checks; it does not run the old V1 test suites or substitute a mock security backend. No new test harness was added. Full acceptance remains actual use of the three native clients.
