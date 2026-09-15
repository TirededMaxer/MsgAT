# Security Policy

## Reporting a vulnerability

Do not open a public issue containing exploit details, private keys, message contents, server addresses, or user identifiers. Use the repository host's private security-advisory feature and include affected versions, reproduction steps, impact, and a minimal proof of concept.

## Supported version

Only the latest release line is intended to receive security fixes. There is not yet a published stable security-support commitment.

## Current boundaries

MsgAT is under active development and has not received an independent security audit. Android, Windows, and macOS use SQLCipher for message data at rest; Windows and macOS include atomic migration for earlier plaintext databases. Relay TLS compatibility is enabled by default and accepts untrusted certificates without authenticating the server. Users can opt into system trust or certificate pinning in settings. Identity vault failures must never be worked around by deleting or regenerating credentials without explicit user action.
