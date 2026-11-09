package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.OpusPacket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Knows who is speaking in one server's voice channel, from the fact that voice packets arrive for them (Discord sends
 * nothing for a person who is silent).
 *
 * <p>It looks at who sent a packet and when it arrived, and nothing else. The audio itself is never read, decoded, kept
 * or passed on.
 */
final class SpeakingTracker implements AudioReceiveHandler {

    /**
     * How long without a packet before someone counts as having stopped. Kept short on purpose: how long the picture keeps
     * moving after the voice stops is the overlay's "release" setting, which smooths over short gaps and can be tuned.
     */
    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long SWEEP_MILLIS = 40;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-standee-speaking");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, Long> lastHeard = new ConcurrentHashMap<>();
    private final Set<Long> speaking = ConcurrentHashMap.newKeySet();
    private final Runnable onChange;
    private final ScheduledFuture<?> sweep;

    SpeakingTracker(Runnable onChange) {
        this.onChange = onChange;
        this.sweep = TIMER.scheduleWithFixedDelay(this::sweepQuiet, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean canReceiveEncoded() {
        return true; // only the packets' arrival is used; nothing is decoded
    }

    @Override
    public void handleEncodedAudio(OpusPacket packet) {
        long id = packet.getUserId();
        if (id == 0) {
            return;
        }
        lastHeard.put(id, System.nanoTime());
        if (speaking.add(id)) {
            onChange.run();
        }
    }

    Set<Long> speakingIds() {
        return Set.copyOf(speaking);
    }

    /** Stops watching and forgets everyone. */
    void stop() {
        sweep.cancel(false);
        boolean any = !speaking.isEmpty();
        speaking.clear();
        lastHeard.clear();
        if (any) {
            onChange.run();
        }
    }

    private void sweepQuiet() {
        try {
            long now = System.nanoTime();
            boolean changed = false;
            for (Long id : speaking) {
                Long last = lastHeard.get(id);
                if ((last == null || now - last > QUIET_NANOS) && speaking.remove(id)) {
                    changed = true;
                }
            }
            if (changed) {
                onChange.run();
            }
        } catch (RuntimeException e) {
            // a failed round must not end the schedule
        }
    }
}
