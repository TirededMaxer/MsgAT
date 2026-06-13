package org.msgat.console;

import org.msgat.console.core.GhostNettyServer;
import org.msgat.console.guard.KillSwitchMonitor;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幽灵中转节点 (Ghost Relay Node) - 启动类
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final int DEFAULT_PORT = 34478;

    public static void main(String[] args) {
        log.info("===MsgAT 初始化中 ===");

        // 1. 初始化纯内存路由表 (Blind Routing Table)
        // 核心原则：绝不落盘，仅在内存中维护 Hash(UUID) <-> Socket 的映射
        BlindRoutingTable routingTable = new BlindRoutingTable();

        // 2. 启动自毁与反侦察监控 (Kill-Switch Monitor)
        // 独立守护线程运行，监控非法调试、内存抓取或异常登录
        KillSwitchMonitor killSwitch = new KillSwitchMonitor(routingTable);
        killSwitch.startMonitoring();

        // 3. 注册 JVM 关闭钩子 (Shutdown Hook)
        // 确保即使是被 kill -15 正常关闭（比如重启服务器），也能在死前物理擦除内存
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("[KILL-SWITCH] 检测到进程关闭信号，执行紧急内存物理擦除...");
            routingTable.secureWipe();
            log.warn("[KILL-SWITCH] 内存擦除完成，服务安全终止。");
        }));

        // 4. 启动 Netty 通讯服务
        try {
            // 支持通过命令行参数指定端口：java -jar ghost-relay.jar 9999
            int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

            // 将路由表注入到 Netty 服务器中，以便后续 Handler 使用
            GhostNettyServer server = new GhostNettyServer(port, routingTable);
            server.start(); // 这里会阻塞，直到服务器被关闭

        } catch (Exception e) {
            log.error("中转节点启动失败，发生致命异常: ", e);
            // 发生异常导致启动失败时，也要立刻擦除已分配的内存，防止残留
            routingTable.secureWipe();
            System.exit(1);
        }
    }
}