// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.msgat.console.db.SignalKeyDirectory;
import org.msgat.console.model.GhostPacket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

final class KeyDirectoryHandler {
    private KeyDirectoryHandler() {}

    static void handle(ChannelHandlerContext context, GhostPacket packet) {
        String identity = context.channel().attr(AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).get();
        byte[] root = context.channel().attr(AttributeKey.<byte[]>valueOf("ROOT_PUBLIC_KEY")).get();
        String mailbox = context.channel().attr(AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (identity == null || root == null || mailbox == null || !mailbox.equals(packet.getTargetMailboxId())) {
            context.close(); return;
        }
        try {
            JsonObject request = BoundedJson.parse(packet.getEncryptedData());
            JsonObject response;
            byte responseType;
            if (packet.getType() == GhostPacket.TYPE_V2_KEY_PUBLISH) {
                SignalKeyDirectory.publish(identity, root, request);
                response = new JsonObject();
                response.addProperty("published", true);
                responseType = GhostPacket.TYPE_V2_KEY_PUBLISHED;
            } else {
                BoundedJson.fields(request, "request_id", "uuid");
                response = SignalKeyDirectory.claim(identity, BoundedJson.string(request, "request_id", 36), BoundedJson.string(request, "uuid", 64));
                responseType = GhostPacket.TYPE_V2_KEY_RESPONSE;
            }
            PacketWriter.write(context.channel(), new GhostPacket(responseType, mailbox, null,
                    response.toString().getBytes(StandardCharsets.UTF_8), null), true);
        } catch (IllegalArgumentException | IllegalStateException | SQLException error) {
            // Do not expose parser/database internals or echo untrusted key material.
            context.close();
        }
    }
}
