# V2 reconstruction status — 2026-11-08

This is an unfinished reconstruction on `codex/v2-secure-rebuild`. It is not a complete replacement for the published V1 release. No V2 release, main-branch merge or repository visibility change has been made. The complete approved scope remains in [RECONSTRUCTION_PLAN.md](RECONSTRUCTION_PLAN.md); this checkpoint does not reduce that scope.

## Implemented source

| Component | Current implementation | Acceptance boundary |
| --- | --- | --- |
| Shared security core | Rust 1.98.1; libsignal v0.106.2 at commit `850942581c06fa65c0323fd52029afa02fa1a1ba`; root-signed identity/bundles; protocol snapshots; prekey refresh; bounded C ABI 2 | Native libraries built for Mac, Windows, Android ARM64/ARMv7. All peer directions still require actual-client acceptance. |
| Android | Verified TEE/StrongBox protection; root-owned database-key envelope; SQLCipher/Room transactional Signal store; serial V2 contact/text/reply/receipt coordinator; bounded JNI/transport; private notifications | Native libraries, Kotlin/resources, lint and debug APK have passed hosted CI. The new live coordinator has not been accepted on a physical device. |
| Mac | Secure Enclave seed wrapping; root-owned SQLCipher key; transactional protocol store; live V2 contacts/text/replies/receipts/profile events; native composer Return/Shift+Return | Actual independent Mac clients exchanged messages and receipts, persisted sessions and received offline messages across client/relay restarts. Remaining content/call/UI work is incomplete. |
| Windows | TPM RSA-OAEP/SHA-256 plus AES-GCM identity vault; root-owned SQLCipher key; bounded native bridge; transactional Signal store; .NET 10 LTS WPF build | Actual TPM startup/reopen passed on Parallels virtual TPM. New protocol storage and native DLL compile on hosted Windows. Live V2 coordinator/UI wiring is still pending. |
| Java relay | One-use timed root challenge; bounded signed key directory; durable V2 ciphertext queue; bounded replay/write queues and expiry; removal of debugger-triggered destructive KillSwitch | JAR packages with ordinary JVM verification and starts successfully. Actual Mac clients used its directory and durable ciphertext delivery. Auth/session binding, directory epochs, abuse controls and full cutover remain unfinished. |

The root remains a 32-byte private seed. Database keys are random and wrapped using the root HKDF hierarchy; protocol identity is root-bound, while ratchets/prekeys retain their required independent randomness. V2 databases use separate paths and reject plaintext substitution. Root material is not exported through an account migration/recovery flow. Full Reset Identity lifecycle is not yet complete.

All SQLCipher configurations explicitly request `synchronous=FULL`; Mac additionally requests fullfsync/checkpoint_fullfsync. This final configuration compiled, but its crash-durability behavior has not been established by an actual power-loss exercise. No mock security backend or new test harness was introduced.

## Actual use already observed

Two genuine Mac debug applications, using separate hardware-protected identities and native UI, ran against an isolated TLS relay with synthetic content:

- Created profiles, published signed bundles, requested/accepted a contact, and exchanged Unicode text in both directions.
- Displayed delivered/read status and an unread counter that cleared when the conversation was viewed.
- Quit/reopened the sender and restored its existing vault/session.
- Sent two messages with the recipient closed. The relay durably retained two 458-byte ciphertext envelopes across a graceful relay restart.
- Reopened/unlocked the recipient, decrypted both queued messages, displayed unread count two, and cleared that count on viewing. All four conversation messages remained visible.
- Returned read receipts for both offline messages after the sender manually reconnected through Settings.
- Confirmed unmodified Return sends and Shift+Return inserts a newline without breaking marked-text input-method handling.

Actual use exposed a Network.framework `.waiting` reconnect bug. The source now schedules bounded retry for that state and compiled; automatic recovery for this specific fix has not yet been accepted in the app.

Mac permanent Secure Enclave Keychain items initially failed with unsigned-app entitlement error `-34018`. The user has no Developer certificate. The implemented alternative uses CryptoKit SecureEnclave.P256.KeyAgreement opaque device-bound key data, ECDH/HKDF/AES-GCM seed wrapping and an encrypted Keychain envelope. Actual startup and Quit/reopen succeeded. This does not claim all temporary managed-memory copies are zeroized. Ad-hoc application updates can require a new Keychain confirmation; the user manually approved both QA applications. Computer Use rejected controlling SecurityAgent, and no automation bypass was used.

The actual Windows WPF app refused unsupported TPM P-256 operations, then succeeded using TPM RSA-OAEP/SHA-256. Reopening preserved the vault bytes. This is a Parallels virtual TPM: physical-machine behavior, host-independent VM non-clonability and same-user process isolation are not established.

