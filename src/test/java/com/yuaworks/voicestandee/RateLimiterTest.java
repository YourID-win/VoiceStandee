package com.yuaworks.voicestandee;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final RateLimiter limiter = new RateLimiter(3, Duration.ofHours(1), now::get);

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void keysAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire(1));
        }
        assertTrue(limiter.tryAcquire(2));
    }

    @Test
    void theWindowSlides() {
        assertTrue(limiter.tryAcquire(1));
        advance(Duration.ofMinutes(30));
        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
        advance(Duration.ofMinutes(31));   // the first one is now more than an hour old
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
    }
}
