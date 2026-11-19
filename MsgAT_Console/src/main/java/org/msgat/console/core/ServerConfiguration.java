package org.msgat.console.core;

import ch.qos.logback.classic.Level;
import com.google.gson.JsonObject;
import io.netty.util.NetUtil;
import org.msgat.console.Main;
import org.msgat.console.db.AttachmentStore;
import org.msgat.console.db.CiphertextMailboxStore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Paths in server-config.json are resolved relative to the executable JAR. */
public record ServerConfiguration(InetSocketAddress listener, Path file, Path dataDirectory, AttachmentStore.Limits attachmentLimits,
                                  CiphertextMailboxStore.Limits mailboxLimits,
                                  ConnectionLimits connectionLimits, ConnectionTimeouts connectionTimeouts, int maxInboundFrameBytes,
                                  TlsFiles tlsFiles, Level logLevel, URI pushOrigin) {
    public record ConnectionLimits(int total, int unauthenticated, int unauthenticatedPerAddress) {}
    public record ConnectionTimeouts(int authenticationSeconds, int readSeconds, int writeSeconds) {}
    public record TlsFiles(Path certificate, Path privateKey) {}
    private static final String DEFAULTS = """
            {
              "port": 34478,
              "listenAddress": "*",
              "dataDirectory": "data",
              "maxConnections": 1024,
              "maxUnauthenticatedConnections": 64,
              "maxUnauthenticatedPerAddress": 16,
              "maxInboundFrameBytes": 262144,
              "authenticationTimeoutSeconds": 30,
              "readTimeoutSeconds": 300,
              "writeTimeoutSeconds": 60,
              "mailboxRecipientQuotaBytes": 67108864,
              "mailboxGlobalQuotaBytes": 2147483648,
              "mailboxRetentionDays": 7,
              "attachmentMaxBytes": 104857600,
              "attachmentOwnerQuotaBytes": 536870912,
              "attachmentGlobalQuotaBytes": 8589934592,
              "attachmentRetentionDays": 14
            }
            """;
    public static ServerConfiguration load() throws Exception {
        Path executable = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path directory = Files.isDirectory(executable) ? executable : executable.getParent();
        Path file = directory.resolve("server-config.json");
        try {
            Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Keep the operator's existing settings.
        }
        if (Files.size(file) > 16 * 1024) throw new IOException("Configuration is too large: " + file);
        try {
            JsonObject values = BoundedJson.parse(Files.readAllBytes(file));
            var allowed = java.util.Set.of("port","dataDirectory","attachmentMaxBytes","attachmentOwnerQuotaBytes",
                    "attachmentGlobalQuotaBytes","attachmentRetentionDays", "maxConnections",
                    "maxUnauthenticatedConnections", "maxUnauthenticatedPerAddress", "pushOrigin",
                    "mailboxRecipientQuotaBytes", "mailboxGlobalQuotaBytes", "mailboxRetentionDays",
                    "listenAddress", "authenticationTimeoutSeconds", "readTimeoutSeconds", "writeTimeoutSeconds",
                    "tlsCertificatePath", "tlsPrivateKeyPath", "logLevel", "maxInboundFrameBytes");
            if (!allowed.containsAll(values.keySet())) throw new IllegalArgumentException("Unknown configuration field");
            int port = (int) number(values,"port",34478,1,65535);
            String listenAddress = values.has("listenAddress") ? BoundedJson.string(values, "listenAddress", 128) : "*";
            InetSocketAddress listener = listener(listenAddress, port);
            String path = values.has("dataDirectory") ? BoundedJson.string(values,"dataDirectory",4096) : "data";
            Path dataDirectory = directory.resolve(path).toAbsolutePath().normalize();
            long maximum = number(values,"attachmentMaxBytes",100L*1024*1024,1,2L*1024*1024*1024);
            long encodedMaximum = ((maximum+65535)/65536)*AttachmentStore.CHUNK_BYTES;
            long ownerQuota = number(values,"attachmentOwnerQuotaBytes",512L*1024*1024,encodedMaximum+4096,1L<<40);
            long globalQuota = number(values,"attachmentGlobalQuotaBytes",8L*1024*1024*1024,ownerQuota,16L<<40);
            int retentionDays = (int) number(values,"attachmentRetentionDays",14,8,30);
            var mailboxDefaults = CiphertextMailboxStore.DEFAULT_LIMITS;
            long mailboxQuota = number(values, "mailboxRecipientQuotaBytes", mailboxDefaults.perMailboxBytes(), 1, 1L << 40);
            long allMailboxesQuota = number(values, "mailboxGlobalQuotaBytes", mailboxDefaults.globalBytes(), mailboxQuota, 16L << 40);
            int mailboxRetention = (int) number(values, "mailboxRetentionDays", mailboxDefaults.retentionDays(),
                    1, CiphertextMailboxStore.MAX_RETENTION_DAYS);
            int total = (int) number(values, "maxConnections", 1024, 1, 65536);
            int pending = (int) number(values, "maxUnauthenticatedConnections", Math.min(64, total), 1, total);
            int perAddress = (int) number(values, "maxUnauthenticatedPerAddress", Math.min(16, pending), 1, pending);
            int maxFrame = (int) number(values, "maxInboundFrameBytes", GhostPacketCodec.MAX_FRAME_SIZE,
                    GhostPacketCodec.MIN_INBOUND_LIMIT, GhostPacketCodec.MAX_FRAME_SIZE);
            var timeouts = new ConnectionTimeouts(
                    (int) number(values, "authenticationTimeoutSeconds", 30, 5, 120),
                    (int) number(values, "readTimeoutSeconds", 300, 240, 3600),
                    (int) number(values, "writeTimeoutSeconds", 60, 10, 600));
            return new ServerConfiguration(listener, file, dataDirectory,
                    new AttachmentStore.Limits(maximum, ownerQuota, globalQuota, retentionDays),
                    new CiphertextMailboxStore.Limits(mailboxQuota, allMailboxesQuota, mailboxRetention),
                    new ConnectionLimits(total, pending, perAddress), timeouts, maxFrame,
                    tlsFiles(values, directory), logLevel(values),
                    values.has("pushOrigin") ? AndroidPushRelay.parseOrigin(BoundedJson.string(values,"pushOrigin",255)) : null);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("Invalid server configuration " + file + ": " + error.getMessage(), error);
        }
    }

    private static TlsFiles tlsFiles(JsonObject values, Path directory) {
        if (values.has("tlsCertificatePath") || values.has("tlsPrivateKeyPath")) {
            if (!values.has("tlsCertificatePath") || !values.has("tlsPrivateKeyPath")) {
                throw new IllegalArgumentException("tlsCertificatePath and tlsPrivateKeyPath must be configured together");
            }
            String certificate = BoundedJson.string(values, "tlsCertificatePath", 4096);
            String privateKey = BoundedJson.string(values, "tlsPrivateKeyPath", 4096);
            if (certificate.isBlank() || privateKey.isBlank()) {
                throw new IllegalArgumentException("TLS file paths cannot be blank");
            }
            return new TlsFiles(directory.resolve(certificate).normalize(), directory.resolve(privateKey).normalize());
        }

        String certificate = System.getenv("MSGAT_TLS_CERT");
        String privateKey = System.getenv("MSGAT_TLS_KEY");
        boolean hasCertificate = certificate != null && !certificate.isBlank();
        boolean hasPrivateKey = privateKey != null && !privateKey.isBlank();
        if (hasCertificate != hasPrivateKey) {
            throw new IllegalArgumentException("MSGAT_TLS_CERT and MSGAT_TLS_KEY must be configured together");
        }
        return hasCertificate ? new TlsFiles(Path.of(certificate), Path.of(privateKey)) : null;
    }

    private static Level logLevel(JsonObject values) {
        if (!values.has("logLevel")) return null;
        return switch (BoundedJson.string(values, "logLevel", 5)) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            default -> throw new IllegalArgumentException("logLevel must be TRACE, DEBUG, INFO, WARN or ERROR");
        };
    }

    private static InetSocketAddress listener(String address, int port) {
        if (address.equals("*")) return new InetSocketAddress(port);
        if (!NetUtil.isValidIpV4Address(address) && !NetUtil.isValidIpV6Address(address))
            throw new IllegalArgumentException("listenAddress must be an IP address or *");
        try {
            return new InetSocketAddress(InetAddress.getByName(address), port);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("Invalid listenAddress", error);
        }
    }

    private static long number(JsonObject object,String name,long fallback,long minimum,long maximum) {
        long value = object.has(name) ? BoundedJson.integer(object,name,minimum,maximum) : fallback;
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid configuration range: " + name);
        return value;
    }
}
