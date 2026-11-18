package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the bots where they are needed, and answers "who is in this streamer's voice channel and who is speaking".
 *
 * <p>A bot joins by itself when a streamer (someone who has an overlay address and has not switched this off) enters a
 * voice channel, and leaves when no streamer is left in its channel. Discord allows one voice connection per bot in a server, so
 * with one bot a server with two streamers in different channels serves the first one. With several bots (the first one is the main
 * bot, the others only listen in voice channels) several channels are served at once, see {@link BotAssignment}.
 */
final class VoiceCoordinator extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceCoordinator.class);
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-standee-later");
        t.setDaemon(true);
        return t;
    });

    /** How long a streamer has to stay put before the bot follows (so that channels they only pass through are skipped). */
    private static final long SETTLE_MILLIS = 2000;

    private final StandeeStore store;
    private final Debouncer settle = new Debouncer(LATER);
    /** The speaking trackers, by bot and server. */
    private final Map<String, SpeakingTracker> trackers = new ConcurrentHashMap<>();
    /** For each server, the channels that have a streamer and since when (so that the one that was first is served first). */
    private final Map<Long, Map<Long, Long>> since = new ConcurrentHashMap<>();
    /** The bot and server pairs whose leftover voice connection (from a run that was stopped) has been dealt with. */
    private final Set<String> cleaned = ConcurrentHashMap.newKeySet();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile List<JDA> bots = List.of();
    private volatile Runnable onChange = () -> { };

    VoiceCoordinator(StandeeStore store) {
        this.store = store;
    }

    /** The bots: the first one is the main bot (it sees who is where for the overlay). */
    void attach(List<JDA> bots, Runnable onChange) {
        this.bots = List.copyOf(bots);
        this.onChange = onChange;
    }

    /** Looks at every server once (at start, when the bots are all ready). */
    void reconcileAll() {
        List<JDA> list = bots;
        if (list.isEmpty()) {
            return;
        }
        for (Guild guild : list.get(0).getGuilds()) {
            long id = guild.getIdLong();
            LATER.schedule(() -> reconcile(id, true), 3, TimeUnit.SECONDS);
        }
    }

    private static String key(JDA bot, long guildId) {
        return bot.getSelfUser().getIdLong() + ":" + guildId;
    }

    // ---- what the overlay shows ------------------------------------------------------------------------

    Snapshot snapshot(long streamerId) {
        List<JDA> list = bots;
        if (list.isEmpty()) {
            return Snapshot.EMPTY;
        }
        for (Guild guild : list.get(0).getGuilds()) {
            Member streamer = guild.getMemberById(streamerId);
            GuildVoiceState state = streamer == null ? null : streamer.getVoiceState();
            AudioChannel channel = state == null ? null : state.getChannel();
            if (channel == null) {
                continue;
            }
            // live: one of the bots is in this channel; who is speaking comes from that bot
            boolean live = false;
            Set<Long> talking = Set.of();
            for (JDA bot : list) {
                Guild view = bot.getGuildById(guild.getIdLong());
                AudioChannel botChannel = view == null ? null : selfChannel(view);
                if (botChannel != null && botChannel.getIdLong() == channel.getIdLong()) {
                    live = true;
                    SpeakingTracker tracker = trackers.get(key(bot, guild.getIdLong()));
                    talking = tracker == null ? Set.of() : tracker.speakingIds();
                    break;
                }
            }

            List<Member> members = new ArrayList<>(channel.getMembers());
            members.removeIf(m -> m.getUser().isBot());
            members.sort(Comparator.<Member, Boolean>comparing(m -> m.getIdLong() != streamerId).thenComparingLong(Member::getIdLong));
            Set<Long> hidden = store.hidden(streamerId);
            List<Snapshot.Person> people = new ArrayList<>();
            for (Member m : members) {
                // a person the streamer has hidden is shown with their Discord avatar instead of their picture
                String img = hidden.contains(m.getIdLong()) ? null
                        : store.imageKey(guild.getIdLong(), m.getIdLong()).map(k -> "/img/" + k + ".png").orElse(null);
                people.add(new Snapshot.Person(m.getId(), m.getEffectiveName(), img,
                        m.getEffectiveAvatar().getUrl(256), talking.contains(m.getIdLong())));
            }
            return new Snapshot(live, people);
        }
        return Snapshot.EMPTY;
    }

    // ---- joining and leaving ---------------------------------------------------------------------------

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        long guildId = event.getGuild().getIdLong();
        LATER.schedule(() -> reconcile(guildId, true), 3, TimeUnit.SECONDS);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        long guildId = event.getGuild().getIdLong();
        LATER.schedule(() -> reconcile(guildId, false), 3, TimeUnit.SECONDS);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        long guildId = guild.getIdLong();
        if (member.getUser().isBot()) {
            if (member.equals(guild.getSelfMember()) && event.getChannelJoined() == null) {
                SpeakingTracker tracker = trackers.remove(key(event.getJDA(), guildId));
                if (tracker != null) {
                    tracker.stop();
                }
            }
        } else {
            settle.trigger(guildId, SETTLE_MILLIS, TimeUnit.MILLISECONDS, () -> reconcile(guildId, false));
        }
        onChange.run();
    }

    /**
     * A streamer's settings changed (they got an overlay address, or switched the automatic joining on or off): look at where they
     * are now, so that a bot comes to a voice channel they are already in, or leaves when they have just switched it off.
     */
    void streamerChanged(long userId) {
        List<JDA> list = bots;
        if (list.isEmpty()) {
            return;
        }
        for (Guild guild : list.get(0).getGuilds()) {
            Member member = guild.getMemberById(userId);
            GuildVoiceState state = member == null ? null : member.getVoiceState();
            if (state != null && state.getChannel() != null) {
                long guildId = guild.getIdLong();
                settle.trigger(guildId, 500, TimeUnit.MILLISECONDS, () -> reconcile(guildId, false));
            }
        }
    }

    /** Brings the places of the bots in the server in line with where streamers are. */
    private synchronized void reconcile(long guildId, boolean startup) {
        try {
            List<JDA> list = bots;
            if (list.isEmpty() || list.get(0).getGuildById(guildId) == null) {
                return;
            }
            // A bot that Discord still lists in a channel (the last run was stopped without leaving): asking to join where it already
            // is changes nothing for Discord, so it leaves first (once for each bot and server); the voice event then brings it back.
            boolean disconnected = false;
            for (JDA bot : list) {
                Guild view = bot.getGuildById(guildId);
                if (view == null) {
                    continue;
                }
                boolean first = cleaned.add(key(bot, guildId));
                AudioChannel current = selfChannel(view);
                if (startup && first && current != null) {
                    LOG.info("Discord still lists the bot {} in '{}' ({}): leaving first", bot.getSelfUser().getName(), current.getName(), view.getName());
                    bot.getDirectAudioController().disconnect(view);
                    disconnected = true;
                }
            }
            if (disconnected) {
                LATER.schedule(() -> reconcile(guildId, false), 5, TimeUnit.SECONDS);
                return;
            }

            Guild main = list.get(0).getGuildById(guildId);
            Map<Long, Long> channelSince = since.computeIfAbsent(guildId, id -> new ConcurrentHashMap<>());
            Set<Long> qualifying = new LinkedHashSet<>();
            for (VoiceChannel channel : main.getVoiceChannels()) {
                if (channel.getMembers().stream().anyMatch(m -> !m.getUser().isBot() && store.isAutoStreamer(m.getIdLong()))) {
                    qualifying.add(channel.getIdLong());
                }
            }
            channelSince.keySet().retainAll(qualifying);
            long now = System.currentTimeMillis();
            for (long id : qualifying) {
                channelSince.putIfAbsent(id, now);
            }

            List<BotAssignment.Bot> states = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Guild view = list.get(i).getGuildById(guildId);
                if (view != null) {
                    AudioChannel current = selfChannel(view);
                    states.add(new BotAssignment.Bot(i, current == null ? null : current.getIdLong()));
                }
            }
            List<BotAssignment.Wanted> wanted = new ArrayList<>();
            for (long id : qualifying) {
                wanted.add(new BotAssignment.Wanted(id, channelSince.get(id)));
            }
            BotAssignment.Plan plan = BotAssignment.plan(states, wanted, (index, channelId) -> mayJoin(list.get(index), guildId, channelId));

            for (int index : plan.leaves()) {
                leave(list.get(index), list.get(index).getGuildById(guildId));
            }
            for (Map.Entry<Integer, Long> entry : plan.joins().entrySet()) {
                JDA bot = list.get(entry.getKey());
                Guild view = bot.getGuildById(guildId);
                AudioChannel channel = view.getChannelById(AudioChannel.class, entry.getValue());
                if (channel != null) {
                    join(bot, view, channel);
                }
            }
            for (long channelId : plan.unserved()) {
                if (warned.add("busy:" + channelId)) {
                    LOG.warn("No free bot for the voice channel {} in {} (every bot is serving another channel, or cannot join)", channelId, main.getName());
                }
            }
            // a channel that is served later should be reported again if it is left without a bot another time
            warned.removeIf(w -> w.startsWith("busy:") && !plan.unserved().contains(Long.parseLong(w.substring(5))));
        } catch (RuntimeException e) {
            LOG.warn("Could not update the voice connections of server {}", guildId, e);
        }
    }

    /** Whether the bot can see and join the channel (a missing permission is reported once). */
    private boolean mayJoin(JDA bot, long guildId, long channelId) {
        Guild view = bot.getGuildById(guildId);
        AudioChannel channel = view == null ? null : view.getChannelById(AudioChannel.class, channelId);
        if (channel == null) {
            return false;
        }
        if (view.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)) {
            return true;
        }
        if (warned.add(bot.getSelfUser().getId() + ":" + channel.getId())) {
            LOG.warn("The bot {} has no permission to see and join '{}' in {}", bot.getSelfUser().getName(), channel.getName(), view.getName());
        }
        return false;
    }

    private void join(JDA bot, Guild guild, AudioChannel channel) {
        AudioManager audio = guild.getAudioManager();
        SpeakingTracker tracker = trackers.computeIfAbsent(key(bot, guild.getIdLong()), id -> new SpeakingTracker(() -> onChange.run()));
        audio.setSelfDeafened(false); // a deafened bot is sent no voice packets, which is all the speaking detection works from
        audio.setReceivingHandler(tracker);
        audio.setConnectionListener(new ConnectionListener() {
            @Override
            public void onPing(long ping) {
                // not interesting
            }

            @Override
            public void onStatusChange(ConnectionStatus status) {
                LOG.info("Voice connection of {} in {}: {}", bot.getSelfUser().getName(), guild.getName(), status);
            }
        });
        LOG.info("{} joins '{}' in {}", bot.getSelfUser().getName(), channel.getName(), guild.getName());
        audio.openAudioConnection(channel);
    }

    private void leave(JDA bot, Guild guild) {
        LOG.info("{} leaves the voice channel in {}", bot.getSelfUser().getName(), guild.getName());
        SpeakingTracker tracker = trackers.remove(key(bot, guild.getIdLong()));
        if (tracker != null) {
            tracker.stop();
        }
        AudioManager audio = guild.getAudioManager();
        audio.setReceivingHandler(null);
        if (audio.getConnectionStatus() == ConnectionStatus.NOT_CONNECTED) {
            bot.getDirectAudioController().disconnect(guild);
        } else {
            audio.closeAudioConnection();
        }
    }

    /** Leaves every voice channel; for shutdown, so that Discord does not keep listing the bots. */
    void leaveAll() {
        for (JDA bot : bots) {
            for (Guild guild : bot.getGuilds()) {
                if (selfChannel(guild) != null) {
                    leave(bot, guild);
                }
            }
        }
    }

    private static AudioChannel selfChannel(Guild guild) {
        GuildVoiceState state = guild.getSelfMember().getVoiceState();
        return state == null ? null : state.getChannel();
    }
}
