package com.yuaworks.voicestandee;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a task once things have been quiet for a while: each new trigger for the same key cancels the one waiting and starts the
 * wait again. Different keys are independent.
 *
 * <p>Used so that the bot follows a streamer to the place they settle in, not to every channel they pass through (the bridge
 * puts people into a "create a room" channel for a moment before moving them to their own room).
 */
final class Debouncer {

    private final ScheduledExecutorService scheduler;
    private final Map<Long, ScheduledFuture<?>> waiting = new ConcurrentHashMap<>();

    Debouncer(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    void trigger(long key, long delay, TimeUnit unit, Runnable task) {
        ScheduledFuture<?> next = scheduler.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // one failed run must not break later ones
            }
        }, delay, unit);
        ScheduledFuture<?> previous = waiting.put(key, next);
        if (previous != null) {
            previous.cancel(false);
        }
    }
}
