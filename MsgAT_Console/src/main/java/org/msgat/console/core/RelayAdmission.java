// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.socket.SocketChannel;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Accounts for sockets before TLS is installed and releases pending slots at authentication. */
final class RelayAdmission {
    private enum Phase { PENDING, AUTHENTICATED, CLOSING, RELEASED }

    private final ServerConfiguration.ConnectionLimits limits;
    private final int authenticationSeconds;
    private final Map<InetAddress, Integer> pendingByAddress = new HashMap<>();
    private int total;
    private int pending;

    RelayAdmission(ServerConfiguration.ConnectionLimits limits, int authenticationSeconds) {
        this.limits = limits;
        this.authenticationSeconds = authenticationSeconds;
    }

    Lease acquire(SocketChannel channel) {
        var remote = channel.remoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        Lease lease;
        synchronized (this) {
            int addressCount = address == null ? 0 : pendingByAddress.getOrDefault(address, 0);
            if (total >= limits.total() || pending >= limits.unauthenticated()
                    || addressCount >= limits.unauthenticatedPerAddress()) return null;
            total++;
            pending++;
            if (address != null) pendingByAddress.put(address, addressCount + 1);
            lease = new Lease(address);
        }
        channel.closeFuture().addListener(ignored -> lease.release());
        try { lease.armDeadline(channel); }
        catch (RejectedExecutionException stopping) { lease.release(); return null; }
        return lease;
    }

    final class Lease {
        private final InetAddress address;
        private Phase phase = Phase.PENDING;
        private ScheduledFuture<?> deadline;

        private Lease(InetAddress address) { this.address = address; }

        private void armDeadline(SocketChannel channel) {
            synchronized (RelayAdmission.this) {
                if (phase != Phase.PENDING) return;
                deadline = channel.eventLoop().schedule(() -> {
                    synchronized (RelayAdmission.this) {
                        if (phase != Phase.PENDING) return;
                        phase = Phase.CLOSING;
                    }
                    channel.close();
                }, authenticationSeconds, TimeUnit.SECONDS);
            }
        }

        boolean authenticate() {
            synchronized (RelayAdmission.this) {
                if (phase != Phase.PENDING) return false;
                phase = Phase.AUTHENTICATED;
                releasePending();
                return true;
            }
        }

        private void release() {
            synchronized (RelayAdmission.this) {
                if (phase == Phase.RELEASED) return;
                if (phase != Phase.AUTHENTICATED) releasePending();
                phase = Phase.RELEASED;
                total--;
            }
        }

        private void releasePending() {
            if (deadline != null) { deadline.cancel(false); deadline = null; }
            pending--;
            if (address != null) pendingByAddress.computeIfPresent(address, (key, count) -> count == 1 ? null : count - 1);
        }
    }
}
