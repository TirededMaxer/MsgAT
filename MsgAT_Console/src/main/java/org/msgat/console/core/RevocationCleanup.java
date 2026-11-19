// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import org.msgat.console.db.AttachmentStore;
import org.msgat.console.db.IdentityRevocations;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Resumes durable revocations on startup and wakes on new records. */
public final class RevocationCleanup {
    private static final long BATCH_DELAY_MS = 1_000;
    private static final long READ_RETRY_MS = 5_000;
    private static final long NO_WORK = -1;
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RevocationCleanup.class);
    private final ScheduledExecutorService scheduler;
    private final AttachmentStore attachments;
    private ScheduledFuture<?> scheduled;
    private long revision;
    private boolean running;
    private boolean wakeRequested;

    public RevocationCleanup(ScheduledExecutorService scheduler, AttachmentStore attachments) {
        this.scheduler = scheduler;
        this.attachments = attachments;
    }

    public synchronized void wake() {
        if (scheduler.isShutdown()) return;
        if (running) {
            wakeRequested = true;
            return;
        }
        if (scheduled != null) {
            if (scheduled.getDelay(TimeUnit.MILLISECONDS) <= BATCH_DELAY_MS) return;
            scheduled.cancel(false);
        }
        schedule(BATCH_DELAY_MS);
    }

    private void schedule(long delayMs) {
        if (scheduler.isShutdown()) return;
        long current = ++revision;
        try {
            scheduled = scheduler.schedule(() -> run(current), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            if (!scheduler.isShutdown()) throw stopped;
        }
    }

    private void run(long current) {
        synchronized (this) {
            if (current != revision || scheduler.isShutdown()) return;
            scheduled = null;
            running = true;
        }
        long delay = READ_RETRY_MS;
        try {
            try {
                IdentityRevocations.cleanup(attachments);
            } catch (SQLException | IOException error) {
                log.warn("Revocation cleanup will retry ({})", error.getClass().getSimpleName());
            }
            var next = IdentityRevocations.nextCleanupDueAt();
            delay = next.isEmpty() ? NO_WORK
                    : Math.max(BATCH_DELAY_MS, next.getAsLong() - System.currentTimeMillis());
        } catch (SQLException error) {
            log.warn("Revocation schedule will retry ({})", error.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                running = false;
                if (wakeRequested) {
                    wakeRequested = false;
                    schedule(BATCH_DELAY_MS);
                } else if (delay != NO_WORK) {
                    schedule(delay);
                }
            }
        }
    }
}
