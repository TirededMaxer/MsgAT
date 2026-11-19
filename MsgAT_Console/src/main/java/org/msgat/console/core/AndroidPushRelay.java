// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import com.google.crypto.tink.apps.webpush.WebPushHybridEncrypt;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import org.msgat.console.db.CiphertextMailboxStore;
import org.msgat.console.db.CiphertextMailboxStore.PushStatus;
import org.msgat.console.db.CiphertextMailboxStore.PushSubscription;
import org.msgat.console.db.IdentityRevocations;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Sends opaque, authenticated mailbox wake-ups to the operator's ntfy instance. */
public final class AndroidPushRelay implements AutoCloseable {
    static final byte[] CAPABILITY = "UP1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN = "MsgAT/v2/android-wake".getBytes(StandardCharsets.US_ASCII);
    private static final Logger log = LoggerFactory.getLogger(AndroidPushRelay.class);
    private static final Pattern TOPIC = Pattern.compile("/up[A-Za-z0-9_-]{12,62}");
    private static final Pattern GENERATION = Pattern.compile("[a-f0-9]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SUBSCRIPTION_SECONDS = 30 * 86400L;
    private static final int CONCURRENCY = 4;
    private final URI origin;
    private final String originKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY).build();
    private final Set<String> sending = ConcurrentHashMap.newKeySet();
    private final ExecutorService senders = Executors.newFixedThreadPool(CONCURRENCY);
    private final java.util.concurrent.ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    public AndroidPushRelay(URI origin) {
        this.origin = origin;
        originKey = origin.toASCIIString();
        poller.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
    }

    static URI parseOrigin(String configured) {
        URI uri = URI.create(configured);
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                !(path.isEmpty() || path.equals("/")) || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("pushOrigin must be an HTTPS origin");
        }
        int port = uri.getPort();
        return URI.create("https://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port));
    }

    private String topic(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !origin.getHost().equalsIgnoreCase(uri.getHost()) ||
                port(origin) != port(uri) || uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
                (uri.getRawQuery() != null && !"up=1".equals(uri.getRawQuery())) ||
                !TOPIC.matcher(uri.getRawPath()).matches()) {
            throw new IllegalArgumentException("Push endpoint is not on the configured distributor");
        }
        return uri.getRawPath().substring(1);
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    void handle(ChannelHandlerContext ctx, String identity, GhostPacket packet) throws SQLException {
        byte[] correlation = packet.getNonceOrIv();
        if (!identity.equals(packet.getTargetMailboxId()) || correlation == null || correlation.length != 16 ||
                packet.getPayloadLen() > 1400) { ctx.close(); return; }
        PushStatus status;
        String generation = "";
        try {
            var body = BoundedJson.parse(packet.getEncryptedData());
            generation = BoundedJson.string(body, "generation", 32);
            if (!GENERATION.matcher(generation).matches()) throw new IllegalArgumentException("Invalid generation");
            long now = Instant.now().getEpochSecond();
            status = switch (BoundedJson.string(body, "op", 16)) {
                case "register" -> {
                    BoundedJson.fields(body, "op", "generation", "endpoint", "publicKey", "auth", "wakeKey");
                    String topic = topic(BoundedJson.string(body, "endpoint", 1024));
                    byte[] publicKey = decode(body, "publicKey", 65);
                    byte[] auth = decode(body, "auth", 16);
                    byte[] wakeKey = decode(body, "wakeKey", 32);
                    encrypter(publicKey, auth); // Reject a malformed P-256 point before accepting a subscription.
                    var incoming = new PushSubscription(identity, originKey, topic, generation, publicKey, auth, wakeKey,
                            randomNonce(), now + 600, 0, 0);
                    yield CiphertextMailboxStore.registerPush(incoming, now, now + SUBSCRIPTION_SECONDS);
                }
                case "confirm" -> {
                    BoundedJson.fields(body, "op", "generation", "challenge");
                    yield CiphertextMailboxStore.confirmPush(identity, originKey, generation, decode(body, "challenge", 16),
                            now, now + SUBSCRIPTION_SECONDS);
                }
                case "remove" -> {
                    BoundedJson.fields(body, "op", "generation");
                    CiphertextMailboxStore.removePush(identity, originKey, generation);
                    yield new PushStatus("removed", 0);
                }
                default -> throw new IllegalArgumentException("Unknown push operation");
            };
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            status = new PushStatus("invalid", 0);
        }
        var body = new JsonObject();
        body.addProperty("generation", generation);
        body.addProperty("status", status.status());
        body.addProperty("expires", status.expiresAt());
        PacketWriter.write(ctx.channel(), new GhostPacket(GhostPacket.TYPE_V2_PUSH_RESULT, identity, correlation,
                body.toString().getBytes(StandardCharsets.UTF_8), null), true);
    }

