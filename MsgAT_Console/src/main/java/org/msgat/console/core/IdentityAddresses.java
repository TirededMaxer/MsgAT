// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.bouncycastle.jcajce.provider.digest.Blake2b;

/** Public identity addressing only. The relay has no content-decryption key. */
public final class IdentityAddresses {
    private IdentityAddresses() {}
    public static String identityUuid(byte[] publicKey) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String calculateMailboxId(String uuid) {
        byte[] hash = new Blake2b.Blake2b512().digest(uuid.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash, 0, 32);
    }
}