## Hosted compilation and source checkpoints

The latest supplied project instructions restore GitHub-hosted builds and packaging. No local build was run in this continuation. Both workflows now generate the native core from locked source before compiling applications; no generated library is committed or uploaded from the developer computer. Protobuf 3.25.5 downloads have pinned platform-specific SHA-256 hashes. Android uses NDK 26.3.11579264 and 16 KiB ELF LOAD alignment. Windows explicitly selects MSVC tools to avoid Git's unrelated Unix `link.exe` and preserves Windows environment-variable lookup semantics.

- `cef5e9b`, [CI 42480183171](https://github.com/TirededMaxer/MsgAT/actions/runs/42480183171): Android, Mac and Java relay passed. Windows exposed the linker PATH collision.
- `6d61bc2`: added Windows transactional Signal storage and began the linker fix.
- `181b339`: moved Windows to .NET 10 LTS, with Microsoft.Data.Sqlite.Core and ProtectedData pinned to 10.0.14, and corrected the MSVC environment lookup. Strict analysis found two storage-code issues.
- `753a717`, [CI 42481526615](https://github.com/TirededMaxer/MsgAT/actions/runs/42481526615): fixed both analysis issues. All four hosted jobs passed: Android native/Kotlin/resource compilation, lint and debug APK; Mac native/Xcode Release build; Windows native compilation and .NET 10 WPF publish; Java relay package.

CI compiles applications/resources and runs Android lint. The old V1 test suites are not used as evidence for V2 security or runtime behavior. Successful compilation/publish is not actual GUI or interoperability acceptance. Android release signing must retain the original certificate; credentials remain in GitHub Secrets and the offline backup, never in source/logs/artifacts.

## Current cleanup state

All temporary Mac QA application copies and the isolated relay were stopped; process exits and closed relay port were checked. Their temporary app/JAR/certificate/server-database staging was removed. Windows host/guest staging was removed and the VM stopped. No validation client or temporary relay remains running.

Thirteen project-local build/cache roots were removed after hosted compilation became available: Gradle/Kotlin intermediates, Android build/JNI outputs, Swift build/static-library outputs, Maven target, Rust target, Windows bin/obj, and the temporary Protobuf tools directory. Source, resources, lockfiles, real runtime profiles and the offline signing-key backup were preserved. Shared global toolchains/caches belonging to other projects were not treated as MsgAT build output.

Encrypted QA profile data under `org.msgat.mac.development` and its `.qaone`/`.qatwo` identities, corresponding Keychain items/defaults, and the Windows development profile remain only for normal-upgrade/session continuity acceptance. Remove these test identities at final cleanup. Do not incidentally erase production profiles. Generated QA crash reports may also need final cleanup.

## Next work and mandatory completion gates

1. Accept the new Windows identity-envelope handling: ordinary settings snapshots no longer contain root/database key fields, the cached identity remains TPM ciphertext, and operations unlock short-lived byte buffers. The persisted v3 shape preserves existing roots and wrapped database keys on an ordinary upgrade. Hosted compilation and real upgrade/reopen acceptance for this change remain pending. Hardware binding is not proof of application/process isolation; resolve the same-user TPM/CNG access and native-library loading boundaries before security sign-off.
2. Wire the Windows coordinator and native bridge into real contact/text/receipt UI actions. Complete actual Android/Windows exchange and all peer directions. Android database singleton initialization and lazy-open failure handling also need review before device acceptance.
3. Domain/version/session-bind authentication; implement directory epochs and replenishment so a fresh directory cannot reassign already-consumed keys. Finish admission/rate controls, revocation, server configuration/resource budgets and retirement of legacy server processing/storage keys.
4. Finish encrypted media, files, thumbnails, avatars, drafts/search and attachment transfer. Replace all legacy media/edit/revoke/call/account paths; Android and Mac no longer accept legacy business packets. Windows legacy messaging is still active pending its coordinator cutover.
5. Finish offline, crash-recoverable Reset Identity and cryptographic erasure across all platforms. Never use regeneration to hide an unlock failure.
6. Complete official Telegram package/source/UI research, three native interfaces, desktop interactions, links/previews, supported local sticker imports, groups, message operations, calls and background delivery from the full approved plan.
7. Accept real use of the three native clients and server. Reproduce remaining connection, UI, media/call and lifecycle cases; build success alone does not close these gates.
8. Build/package all four components on hosted runners, verify original Android signing and remote asset hashes, update docs, deliver only into Downloads/MsgAT, and retain only the newest successful Release while preserving tags/history. Only after full completion, actual acceptance and delivery, change the repository to private.

No production data has been reset. No V2 package has been released.
