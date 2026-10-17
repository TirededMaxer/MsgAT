# Contributing to MsgAT

## Development rules

1. Keep visible product copy in English unless a localization resource is added for every supported client.
2. Never silently replace an identity, key, or database after a storage or decryption error. Fail closed and surface a recoverable error where possible.
3. Treat packet constants and JSON payload fields as a shared protocol. Update all clients and the relay together.
4. Avoid process-wide unmanaged coroutine scopes, retain cycles, and event-loop leaks. Every long-lived resource must have an explicit shutdown path.
5. Keep secrets, generated packages, runtime databases, IDE state, and signing material out of Git.

## Verification

Use the GitHub-hosted CI and Cloud packages workflows documented in `docs/BUILDING.md` for every area changed. Do not rely on locally retained build outputs or dependency caches. For protocol, storage, calling, or identity changes, also test a real upgrade from the previous release and run two-client interoperability checks on actual devices or VMs.

Pull requests should explain user-visible behavior, data migration impact, security impact, and exact test coverage. Do not merge with failing compiler, lint, or test output.
