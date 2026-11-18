package com.yuaworks.voicestandee;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Which bot serves which voice channel of one server. A bot can be in one voice channel of a server at a time, so with several bots
 * several channels that have a streamer in them (two collaboration rooms) can be served at once.
 *
 * <p>The rules (a pure decision, so that it can be tested):
 * <ul>
 *   <li>A bot that is in a channel that still has a streamer keeps it. Nobody takes a bot away from the room it is serving.</li>
 *   <li>The other channels with a streamer are served in the order they got their first streamer ("first come"), each by the first
 *       free bot (the bots are numbered; a bot that cannot see or join the channel is skipped).</li>
 *   <li>A channel with several streamers needs one bot. A bot that is in a channel with no streamer (or in a channel that another
 *       bot serves) is free: it moves to a waiting channel, or leaves.</li>
 *   <li>A channel for which no bot is free is reported as unserved.</li>
 * </ul>
 */
final class BotAssignment {

    private BotAssignment() {
    }

    /** One bot of the server: its number, and the id of the channel it is in now (null: in none). */
    record Bot(int index, Long channel) {
    }

    /** A channel that has a streamer in it, and since when (milliseconds). */
    record Wanted(long channel, long since) {
    }

    /**
     * What to do: {@code joins} (bot number to channel id; a bot that is in another channel moves), {@code leaves} (bot numbers that
     * are in a channel they should leave), and the channels nobody could be sent to.
     */
    record Plan(Map<Integer, Long> joins, List<Integer> leaves, List<Long> unserved) {
    }

    static Plan plan(List<Bot> bots, List<Wanted> wanted, BiPredicate<Integer, Long> canJoin) {
        Set<Long> wantedIds = new HashSet<>();
        for (Wanted w : wanted) {
            wantedIds.add(w.channel());
        }
        Set<Long> claimed = new HashSet<>();
        List<Bot> free = new ArrayList<>();
        for (Bot bot : bots) {
            if (bot.channel() != null && wantedIds.contains(bot.channel()) && claimed.add(bot.channel())) {
                continue;   // it keeps the room it is serving
            }
            free.add(bot);
        }

        List<Wanted> waiting = wanted.stream().filter(w -> !claimed.contains(w.channel()))
                .sorted(Comparator.comparingLong(Wanted::since).thenComparingLong(Wanted::channel)).toList();
        Map<Integer, Long> joins = new LinkedHashMap<>();
        List<Long> unserved = new ArrayList<>();
        for (Wanted w : waiting) {
            Bot chosen = null;
            for (Bot bot : free) {
                if (canJoin.test(bot.index(), w.channel())) {
                    chosen = bot;
                    break;
                }
            }
            if (chosen == null) {
                unserved.add(w.channel());
            } else {
                free.remove(chosen);
                joins.put(chosen.index(), w.channel());
            }
        }
        List<Integer> leaves = new ArrayList<>();
        for (Bot bot : free) {
            if (bot.channel() != null) {
                leaves.add(bot.index());
            }
        }
        return new Plan(joins, leaves, unserved);
    }
}
