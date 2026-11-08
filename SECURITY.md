# Security Policy

## Reporting a vulnerability

Do not open a public issue containing exploit details, private keys, message contents, server addresses, or user identifiers. Use the repository host's private security-advisory feature and include affected versions, reproduction steps, impact, and a minimal proof of concept.

## Supported version

Only the latest release line is intended to receive security fixes. There is not yet a published stable security-support commitment.

## Current boundaries

The paragraph below describes the existing published release. V2 reconstruction is in progress; see docs/REBUILD_STATUS.md. Its source now introduces root-owned local data-key envelopes, device-only Mac seed wrapping, authenticated TLS defaults and a pinned libsignal security core. Mac contacts, text and delivery/read receipts now use the shared core and V2 ciphertext relay; Android and Windows live-chat integration remains incomplete. Windows now defaults to TPM protection, Android verifies TEE/StrongBox, and Mac requires Secure Enclave. These paths have compiled. Mac actual startup and relaunch now succeed using CryptoKit device-bound opaque key data plus an encrypted root envelope in Keychain, without requiring a Developer certificate. Windows actual startup now succeeds with a TPM RSA-OAEP/SHA-256 wrapper after its TPM provider rejected P-256 ECDH. This was checked on a Parallels virtual TPM, not physical Windows hardware. Media encryption, complete platform session integration and actual-client acceptance remain outstanding. Do not treat partial source changes or successful compilation as a completed security upgrade.

MsgAT is under active development and has not received an independent security audit. Android, Windows, and macOS use SQLCipher for message data at rest; Windows and macOS include atomic migration for earlier plaintext databases. Relay TLS compatibility is enabled by default and accepts untrusted certificates without authenticating the server. Users can opt into system trust or certificate pinning in settings. Identity vault failures must never be worked around by deleting or regenerating credentials without explicit user action.
