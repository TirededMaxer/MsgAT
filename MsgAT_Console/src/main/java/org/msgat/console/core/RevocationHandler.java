// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.ChannelHandlerContext;
import org.msgat.console.db.IdentityRevocations;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;

/** A signed revocation is the only operation permitted without retaining its destroyed root. */
final class RevocationHandler {
    private static long window=System.nanoTime();
    private static int count;
    private RevocationHandler() { }
    private static synchronized boolean admit() {
        if(System.nanoTime()-window>=1_000_000_000L) { window=System.nanoTime(); count=0; }
        return ++count<=32;
    }
    static void handle(ChannelHandlerContext context, GhostPacket packet, BlindRoutingTable routes, Runnable wakeCleanup) {
        try {
            if(!admit() || packet.getPayloadLen()!=113) { context.close(); return; }
            var proof=packet.getEncryptedData();
            var record = IdentityRevocations.record(proof);
            if (record.changed()) wakeCleanup.run();
            routes.closeIdentity(record.mailbox());
            var hash=java.security.MessageDigest.getInstance("SHA-256").digest(proof);
            PacketWriter.write(context.channel(),new GhostPacket(GhostPacket.TYPE_V2_REVOKED,null,packet.getNonceOrIv(),hash,null),true)
                .addListener(done -> context.close());
        } catch(Exception error) { context.close(); }
    }
}
