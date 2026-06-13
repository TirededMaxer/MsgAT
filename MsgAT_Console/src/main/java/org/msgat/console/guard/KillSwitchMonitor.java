package org.msgat.console.guard;

import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 防御守卫：监控非法调试与内存嗅探
 */
public class KillSwitchMonitor {
    private static final Logger log = LoggerFactory.getLogger(KillSwitchMonitor.class);
    private final BlindRoutingTable routingTable;
    private Thread monitorThread;
    private volatile boolean running = true;

    public KillSwitchMonitor(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

    public void startMonitoring() {
        monitorThread = new Thread(() -> {
            while (running) {
                try {
                    checkJvmDebuggers();
                    checkLinuxPtrace(); // 检查外部嗅探
                    Thread.sleep(3000); // 每3秒高频扫描一次
                } catch (SecurityException e) {
                    triggerKillSwitch("[GUARD] 检测到安全异常: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "KillSwitch-Thread");

        // 设置为守护线程，这样主线程结束时它会自动销毁
        monitorThread.setDaemon(true);
        monitorThread.start();
        log.info("[GUARD] 防御守卫已启动，正在监控非法调试与内存嗅探...");
    }

    /**
     * 检查是否被挂载了 Java 调试器
     */
    private void checkJvmDebuggers() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            if (arg.contains("-agentlib:jdwp") || arg.contains("-Xdebug") || arg.contains("-javaagent")) {
                throw new SecurityException("[GUARD] 检测到 JVM 调试器/探针挂载!");
            }
        }
    }

    /**
     * 检查 Linux 系统下的 ptrace (如 gdb, strace)
     */
    private void checkLinuxPtrace() {
        // 1. 判断操作系统，这招只在 Linux 服务器上管用
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux")) {
            return;
        }

        File statusFile = new File("/proc/self/status");
        if (!statusFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 2. 逐行扫描，寻找 TracerPid
                if (line.startsWith("TracerPid:")) {
                    // 格式通常是 "TracerPid:    0"
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        int tracerPid = Integer.parseInt(parts[1]);
                        // 3. 核心判决：只要大于 0，说明被探针插入了！
                        if (tracerPid > 0) {
                            throw new SecurityException("[GUARD] 检测到外部 ptrace 内存嗅探 (异常进程PID: " + tracerPid + ")！");
                        }
                    }
                    break; // 找到了就可以跳出循环了
                }
            }
        } catch (Exception e) {
            // 读取文件可能会有权限问题或 IO 异常，静默忽略，不影响主流程
            log.debug("[GUARD] 读取 status 文件失败: ", e);
        }
    }

    /**
     * 触发自毁
     */
    private void triggerKillSwitch(String reason) {
        log.error("!!! [FATAL] 触发自毁机制 !!! 原因: {}", reason);
        // 瞬间清空所有通讯映射
        routingTable.secureWipe();
        // 强制终止 JVM，status 1 表示异常退出
        System.exit(1);
    }
}