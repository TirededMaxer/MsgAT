# V2 encrypted web previews

Status: source implementation, 2026-11-14. The build and actual-client acceptance follow the approved coding phase.

## User behavior

The composer can place the preview above or below the message text and switch a cover between a large image and a thumbnail. Existing messages default to a large cover below the text. The composer applies these options to the current draft and reuses the fetched metadata and cover. Scheduled sends, forwarding and saving retain both options through the encrypted preview metadata. [Telegram adjustable link previews](https://telegram.org/blog/reply-revolution#adjustable-link-previews)

Each composer has an encrypted, local **Link previews** preference. The shared rich-text parser lists supported HTTP(S) URLs in message order, excluding spoilers and code. It recognizes scheme names regardless of case and treats supported `www.` addresses as HTTPS. The first URL is selected by default. The link picker selects another URL or restores a removed preview. Only the selected URL is fetched, after a 600 ms debounce. Switching or removing it cancels the pending request, and editing text around an unchanged selection keeps the current request. A message carries at most one preview. Sending before it is ready, or after a failed fetch, still sends the text immediately.

The draft card shows the destination host, title, summary, optional cover and target URL before sending. It indicates a pending or unavailable preview. Received and saved cards identify the destination by the host parsed from the same URL used for navigation. Clicking a card opens its URL with the default browser. Recipients render the title, summary and cover from the encrypted message and object.

When a draft preview is unavailable, all three composers offer a retry for the selected URL. Retrying preserves the draft, preview placement and cover-size choice. Sending before the retry finishes still queues the text.

The sender's request exposes its network address and selected URL to the website. Disabling the preference prevents automatic fetches; this feature does not claim anonymous browsing or use the chat relay as a URL proxy.

## Fetch implementation

The shared Rust core owns the destination policy, passive metadata scanner and event validation, with no new Rust dependency. Platform code performs HTTP:

- Windows uses .NET `SocketsHttpHandler.ConnectCallback` and connects only to the previously checked addresses. Redirect following, cookies and proxies are disabled. [Microsoft connection callback](https://learn.microsoft.com/en-us/dotnet/api/system.net.http.socketshttphandler.connectcallback)
- Android uses the existing Netty HTTP codec and bounded aggregator, connecting to resolved `InetAddress` endpoints with a TLS engine configured for the original hostname and HTTPS endpoint identification. It does not share the relay's development-certificate policy. [Netty HTTP codec](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpClientCodec.html)
- Mac links the operating system's libcurl through a small C target. `CURLOPT_RESOLVE` pins checked addresses while retaining normal hostname TLS verification. It uses no command-line subprocess, cookies, netrc credentials or proxy environment settings. Xcode and SwiftPM both include the target. [libcurl resolution](https://curl.se/libcurl/c/CURLOPT_RESOLVE.html), [proxy configuration](https://github.com/curl/curl/blob/master/docs/libcurl/opts/CURLOPT_PROXY.md)

Every initial page, redirect and cover destination passes the same policy. It accepts only ASCII HTTP(S) URLs without credentials, scoped addresses, escaped authorities or backslashes. Local/single-label names and private, loopback, link-local, multicast, documentation and other reserved address ranges are refused. All returned DNS addresses must pass; the connection uses that checked set rather than resolving the host again. HTTPS redirects cannot downgrade to HTTP.

Limits: two concurrent fetch workers; at most three redirects per resource; 1 MiB HTML; 2 MiB downloaded cover; bounded response headers; a 12 second operation budget, checked before subsequent network hops. Native synchronous DNS on Android/Mac can finish after cancellation or this deadline; it is confined to bounded workers, and canceled work does not proceed to a new fetch. The UI does not wait for DNS on its main thread.

The metadata scanner reads tags and attributes without executing a DOM, JavaScript, CSS or embedded content. It extracts Open Graph/Twitter/plain title and description, site name and a cover candidate. Control and direction-override characters are removed. Title, summary and site UTF-8 limits are 512, 2048 and 256 bytes. The original message URL remains the navigation target; an `og:url` value cannot replace it. Covers are decoded with dimension limits and normalized to a JPEG no larger than 512 pixels on the longest side and 512 KiB. No page/cover plaintext disk cache is created.

## Encryption and durable ownership

`chat.text.body.preview` is optional. Its required fields are `url`, `title`, `summary`, `site` and `image` (an encrypted-object manifest or null). The sender writes optional boolean `above_text` for placement above the text and `compact_image` for a thumbnail. Recipients using either option need a version whose validator recognizes the respective field. The URL must match a visible address parsed from the message text, including HTTPS expansion for supported `www.` addresses. Shared event validation runs before host application. The preview metadata, manifest, content key and text are inside the Signal envelope.

Only a completed preview selected for sending enters the object importer. The cover import belongs to the messaging coordinator's existing shutdown owner, with staging before encrypted-file creation. The message, pending event, preview metadata, cover relationship and upload job commit in one SQLCipher transaction. Readiness filtering holds the event until every referenced object has a sufficient lease on the current relay connection.

`message_previews` stores message or saved-item ownership, placement and cover size. `web_preview_preferences` stores the local on/off preference. The central reference view includes preview images. Message deletion, edits that change the body, and revocation remove the message's preview relationship. Transfer pins retain an active operation until it finishes, then normal ciphertext garbage collection applies. Status/receipt-only updates preserve the preview.

Forwarding and saving capture the preview with the source snapshot. Its contents participate in queued-source fingerprints and commit-time source checks. Each recipient or saved item receives a newly keyed cover object, with copy-space accounting and failed-copy cleanup. A missing cover waits through the existing persistent forward queue; a saved preview remains independent when the original message is edited, revoked or cleared. Deleting a saved item releases its own cover.

The forward confirmation offers an explicit option to omit link previews. That option skips cover downloads and copies for the whole batch while preserving the original message text and links. Saving a message keeps the preview text and copies the cover if it is already local. A missing cover does not prevent saving the message.

No preview is regenerated after receiving or forwarding a message, and editing text currently removes an obsolete preview rather than fetching a new one in the background.

If a received preview still has a pending cover transfer, the original chat card can request, pause, resume or retry that transfer. The card shows the queue state or progress separately from the available action. Each change checks that the message still owns the exact cover and updates only that encrypted cover transfer.

## Unified acceptance still required

After the coding pause is lifted, use the clients to verify all client directions, composer and received card layouts, placement and cover-size preservation, default-browser navigation, global disable and single-preview removal, normal text on fetch failure, cancellation and chat switching, redirects and rejected private destinations, offline/lease recovery, missing or corrupt covers, forwarding, saved-item independence, edits, revocation, history clearing and shutdown/reset.
