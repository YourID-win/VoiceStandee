package com.yuaworks.voicestandee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebouncerTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Debouncer debouncer = new Debouncer(scheduler);

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void manyTriggersInARowRunTheTaskOnlyOnceAfterTheyStop() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            debouncer.trigger(1, 150, TimeUnit.MILLISECONDS, runs::incrementAndGet);
            Thread.sleep(40);
        }
        assertEquals(0, runs.get(), "still waiting while triggers keep coming");
        Thread.sleep(400);
        assertEquals(1, runs.get());
    }

    @Test
    void theLastTaskIsTheOneThatRuns() throws Exception {
        AtomicInteger last = new AtomicInteger();
        debouncer.trigger(1, 100, TimeUnit.MILLISECONDS, () -> last.set(1));
        debouncer.trigger(1, 100, TimeUnit.MILLISECONDS, () -> last.set(2));
        Thread.sleep(300);
        assertEquals(2, last.get());
    }

    @Test
    void differentKeysDoNotCancelEachOther() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        debouncer.trigger(1, 100, TimeUnit.MILLISECONDS, runs::incrementAndGet);
        debouncer.trigger(2, 100, TimeUnit.MILLISECONDS, runs::incrementAndGet);
        Thread.sleep(300);
        assertEquals(2, runs.get());
    }

    @Test
    void aFailingTaskDoesNotBreakTheNextOne() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        debouncer.trigger(1, 50, TimeUnit.MILLISECONDS, () -> { throw new IllegalStateException("boom"); });
        Thread.sleep(150);
        debouncer.trigger(1, 50, TimeUnit.MILLISECONDS, runs::incrementAndGet);
        Thread.sleep(200);
        assertEquals(1, runs.get());
    }
}
