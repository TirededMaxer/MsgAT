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
        switch (packet.getType()) {
            case GhostPacket.TYPE_MESSAGE:
                handleIncomingMessage(ctx, packet);
                break;
            case GhostPacket.TYPE_HEARTBEAT:
                handleHeartbeat(ctx, packet);
                break;
            case GhostPacket.TYPE_ACK:
                handleAck(packet);
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
            case GhostPacket.TYPE_FRIEND_DELETED:
            case GhostPacket.TYPE_REQUEST_REVOKED:
            case GhostPacket.TYPE_ACCOUNT_RESET:
                deliverOrStore(packet.getTargetMailboxId(), packet);
                break;
            case GhostPacket.TYPE_CALL_OFFER:
            case GhostPacket.TYPE_CALL_ANSWER:
            case GhostPacket.TYPE_CALL_ICE:
            case GhostPacket.TYPE_CALL_REJECT:
            case GhostPacket.TYPE_CALL_END:
            case GhostPacket.TYPE_CALL_BUSY:
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

    // Handles channel deactivation event.
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
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
        deliverOrStore(targetMailbox, packet);
    }

    // Handles profile update requests by saving user details to the database.
    private void handleUpdateProfile(ChannelHandlerContext ctx, GhostPacket packet) {
        try {
            String json = new String(packet.getEncryptedData(), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String uuid = obj.get("uuid").getAsString();
            String mailboxId = CryptoUtils.calculateMailboxId(uuid);
            String nickname = obj.get("nickname").getAsString();
            String avatarPath = obj.has("avatarPath") ? obj.get("avatarPath").getAsString() : "";
            String avatarData = obj.has("avatarData") ? obj.get("avatarData").getAsString() : "";
            
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
                ctx.writeAndFlush(resp);
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
        Channel targetChannel = routingTable.getChannel(targetMailbox);

        if (targetChannel != null && targetChannel.isActive()) {
            GhostPacket packetCopy = new GhostPacket(packet.getType(), packet.getTargetMailboxId(), packet.getNonceOrIv(), packet.getEncryptedData(), packet.getAuthTag());
            targetChannel.writeAndFlush(packetCopy);
        } else {
            GhostPacket packetCopy = new GhostPacket(packet.getType(), packet.getTargetMailboxId(), packet.getNonceOrIv(), packet.getEncryptedData(), packet.getAuthTag());
            routingTable.storeOfflineMessage(targetMailbox, packetCopy);
        }
    }

    // Attempts real-time delivery if target is online, otherwise drops the packet (used for voice calls).
    private void deliverOnly(String targetMailbox, GhostPacket packet) {
        Channel targetChannel = routingTable.getChannel(targetMailbox);
        if (targetChannel != null && targetChannel.isActive()) {
            GhostPacket packetCopy = new GhostPacket(packet.getType(), packet.getTargetMailboxId(), packet.getNonceOrIv(), packet.getEncryptedData(), packet.getAuthTag());
            targetChannel.writeAndFlush(packetCopy);
        } else {
            log.debug("[RELAY] Target offline, dropping real-time packet type: {}", packet.getType());
        }
    }

    // Echoes heartbeat packets back to the client.
    private void handleHeartbeat(ChannelHandlerContext ctx, GhostPacket packet) {
        ctx.writeAndFlush(packet);
    }

    // Handles message acknowledgement by clearing stored offline messages for the given mailbox.
    private void handleAck(GhostPacket packet) {
        String mailbox = packet.getTargetMailboxId();
        routingTable.clearAndWipeOfflineMessages(mailbox);
    }

    // Handles account deletion request by wiping all data associated with the authenticated user.
    private void handleDeleteAccount(ChannelHandlerContext ctx, GhostPacket packet) {
        String senderMailbox = ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (senderMailbox != null) {
            org.msgat.console.db.DatabaseManager.deleteUserAndData(senderMailbox);
            routingTable.remove(senderMailbox);
        }
        ctx.close();
    }


}