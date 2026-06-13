package org.msgat.console.routing;

import io.netty.channel.Channel;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

/**
 * 盲路由表：仅在内存中维护 Hash(UUID) <-> Socket 的映射
 */
public class BlindRoutingTable {
    private static final Logger log = LoggerFactory.getLogger(BlindRoutingTable.class);

    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();

    // 【新增】：内部包装类，用于记录消息存入内存的物理时间
    private record TimedPacket(GhostPacket packet, long timestamp) {}

    // 【修改】：暂存池类型变更为存储带时间戳的包装类
    private final ConcurrentHashMap<String, Queue<TimedPacket>> offlineVault = new ConcurrentHashMap<>();

    // 【新增】：单线程调度器，用于定期执行过期清理任务
    private final ScheduledExecutorService cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OfflineVault-Cleaner");
        t.setDaemon(true); // 设为守护线程，随 JVM 退出而退出
        return t;
    });

    public BlindRoutingTable() {
        // 【新增】：启动定时任务，每小时执行一次全量扫描，清理过期 24 小时的消息
        cleanerExecutor.scheduleAtFixedRate(this::cleanExpiredMessages, 1, 1, TimeUnit.HOURS);
    }

    /**
     * 存入离线消息
     */
    public void storeOfflineMessage(String mailboxHash, GhostPacket packet) {
        Queue<TimedPacket> queue = offlineVault.computeIfAbsent(mailboxHash, k -> new ConcurrentLinkedQueue<>());

        // 1. 检查单信箱 50 条上限
        while (queue.size() >= 50) {
            TimedPacket oldest = queue.poll();
            if (oldest != null) {
                oldest.packet().wipeData(); // 物理擦除旧消息
            }
        }

        // 2. 【修改】：存入带当前系统时间戳的消息包装
        queue.add(new TimedPacket(packet, System.currentTimeMillis()));
        log.debug("[ROUTING] 消息已存入信箱 {} 暂存池 (过期阈值: 24h)", mailboxHash);
    }

    /**
     * 提取离线消息
     */
    public List<GhostPacket> retrieveOfflineMessages(String mailboxHash) {
        Queue<TimedPacket> timedQueue = offlineVault.remove(mailboxHash);
        if (timedQueue == null || timedQueue.isEmpty()) return null;

        // 【修改】：将 TimedPacket 解包为原始 GhostPacket 列表返回给推送逻辑
        List<GhostPacket> result = new ArrayList<>();
        TimedPacket tp;
        while ((tp = timedQueue.poll()) != null) {
            result.add(tp.packet());
        }
        return result;
    }

    /**
     * 【新增核心逻辑】：扫描并清理超过 24 小时的“僵尸”消息
     */
    private void cleanExpiredMessages() {
        long now = System.currentTimeMillis();
        long expiryMillis = 24 * 60 * 60 * 1000L; // 24小时的毫秒数

        log.info("[CLEANER] 正在执行离线消息过期扫描...");
        int purgedCount = 0;

        // 迭代 ConcurrentHashMap 所有的 Key
        Iterator<String> mailboxIterator = offlineVault.keySet().iterator();
        while (mailboxIterator.hasNext()) {
            String mailboxHash = mailboxIterator.next();
            Queue<TimedPacket> queue = offlineVault.get(mailboxHash);

            if (queue != null) {
                // 使用安全迭代器清理队列中的过期项
                Iterator<TimedPacket> packetIterator = queue.iterator();
                while (packetIterator.hasNext()) {
                    TimedPacket tp = packetIterator.next();
                    if (now - tp.timestamp() > expiryMillis) {
                        tp.packet().wipeData(); // 执行物理覆写自毁
                        packetIterator.remove();
                        purgedCount++;
                    }
                }

                // 如果整个信箱都被清空了，则移除该信箱映射，节省内存
                if (queue.isEmpty()) {
                    mailboxIterator.remove();
                }
            }
        }

        if (purgedCount > 0) {
            log.info("[CLEANER] 自动清理结束，已抹除 {} 条过期的离线消息", purgedCount);
        }
    }

    /**
     * 收到 ACK 或触发自毁时，手动抹除特定信箱暂存池
     */
    public void clearAndWipeOfflineMessages(String mailboxHash) {
        Queue<TimedPacket> messages = offlineVault.remove(mailboxHash);
        if (messages != null) {
            for (TimedPacket tp : messages) {
                tp.packet().wipeData();
            }
            messages.clear();
        }
    }

    public void register(String mailboxHashHex, Channel channel) {
        activeChannels.put(mailboxHashHex, channel);
        log.debug("[ROUTING] 新信箱已挂载，当前活跃节点数: {}", activeChannels.size());
    }

    public Channel getChannel(String mailboxHashHex) {
        return activeChannels.get(mailboxHashHex);
    }

    public void remove(String mailboxHashHex) {
        activeChannels.remove(mailboxHashHex);
    }

    /**
     * 终极防御：紧急安全擦除
     */
    public void secureWipe() {
        log.warn("[WIPE] 正在执行路由表安全擦除...");

        // 1. 停止定时清理任务
        cleanerExecutor.shutdownNow();

        // 2. 强制关闭所有活跃连接
        activeChannels.values().forEach(Channel::close);
        activeChannels.clear();

        // 3. 物理覆写离线池中所有数据包
        offlineVault.values().forEach(queue -> {
            queue.forEach(tp -> tp.packet().wipeData());
            queue.clear();
        });
        offlineVault.clear();
        log.warn("[WIPE] 内存物理擦除完成。");
    }
}