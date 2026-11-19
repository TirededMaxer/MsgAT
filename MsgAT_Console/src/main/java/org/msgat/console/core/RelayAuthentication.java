// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fixed-width, purpose-separated root proof bound to this TLS endpoint and both nonces. */
final class RelayAuthentication {
    static final byte[] REQUEST = {'M', 'T', 'A', '2'};
    static final byte[] TRANSFER_REQUEST = {'M', 'T', 'B', '2'};
    private static final byte[] DOMAIN = "MsgAT/v2/relay-auth\0".getBytes(StandardCharsets.US_ASCII);

    private RelayAuthentication() { }

    static byte[] challenge(Channel channel, byte[] clientNonce, boolean transfer) {
        SslHandler tls = channel.pipeline().get(SslHandler.class);
        if (tls == null || !tls.handshakeFuture().isSuccess()) throw new IllegalArgumentException("TLS is required");
        var certificates = tls.engine().getSession().getLocalCertificates();
        if (certificates == null || certificates.length == 0) throw new IllegalArgumentException("TLS certificate is required");
        try {
            byte[] endpoint = MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded());
            return ByteBuffer.allocate(50).put((byte) 2).put((byte) (transfer ? 1 : 0)).put(clientNonce).put(endpoint).array();
        } catch (java.security.cert.CertificateEncodingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Unable to bind TLS authentication", error);
        }
    }

    static byte[] transcript(String mailbox, byte[] serverNonce, byte[] challenge) {
        if (!mailbox.matches("[a-f0-9]{64}") || serverNonce.length != 16
                || challenge.length != 50 || challenge[0] != 2 || (challenge[1] != 0 && challenge[1] != 1)) throw new IllegalArgumentException("Invalid authentication transcript");
        return ByteBuffer.allocate(DOMAIN.length + 98).put(DOMAIN).put((byte) 2).put(challenge[1])
                .put(HexFormat.of().parseHex(mailbox)).put(challenge, 2, 16).put(serverNonce)
                .put(challenge, 18, 32).array();
    }
}
