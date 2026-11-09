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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the bot where it is needed, and answers "who is in this streamer's voice channel and who is speaking".
 *
 * <p>The bot joins by itself when a streamer (someone who has an overlay address and has not switched this off) enters a
 * voice channel, and leaves when no streamer is left in its channel. Discord allows one voice connection per server, so
 * in a server with two streamers in different channels the first one is served.
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
    private final Map<Long, SpeakingTracker> trackers = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile JDA jda;
    private volatile Runnable onChange = () -> { };

    VoiceCoordinator(StandeeStore store) {
        this.store = store;
    }

    void attach(JDA jda, Runnable onChange) {
        this.jda = jda;
        this.onChange = onChange;
    }

    // ---- what the overlay shows ------------------------------------------------------------------------

    Snapshot snapshot(long streamerId) {
        JDA jda = this.jda;
        if (jda == null) {
            return Snapshot.EMPTY;
        }
        for (Guild guild : jda.getGuilds()) {
            Member streamer = guild.getMemberById(streamerId);
            GuildVoiceState state = streamer == null ? null : streamer.getVoiceState();
            AudioChannel channel = state == null ? null : state.getChannel();
            if (channel == null) {
                continue;
            }
            AudioChannel botChannel = selfChannel(guild);
            boolean live = botChannel != null && botChannel.getIdLong() == channel.getIdLong();
            SpeakingTracker tracker = trackers.get(guild.getIdLong());
            Set<Long> talking = live && tracker != null ? tracker.speakingIds() : Set.of();

            List<Member> members = new ArrayList<>(channel.getMembers());
            members.removeIf(m -> m.getUser().isBot());
            members.sort(Comparator.<Member, Boolean>comparing(m -> m.getIdLong() != streamerId).thenComparingLong(Member::getIdLong));
            List<Snapshot.Person> people = new ArrayList<>();
            for (Member m : members) {
                String img = store.imageKey(guild.getIdLong(), m.getIdLong()).map(k -> "/img/" + k + ".png").orElse(null);
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
        Guild guild = event.getGuild();
        LATER.schedule(() -> reconcile(guild, true), 3, TimeUnit.SECONDS);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        Guild guild = event.getGuild();
        LATER.schedule(() -> reconcile(guild, false), 3, TimeUnit.SECONDS);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (member.getUser().isBot()) {
            if (member.equals(guild.getSelfMember()) && event.getChannelJoined() == null) {
                SpeakingTracker tracker = trackers.remove(guild.getIdLong());
                if (tracker != null) {
                    tracker.stop();
                }
            }
        } else {
            settle.trigger(guild.getIdLong(), SETTLE_MILLIS, TimeUnit.MILLISECONDS, () -> reconcile(guild, false));
        }
        onChange.run();
    }

    /** Brings the bot's place in the server in line with where streamers are. */
    private synchronized void reconcile(Guild guild, boolean startup) {
        try {
            AudioChannel current = selfChannel(guild);
            if (startup && current != null) {
                // Discord still lists the bot in a channel (the last run was stopped without leaving). Asking to join where it
                // already is changes nothing for Discord, so leave first; the resulting voice event then brings the bot back.
                LOG.info("Discord still lists the bot in '{}' ({}): leaving first", current.getName(), guild.getName());
                jda.getDirectAudioController().disconnect(guild);
                LATER.schedule(() -> reconcile(guild, false), 5, TimeUnit.SECONDS);
                return;
            }
            AudioChannel wanted = wantedChannel(guild, current);
            if (wanted == null) {
                if (current != null) {
                    leave(guild);
                }
                return;
            }
            if (current == null || current.getIdLong() != wanted.getIdLong()) {
                join(guild, wanted);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not update the voice connection in {}", guild.getName(), e);
        }
    }

    /** The channel that has a streamer in it; the one the bot is in already is kept when it qualifies. */
    private AudioChannel wantedChannel(Guild guild, AudioChannel current) {
        AudioChannel first = null;
        for (VoiceChannel channel : guild.getVoiceChannels()) {
            boolean hasStreamer = channel.getMembers().stream()
                    .anyMatch(m -> !m.getUser().isBot() && store.isAutoStreamer(m.getIdLong()));
            if (!hasStreamer) {
                continue;
            }
            if (current != null && channel.getIdLong() == current.getIdLong()) {
                return channel;
            }
            if (first == null) {
                first = channel;
            }
        }
        return first;
    }

    private void join(Guild guild, AudioChannel channel) {
        Member self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)) {
            if (warned.add(channel.getId())) {
                LOG.warn("No permission to see and join '{}' in {}", channel.getName(), guild.getName());
            }
            return;
        }
        AudioManager audio = guild.getAudioManager();
        SpeakingTracker tracker = trackers.computeIfAbsent(guild.getIdLong(), id -> new SpeakingTracker(() -> onChange.run()));
        audio.setSelfDeafened(false); // a deafened bot is sent no voice packets, which is all the speaking detection works from
        audio.setReceivingHandler(tracker);
        audio.setConnectionListener(new ConnectionListener() {
            @Override
            public void onPing(long ping) {
                // not interesting
            }

            @Override
            public void onStatusChange(ConnectionStatus status) {
                LOG.info("Voice connection in {}: {}", guild.getName(), status);
            }
        });
        LOG.info("Joining '{}' in {}", channel.getName(), guild.getName());
        audio.openAudioConnection(channel);
    }

    private void leave(Guild guild) {
        LOG.info("Leaving the voice channel in {}", guild.getName());
        SpeakingTracker tracker = trackers.remove(guild.getIdLong());
        if (tracker != null) {
            tracker.stop();
        }
        AudioManager audio = guild.getAudioManager();
        audio.setReceivingHandler(null);
        if (audio.getConnectionStatus() == ConnectionStatus.NOT_CONNECTED) {
            jda.getDirectAudioController().disconnect(guild);
        } else {
            audio.closeAudioConnection();
        }
    }

    /** Leaves every voice channel; for shutdown, so that Discord does not keep listing the bot. */
    void leaveAll() {
        JDA jda = this.jda;
        if (jda == null) {
            return;
        }
        for (Guild guild : jda.getGuilds()) {
            if (selfChannel(guild) != null) {
                leave(guild);
            }
        }
    }

    private static AudioChannel selfChannel(Guild guild) {
        GuildVoiceState state = guild.getSelfMember().getVoiceState();
        return state == null ? null : state.getChannel();
    }
}
