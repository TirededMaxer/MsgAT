package org.msgat.console.guard;

import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.util.List;

public class KillSwitchMonitor {
    private static final Logger log = LoggerFactory.getLogger(KillSwitchMonitor.class);
    private final BlindRoutingTable routingTable;
    private Thread monitorThread;
    private volatile boolean running = true;

    // Constructor to inject the routing table instance for emergency destruction capabilities.
    public KillSwitchMonitor(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

    // Spawns a background daemon thread that continuously polls for debuggers and ptrace attachments.
    public void startMonitoring() {
        monitorThread = new Thread(() -> {
            while (running) {
                try {
                    checkJvmDebuggers();
                    checkLinuxPtrace();
                    Thread.sleep(3000);
                } catch (SecurityException e) {
                    triggerKillSwitch("Security exception detected: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "KillSwitch-Thread");

        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    // Scans JVM startup arguments for any known remote debugger or java agent flags.
    private void checkJvmDebuggers() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            if (arg.contains("-agentlib:jdwp") || arg.contains("-Xdebug") || arg.contains("-javaagent")) {
                throw new SecurityException("Debugger or java agent detected!");
            }
        }
    }

    // Checks the Linux /proc/self/status file to detect if a TracerPid is attached to the JVM.
    private void checkLinuxPtrace() {
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
                if (line.startsWith("TracerPid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        int tracerPid = Integer.parseInt(parts[1]);
                        if (tracerPid > 0) {
                            throw new SecurityException("External ptrace detected (PID: " + tracerPid + ")!");
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
        }
    }

    // Executes emergency termination protocol by wiping all routing states and forcibly exiting the process.
    private void triggerKillSwitch(String reason) {
        log.error("Triggering kill switch. Reason: {}", reason);
        routingTable.secureWipe();
        System.exit(1);
    }
}