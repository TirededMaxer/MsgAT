# V2 encrypted profiles

Status: source implementation; not built or accepted. Keep the current implementation-only gate. This does not mark the entire contacts or UI module complete.

## Own profile

All three clients store their own nickname, avatar object relationship and monotonically increasing revision in SQLCipher `user_profiles`. Android SharedPreferences and Mac UserDefaults retain no new nickname or avatar path: a safe decrypted snapshot stays in RAM. Windows keeps its existing encrypted settings snapshot for current UI compatibility, with the SQLCipher row as authority.

Initial setup accepts a nickname with an optional picture. Platform image decoders limit dimensions and normalize selected avatars to JPEG, at most 512 pixels on the longest side and 512 KiB. Normalized bytes enter the existing chunk-encrypted object importer directly from RAM. This does not create a second plaintext avatar file. Failed imports retain the same staging and recovery rules as message attachments.

After the profile and outgoing events commit together, accepted, unblocked contacts receive the new profile. Three native profile editors expose changing the nickname, replacing the photo and explicitly removing it. With no new photo or removal selected, saves keep the current photo. The editor groups the circular preview and photo actions above the name field. The form scrolls separately from the Save and Cancel controls. Save stays disabled while the operation is running, and the editor shows a failure in place so the user can retry with the same input.

The settings overview shows the current profile and a copy button for the public UUID. Edit profile, network, privacy and storage have separate entries. The Android overview also links to background reception settings. The network form scrolls independently while save and cancel remain visible, including when advanced fields are expanded. Profile edits use the existing encrypted profile coordinator. Identity reset retains its confirmation flow.

## Wire format and relay

`contact.request`, `contact.accept` and `contact.profile` carry `nickname`, `revision` and `avatar` (an encrypted object manifest or `null`) inside the normal Signal envelope. The shared parser accepts JPEG/PNG manifests up to 512 KiB and unsigned 32-bit, nonzero profile revisions. Nickname-only development events remain readable, as revision zero.

The relay stores ciphertext and opaque object bytes; it does not receive the nickname or the avatar's content key in plaintext. Avatar transfer uses the existing authenticated bulk object channel. Manifest events are held until the current relay session has an adequate object lease, including on reconnect. Several profile events referencing one object reuse the current upload lease.

`object_transfers` replaces message-only `media_transfers`. Each task has an event ID, object ID, owning peer, owner kind (`message` or `profile`), creation time and direction. Profile images do not create chat messages. Existing development transfer rows migrate transactionally through their message owners; malformed orphan rows cause startup failure instead of silent data loss.

Received profile revisions cannot move backwards. An equal positive revision keeps the first committed content. Accepting a request reads the current stored profile rather than trusting an earlier UI copy. Authentication, revocation, contact acceptance and block checks remain in the messaging coordinator.

## Display and lifetime

Windows decodes avatars on bounded workers into WPF image brushes. Mac reads encrypted bytes through its existing bounded media worker. Android uses a RAM painter, bypassing Coil's disk cache for encrypted references. A completed download refreshes waiting views; the image file path is never exposed as a decrypted temporary URL.

Windows updates the author label on existing quoted messages when the contact name changes. The folder editor updates its visible contact names and reapplies any current search while keeping selected members. On Mac, a temporary vault read error keeps the last displayed contact profile until a subsequent refresh.

`media_object_references` includes `user_profiles`, message attachments, saved items, transfer tasks and active transfer pins. Superseding a profile releases the old incoming job; its active transfer pin protects any file operation until it finishes. Pin release participates in normal garbage collection. Prior-process pins are recovered using the vault's exclusive process owner. Pending outgoing events continue to own the object until relay acknowledgement.

The initial compatibility pass moves known own-profile metadata into the encrypted vault and removes only known application-owned legacy avatar files. It does not delete source images chosen from the user's library.

## Remaining work and acceptance

Android and Mac crop handoffs now return normalized JPEG bytes in RAM directly to the profile coordinator. Android no longer launches UCrop or writes its output under cacheDir; its dependency and activity registration have been removed. The native Compose crop surface uses secure flags, safe drawing insets, a viewport fitting available space, pan/zoom, bounded input/decode and explicit confirmation. Mac keeps its native SwiftUI crop interaction, loads a bounded thumbnail on the media worker, and returns Data instead of writing a temporary JPEG. Windows already returns its crop bytes in memory. Chosen originals remain unchanged; crop pixels and buffers are released when their owner closes. This describes the application path, not an assertion about OS-managed image-codec memory or caches.

The new profile editor and crop screens await the unified build and visual comparison, including the Android crop toolbar with the status bar visible.

The unified real-client acceptance must cover initial setup, re-open, changing/removing an avatar, request/acceptance, all client directions, delayed downloads, reconnect, reordered revisions, replacement during a transfer, shutdown/reset and recovery. No new test harness or local build was added for this source change.
