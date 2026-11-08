# MsgAT project workflow

- After the entire reconstruction, real-client acceptance and package delivery are complete, change the GitHub repository to private. Do not change visibility before that final step.
- Identity creation requires verified hardware protection: Android TEE/StrongBox, Mac Secure Enclave, Windows TPM. Fail closed with a visible startup error; do not introduce a software fallback.
- Close and remove unused reconstruction clients and temporary application staging promptly. Keep only clients currently needed for actual-use acceptance; preserve real user data and required build caches.
- The user approved libsignal and AGPL distribution requirements for clients. Preserve the server's MIT licensing and document the component boundaries.

- Build and package all four components on GitHub-hosted runners. Use the CI and Cloud packages workflows in `.github/workflows/`; see `docs/BUILDING.md`.
- Do not run Gradle, Maven, dotnet builds, SwiftPM or Xcode builds on the developer's computer. Do not recreate local dependency caches or package directories. Local source inspection and lightweight script/configuration checks are allowed.
- Keep source, resources, dependency lockfiles, build scripts, licenses and documentation locally. Do not delete real runtime data or the offline signing-key backup as build-cache cleanup.
- Android release packages must retain the existing certificate. Signing credentials belong in GitHub Actions Secrets, never in source, logs or build artifacts.
- Verify all four packages and remote asset hashes before publishing. Keep only the newest successful GitHub Release; preserve Git tags and commit history.
