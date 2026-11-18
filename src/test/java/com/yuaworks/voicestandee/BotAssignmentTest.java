package com.yuaworks.voicestandee;

import com.yuaworks.voicestandee.BotAssignment.Bot;
import com.yuaworks.voicestandee.BotAssignment.Plan;
import com.yuaworks.voicestandee.BotAssignment.Wanted;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotAssignmentTest {

    private static final BiPredicate<Integer, Long> ANYWHERE = (bot, channel) -> true;

    private static Plan plan(List<Bot> bots, List<Wanted> wanted) {
        return BotAssignment.plan(bots, wanted, ANYWHERE);
    }

    @Test
    void oneBotJoinsTheChannelThatWasFirstAndLeavesTheOthersUnserved() {
        Plan p = plan(List.of(new Bot(0, null)), List.of(new Wanted(20, 2000), new Wanted(10, 1000)));
        assertEquals(Map.of(0, 10L), p.joins());
        assertEquals(List.of(20L), p.unserved(), "the later channel waits");
        assertEquals(List.of(), p.leaves());
    }

    @Test
    void aBotKeepsItsRoomEvenWhenAnotherRoomWasFirst() {
        // the bot is in 20; room 10 got its streamer earlier, but nobody takes a bot away from the room it serves
        Plan p = plan(List.of(new Bot(0, 20L)), List.of(new Wanted(10, 1000), new Wanted(20, 2000)));
        assertEquals(Map.of(), p.joins());
        assertEquals(List.of(10L), p.unserved());
        assertEquals(List.of(), p.leaves());
    }

    @Test
    void twoBotsServeTwoRoomsAndAThirdRoomWaits() {
        Plan p = plan(List.of(new Bot(0, null), new Bot(1, null)),
                List.of(new Wanted(30, 3000), new Wanted(10, 1000), new Wanted(20, 2000)));
        assertEquals(Map.of(0, 10L, 1, 20L), p.joins(), "in the order the rooms got their first streamer");
        assertEquals(List.of(30L), p.unserved());
    }

    @Test
    void aRoomWithSeveralStreamersNeedsOneBot() {
        // the rooms are listed once each, however many streamers they have: the second bot stays free
        Plan p = plan(List.of(new Bot(0, 10L), new Bot(1, null)), List.of(new Wanted(10, 1000)));
        assertEquals(Map.of(), p.joins());
        assertEquals(List.of(), p.unserved());
        assertEquals(List.of(), p.leaves());
    }

    @Test
    void aBotInARoomWithNoStreamerLeavesOrMovesToAWaitingRoom() {
        Plan leaves = plan(List.of(new Bot(0, 10L), new Bot(1, 20L)), List.of(new Wanted(20, 2000)));
        assertEquals(List.of(0), leaves.leaves());
        assertEquals(Map.of(), leaves.joins());

        Plan moves = plan(List.of(new Bot(0, 10L), new Bot(1, 20L)), List.of(new Wanted(20, 2000), new Wanted(30, 3000)));
        assertEquals(Map.of(0, 30L), moves.joins(), "bot 0 has lost its room and goes to the waiting one");
        assertEquals(List.of(), moves.leaves());
    }

    @Test
    void twoBotsInTheSameRoomAreSortedOut() {
        Plan p = plan(List.of(new Bot(0, 10L), new Bot(1, 10L)), List.of(new Wanted(10, 1000)));
        assertEquals(List.of(1), p.leaves(), "the second one is free and has nowhere to go");
        assertEquals(Map.of(), p.joins());
    }

    @Test
    void aBotThatCannotJoinAChannelIsSkipped() {
        BiPredicate<Integer, Long> bot0CannotSee10 = (bot, channel) -> !(bot == 0 && channel == 10);
        Plan p = BotAssignment.plan(List.of(new Bot(0, null), new Bot(1, null)), List.of(new Wanted(10, 1000)), bot0CannotSee10);
        assertEquals(Map.of(1, 10L), p.joins());

        Plan none = BotAssignment.plan(List.of(new Bot(0, null)), List.of(new Wanted(10, 1000)), bot0CannotSee10);
        assertEquals(List.of(10L), none.unserved());
    }

    @Test
    void nobodyWantedMeansEveryBotLeavesAndNothingIsJoined() {
        Plan p = plan(List.of(new Bot(0, 10L), new Bot(1, null)), List.of());
        assertEquals(List.of(0), p.leaves());
        assertEquals(Map.of(), p.joins());
        assertEquals(List.of(), p.unserved());
    }
}