    private static byte[] decode(JsonObject body, String name, int size) {
        String encoded = BoundedJson.string(body, name, (size * 4 + 2) / 3);
        var value = Base64.getUrlDecoder().decode(encoded);
        if (value.length != size || !Base64.getUrlEncoder().withoutPadding().encodeToString(value).equals(encoded))
            throw new IllegalArgumentException("Invalid push key or challenge encoding");
        return value;
    }

    private void poll() {
        if (sending.size() >= CONCURRENCY) return;
        try {
            for (var subscription : CiphertextMailboxStore.duePush(originKey, Instant.now().getEpochSecond())) {
                if (sending.size() >= CONCURRENCY || senders.isShutdown()) break;
                if (!sending.add(subscription.recipient())) continue;
                try {
                    senders.execute(() -> {
                        try { deliver(subscription); }
                        catch (SQLException | RuntimeException error) {
                            log.warn("Push delivery will retry ({})", error.getClass().getSimpleName());
                        } finally { sending.remove(subscription.recipient()); }
                    });
                } catch (RejectedExecutionException closed) {
                    sending.remove(subscription.recipient());
                    return;
                }
            }
        } catch (SQLException | RuntimeException error) {
            log.warn("Push queue will retry ({})", error.getClass().getSimpleName());
        }
    }

    private void deliver(PushSubscription subscription) throws SQLException {
        if (IdentityRevocations.revoked(subscription.recipient())) return;
        long now = Instant.now().getEpochSecond();
        if (subscription.expiresAt() <= now || !CiphertextMailboxStore.isPushStillDue(subscription, now)) return;
        int failures = Math.min(subscription.failures() + 1, 8);
        long delay = Math.min(5L << failures, 300);
        boolean gone = false;
        try {
            boolean challenge = subscription.challenge() != null;
            byte[] body = ByteBuffer.allocate(26).put((byte) 1).put((byte) (challenge ? 1 : 2))
                    .putLong(Math.min(now + 120, subscription.expiresAt()))
                    .put(challenge ? subscription.challenge() : randomNonce()).array();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(subscription.wakeKey(), "HmacSHA256"));
            mac.update(DOMAIN);
            byte[] authenticated = ByteBuffer.allocate(58).put(body).put(mac.doFinal(body)).array();
            byte[] sealed = encrypter(subscription.publicKey(), subscription.auth()).encrypt(authenticated, null);
            HttpRequest request = HttpRequest.newBuilder(origin.resolve("/" + subscription.topic()))
                    .timeout(Duration.ofSeconds(7)).header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("X-UnifiedPush", "1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(sealed)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var bodyStream = response.body()) {
                int status = response.statusCode();
                gone = status == 404 || status == 410;
                if (status >= 200 && status < 300) {
                    failures = 0;
                    // A failed client wake leaves the ciphertext pending, so try again after coalescing.
                    delay = challenge ? 40 : 60;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException ignored) {
            // No URL or subscription secret goes in the log on a delivery failure.
        } catch (GeneralSecurityException error) {
            gone = true;
        }
        CiphertextMailboxStore.finishPush(subscription, Instant.now().getEpochSecond() + delay, failures, gone);
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static WebPushHybridEncrypt encrypter(byte[] publicKey, byte[] auth) throws GeneralSecurityException {
        return new WebPushHybridEncrypt.Builder().withRecipientPublicKey(publicKey).withAuthSecret(auth).build();
    }

    @Override public void close() {
        poller.shutdownNow();
        senders.shutdownNow();
        http.shutdownNow();
        boolean interrupted = false;
        for (ExecutorService executor : new ExecutorService[]{poller, senders}) {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
