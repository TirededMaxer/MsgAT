# MsgAT project workflow

- Latest user coding instruction (2026-11-10): write the approved code directly. Do not run independent source reviews, syntax/configuration checks or repeated verification. Inspect and review for diagnosis only when the later compilation reports errors. Necessary reading of existing APIs to implement the next feature is allowed.

- Latest user gate (2026-11-10): complete all approved implementation, then pause the active goal. Do not start builds, tests, device installation or acceptance until the user says preparations are complete and resumes that phase. This is a conditional pause after all coding, not permission to pause with required modules unfinished.

- Latest user sequencing (2026-11-09): stop current testing and close QA clients. Finish the remaining implementation first; build and perform actual-client acceptance together only after the approved code scope is complete. Do not resume incremental UI automation, device installation or runtime checks before that gate. Keep test identities/data intact for the later unified acceptance. Production relay inspection does not authorize replacing or restarting it during this implementation phase.

- Latest user-supplied workflow (2026-11-09) requires all four components to build/package on GitHub-hosted runners. This supersedes earlier local reconstruction-build exceptions. Do not push unfinished changes, start cloud builds, or upload Release packages while implementation is unfinished; prepare the completed source before the unified hosted build/acceptance stage.
- Scope update (2026-11-18): the user canceled group chat and removed it from the project roadmap. Resume any group-chat design, implementation or acceptance only when the user explicitly requests adding it. Keep single-chat, media/files, one-to-one calls and the remaining approved work in scope.
- Chat camera scope (2026-11-11): provide photo capture and video recording on Android only. On Windows and macOS, keep existing photo/video import and sending, along with voice-message recording and one-to-one calls.

- After the entire reconstruction, real-client acceptance and package delivery are complete, change the GitHub repository to private. Do not change visibility before that final step.
- Identity creation requires verified hardware protection: Android TEE/StrongBox, Mac Secure Enclave, Windows TPM. Fail closed with a visible startup error; do not introduce a software fallback.
- Close and remove unused reconstruction clients and temporary application staging promptly. Keep only clients currently needed for actual-use acceptance; preserve real user data and required build caches.
- The user approved libsignal and AGPL distribution requirements for clients. Preserve the server's MIT licensing and document the component boundaries.
- Media lifecycle invariant: register local object staging before file creation; commit object metadata, message/saved ownership and staging removal in one transaction. Every new media-owning table must be included in `media_object_references` on all three clients before background cleanup can ship. Keep the per-vault process lease for the lifetime of database ownership, and drain operation/maintenance owners before destroying identity protection or closing the vault.

- Build and package all four components on GitHub-hosted runners. Use the CI and Cloud packages workflows in `.github/workflows/`; see `docs/BUILDING.md`.
- Do not run Gradle, Maven, dotnet builds, SwiftPM or Xcode builds on the developer's computer. Do not recreate local dependency caches or package directories. Local source inspection and lightweight script/configuration checks are allowed.
- Keep source, resources, dependency lockfiles, build scripts, licenses and documentation locally. Do not delete real runtime data or the offline signing-key backup as build-cache cleanup.
- Android release packages must retain the existing certificate. Signing credentials belong in GitHub Actions Secrets, never in source, logs or build artifacts.
- Verify all four packages and remote asset hashes before publishing. Keep only the newest successful GitHub Release; preserve Git tags and commit history.
