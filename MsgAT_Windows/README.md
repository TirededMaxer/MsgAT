# MsgAT for Windows — V2 reconstruction

The native Windows client uses .NET 10 LTS, WPF and WebView2-hosted WebRTC. This branch is unfinished; the published V1 download remains a separate release. See [reconstruction status](../docs/REBUILD_STATUS.md) before using this source.

## Build and package

Use the GitHub-hosted CI and Cloud packages workflows described in [BUILDING.md](../docs/BUILDING.md). Do not build locally or reuse local binaries. The workflow installs the SDK, builds the locked Rust/libsignal core with MSVC, stages its x64 DLL, and publishes the self-contained application. Native DLL extraction and actual V2 operation still require acceptance; a successful publish is not runtime evidence.

## Identity and local data

V2 requires a working TPM through Microsoft Platform Crypto Provider. It uses a nonexportable RSA-OAEP/SHA-256 hardware key to wrap an AES-256-GCM identity vault; there is no software-key fallback. A successful private TPM operation is required before creating an identity. The unchanged 32-byte identity seed owns the HKDF hierarchy used to wrap the random SQLCipher database key.

Release data is scoped to `%LOCALAPPDATA%\MsgAT`; Debug uses its `development` subdirectory. The new database is `msgat_v2_vault.db`. A plaintext replacement at this path is rejected; it is not silently imported. Protocol sessions, prekeys, pending events, exact outbox ciphertext and inbox deduplication share the encrypted database and transaction boundary. Contacts, text, quoted replies and delivered/read actions are now wired through the V2 actor; actual upgrade and interoperability acceptance is pending. CI provides a development EXE using the isolated Debug profile for that acceptance.

Ordinary settings snapshots no longer contain identity seed or database-key fields. The identity cache remains hardware ciphertext; operations unlock temporary byte buffers and clear them after use. The existing v3 vault layout preserves the prior root and wrapped database key on ordinary upgrades. The old injectable software protector and obsolete plaintext/software-vault migration tests were removed. This reduces long-lived plaintext references; it does not promise zero copies throughout the managed runtime.

There is no seed export, account recovery or identity migration workflow. Existing vault corruption must fail closed. Hardware binding has been checked on the Parallels virtual TPM for startup/reopen, but does not establish physical-machine behavior, host-independent VM non-clonability or same-user process isolation. Those remain distinct acceptance requirements. Reset Identity lifecycle is also unfinished.

V2 TLS defaults to normal certificate validation; an explicit certificate pin is supported. Untrusted-certificate compatibility requires an explicit setting and is not the new default. Do not use the old published client's defaults to infer the V2 policy.
