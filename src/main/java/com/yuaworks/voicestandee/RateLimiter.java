package com.yuaworks.voicestandee;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** At most {@code max} actions per key within a sliding window. Used to keep reports from being spammed. */
final class RateLimiter {

    private final int max;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Map<Long, Deque<Long>> hits = new HashMap<>();

    RateLimiter(int max, Duration window, LongSupplier nanoClock) {
        this.max = max;
        this.windowNanos = window.toNanos();
        this.nanoClock = nanoClock;
    }

    /** @return whether the action is allowed now (and counts it if so) */
    synchronized boolean tryAcquire(long key) {
        long now = nanoClock.getAsLong();
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= windowNanos) {
            times.pollFirst();
        }
        if (times.size() >= max) {
            return false;
        }
        times.addLast(now);
        return true;
    }
}
