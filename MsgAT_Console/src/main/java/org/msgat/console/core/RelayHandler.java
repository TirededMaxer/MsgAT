package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelayHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);
    private final BlindRoutingTable routingTable;

    // Constructor to initialize the RelayHandler with a routing table instance.
    public RelayHandler(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

    // Handles incoming GhostPackets and routes them to appropriate handler methods based on packet type.
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GhostPacket packet) {
        String mailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (mailbox == null || routingTable.getChannel(mailbox) != ctx.channel()) {
            ctx.close();
            return;
        }
        switch (packet.getType()) {
            case GhostPacket.TYPE_MESSAGE:
                handleIncomingMessage(ctx, packet);
                break;
            case GhostPacket.TYPE_HEARTBEAT:
                handleHeartbeat(ctx, packet);
                break;
            case GhostPacket.TYPE_ACK:
                handleAck(ctx, packet);
                break;
            case GhostPacket.TYPE_SEARCH_USER:
                handleSearchUser(ctx, packet);
                break;
            case GhostPacket.TYPE_UPDATE_PROFILE:
                handleUpdateProfile(ctx, packet);
                break;
            case GhostPacket.TYPE_GET_PROFILE:
                handleGetProfile(ctx, packet);
                break;
            case GhostPacket.TYPE_FRIEND_ACCEPTED:
            case GhostPacket.TYPE_REQUEST_REVOKED:
                if (validateFriendSender(ctx, packet)) deliverOrStore(packet.getTargetMailboxId(), packet);
                break;
            case GhostPacket.TYPE_FRIEND_DELETED:
            case GhostPacket.TYPE_ACCOUNT_RESET:
                deliverOrStore(packet.getTargetMailboxId(), packet);
                break;
            case GhostPacket.TYPE_CALL_OFFER:
            case GhostPacket.TYPE_CALL_ANSWER:
            case GhostPacket.TYPE_CALL_ICE:
            case GhostPacket.TYPE_CALL_REJECT:
            case GhostPacket.TYPE_CALL_END:
            case GhostPacket.TYPE_CALL_BUSY:
            case GhostPacket.TYPE_CALL_SYNC:
                deliverOnly(packet.getTargetMailboxId(), packet);
                break;
            case GhostPacket.TYPE_DELETE_ACCOUNT:
                handleDeleteAccount(ctx, packet);
                break;
            default:
                log.debug("[RELAY] Ignored unknown packet type: {}", packet.getType());
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof java.io.IOException) {
            log.debug("[RELAY] Client network dropped: {}", cause.getMessage());
        } else {
            log.error("[RELAY] Exception caught in pipeline", cause);
        }
        ctx.close();
    }

    // Handles search user request by verifying target existence before delivery.
    private void handleSearchUser(ChannelHandlerContext ctx, GhostPacket packet) {
        String targetMailbox = packet.getTargetMailboxId();
        if (!org.msgat.console.db.DatabaseManager.doesMailboxExist(targetMailbox)) {
            log.warn("[RELAY] Intercepted invalid target mailbox: {}", targetMailbox);
            return;
        }
        if (validateFriendSender(ctx, packet)) deliverOrStore(targetMailbox, packet);
    }

    // Friend-discovery encryption uses public identifiers; bind its claimed sender to authentication.
    private boolean validateFriendSender(ChannelHandlerContext ctx, GhostPacket packet) {
        String recipient = org.msgat.console.db.DatabaseManager.uuidForMailbox(packet.getTargetMailboxId());
        if (recipient == null) return false;
        try {
            var digest = new org.bouncycastle.jcajce.provider.digest.Blake2b.Blake2b256();
            var key = new javax.crypto.spec.SecretKeySpec(digest.digest(
                    (recipient + "|MSGAT|" + recipient).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "AES");
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, packet.getNonceOrIv()));
            byte[] ciphertext = packet.getEncryptedData();
            byte[] combined = java.util.Arrays.copyOf(ciphertext, ciphertext.length + 16);
            System.arraycopy(packet.getAuthTag(), 0, combined, ciphertext.length, 16);
            var payload = com.google.gson.JsonParser.parseString(new String(cipher.doFinal(combined), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String sender = payload.get("senderUuid").getAsString();
            String mailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
            if (!sender.matches("[a-f0-9]{64}") || !CryptoUtils.calculateMailboxId(sender).equals(mailbox)) return false;
            if (packet.getType() != GhostPacket.TYPE_REQUEST_REVOKED) {
                byte[] publicKey = java.util.Base64.getDecoder().decode(payload.get("senderPublicKey").getAsString());
                if (publicKey.length != 32) return false;
            }
            return true;
        } catch (Exception error) {
            log.debug("Rejected malformed friend packet");
            return false;
        }
    }

    // Handles profile update requests by saving user details to the database.
    private void handleUpdateProfile(ChannelHandlerContext ctx, GhostPacket packet) {
        try {
            String json = new String(packet.getEncryptedData(), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String uuid = obj.get("uuid").getAsString();
            if (!uuid.matches("[a-f0-9]{64}")) { ctx.close(); return; }
            String mailboxId = CryptoUtils.calculateMailboxId(uuid);
            String authenticatedMailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
            if (!mailboxId.equals(authenticatedMailbox)) {
                ctx.close();
                return;
            }
            String nickname = obj.get("nickname").getAsString();
            String avatarPath = ""; // Local paths have no meaning on another device.
            String avatarData = obj.has("avatarData") && !obj.get("avatarData").isJsonNull() ? obj.get("avatarData").getAsString() : "";

            org.msgat.console.db.DatabaseManager.saveUser(uuid, mailboxId, nickname, avatarPath, avatarData);
        } catch (Exception e) {
            log.error("[PROFILE] Update profile parse failed", e);
        }
    }

    // Retrieves user profile from the database and sends it back to the client.
    private void handleGetProfile(ChannelHandlerContext ctx, GhostPacket packet) {
        try {
            String json = new String(packet.getEncryptedData(), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String targetUuid = obj.get("targetUuid").getAsString();
            String senderMailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();

            org.msgat.console.db.DatabaseManager.UserProfile profile = org.msgat.console.db.DatabaseManager.getUser(targetUuid);
            if (profile != null) {
                com.google.gson.JsonObject respJson = new com.google.gson.JsonObject();
                respJson.addProperty("uuid", profile.uuid);
                respJson.addProperty("nickname", profile.nickname);
                respJson.addProperty("avatarPath", profile.avatarPath);
                if (profile.avatarData != null) {
                    respJson.addProperty("avatarData", profile.avatarData);
                }

                byte[] payload = respJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                GhostPacket resp = new GhostPacket(GhostPacket.TYPE_PROFILE_RESP, senderMailbox, new byte[16], payload, new byte[16]);
                PacketWriter.write(ctx.channel(), resp, true);
            }
        } catch (Exception e) {
            log.error("[PROFILE] Get profile parse failed", e);
        }
    }

    // Routes incoming chat messages directly to a single recipient.
    private void handleIncomingMessage(ChannelHandlerContext ctx, GhostPacket packet) {
        String targetMailbox = packet.getTargetMailboxId();
        deliverOrStore(targetMailbox, packet);
    }

    // Attempts real-time delivery if target is online, otherwise stores the message for later retrieval.
    private void deliverOrStore(String targetMailbox, GhostPacket packet) {
        synchronized (routingTable) {
            if (!org.msgat.console.db.DatabaseManager.doesMailboxExist(targetMailbox)) return;
            routingTable.storeOfflineMessage(targetMailbox, packet);
            Channel targetChannel = routingTable.getChannel(targetMailbox);
            if (targetChannel != null && targetChannel.isActive() && !routingTable.isReplaying(targetMailbox))
                PacketWriter.write(targetChannel, packet, true);
        }
    }

    // Attempts real-time delivery if target is online, otherwise drops the packet (used for voice calls).
    private void deliverOnly(String targetMailbox, GhostPacket packet) {
        Channel targetChannel = routingTable.getChannel(targetMailbox);
        if (targetChannel != null && targetChannel.isActive()) {
            PacketWriter.write(targetChannel, packet, true);
        } else {
            log.debug("[RELAY] Target offline, dropping real-time packet type: {}", packet.getType());
        }
    }

    // Echoes heartbeat packets back to the client.
    private void handleHeartbeat(ChannelHandlerContext ctx, GhostPacket packet) {
        PacketWriter.write(ctx.channel(), packet, true);
    }

    // Handles message acknowledgement by clearing stored offline messages for the given mailbox.
    private void handleAck(ChannelHandlerContext ctx, GhostPacket packet) {
        String mailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (mailbox != null) routingTable.acknowledge(mailbox, packet.getEncryptedData());
    }

    // Handles account deletion request by wiping all data associated with the authenticated user.
    private void handleDeleteAccount(ChannelHandlerContext ctx, GhostPacket packet) {
        String senderMailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (senderMailbox != null) {
            synchronized (routingTable) {
                org.msgat.console.db.DatabaseManager.deleteUserAndData(senderMailbox);
                routingTable.remove(senderMailbox, ctx.channel());
            }
        }
        PacketWriter.write(ctx.channel(), new GhostPacket(GhostPacket.TYPE_DELETE_ACCOUNT, senderMailbox,
                packet.getNonceOrIv(), new byte[0], new byte[16]), true)
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }


}
