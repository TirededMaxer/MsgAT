package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 幽灵中转器：负责消息的延迟投递、暂存和阅后即焚机制
 */
public class RelayHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);
    private final BlindRoutingTable routingTable;

    public RelayHandler(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

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
            default:
                log.debug("[RELAY] 忽略未知的业务包类型: {}", packet.getType());
                break;
        }
    }

    /**
     * 处理真正的加密聊天消息
     */
    private void handleIncomingMessage(ChannelHandlerContext ctx, GhostPacket packet) {
        String targetMailbox = packet.getTargetMailboxId();
        Channel targetChannel = routingTable.getChannel(targetMailbox);

        // 1. 生成 1000ms ~ 3000ms 的随机延迟
        int delayMs = ThreadLocalRandom.current().nextInt(1000, 3001);

        if (targetChannel != null && targetChannel.isActive()) {
            // [目标在线] -> 执行延迟投递
            log.debug("[RELAY] 目标在线，生成随机延迟 {} ms 后投递", delayMs);

            // 使用 Netty 的 EventLoop 定时器，绝对不会阻塞线程
            ctx.executor().schedule(() -> {
                targetChannel.writeAndFlush(packet).addListener(future -> {
                    if (future.isSuccess()) {
                        log.debug("[RELAY] 延迟投递成功");
                        // 注意：这里投递成功不代表对方已读，真正的抹除在收到 ACK 后进行
                    }
                });
            }, delayMs, TimeUnit.MILLISECONDS);

        } else {
            // [目标离线] -> 暂存到纯内存中
            log.debug("[RELAY] 目标离线，消息已进入内存潜伏池");
            routingTable.storeOfflineMessage(targetMailbox, packet);
        }
    }

    /**
     * 处理噪音/心跳包
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, GhostPacket packet) {
        // 心跳包同样进行 1~3 秒的随机延迟反射，彻底打乱流量特征
        int delayMs = ThreadLocalRandom.current().nextInt(1000, 3001);
        ctx.executor().schedule(() -> {
            ctx.writeAndFlush(packet); // 原样把 500KB 的废包弹回去
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 处理阅后即焚确认 (ACK)
     */
    private void handleAck(GhostPacket packet) {
        // 收到客户端发来的 ACK，意味着消息已在对方屏幕上渲染完毕
        String mailbox = packet.getTargetMailboxId();
        log.info("[ACK] 收到销毁指令，开始物理抹除信箱 {} 的暂存记录...", mailbox.substring(0, 8));

        // 彻底清空该信箱在内存中的所有离线消息，并覆写 0
        routingTable.clearAndWipeOfflineMessages(mailbox);
    }
}