package com.yuaworks.voicestandee;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;

/**
 * The first prototype: can the bot sit in a voice channel and learn who is speaking?
 * It only logs. It does not listen to, decode or store any audio.
 *
 * <p>Needs the environment variable VOICE_STANDEE_TOKEN (the bot token). Optionally VOICE_STANDEE_GUILD (the test server's id),
 * which makes the slash commands appear at once instead of after up to an hour.
 *
 * <p>In Discord: join a voice channel, then type {@code /probe join}. Talk, stay quiet, talk with someone else, and watch the log.
 * {@code /probe leave} makes the bot go.
 */
public final class SpeakingProbe extends ListenerAdapter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        String token = System.getenv("VOICE_STANDEE_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Set the environment variable VOICE_STANDEE_TOKEN first.");
            System.exit(1);
        }
        JDA jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                .addEventListeners(new SpeakingProbe())
                .build()
                .awaitReady();

        var commands = Commands.slash("probe", "Voice speaking test")
                .addSubcommands(
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("join", "Join your voice channel")
                                .addOption(OptionType.BOOLEAN, "deafen", "Join with the speakers off (default: yes)", false),
                        new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("leave", "Leave the voice channel"));
        String guildId = System.getenv("VOICE_STANDEE_GUILD");
        if (guildId != null && !guildId.isBlank()) {
            jda.getGuildById(guildId).updateCommands().addCommands(commands).queue();
        } else {
            jda.updateCommands().addCommands(commands).queue();
        }
        System.out.println("Ready as " + jda.getSelfUser().getName() + ". Waiting for /probe join");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("probe") || event.getGuild() == null) {
            return;
        }
        AudioManager manager = event.getGuild().getAudioManager();
        switch (String.valueOf(event.getSubcommandName())) {
            case "join" -> {
                var voice = event.getMember().getVoiceState();
                AudioChannel channel = voice == null ? null : voice.getChannel();
                if (channel == null) {
                    event.reply("Join a voice channel first.").setEphemeral(true).queue();
                    return;
                }
                boolean deafen = event.getOption("deafen") == null || event.getOption("deafen").getAsBoolean();
                manager.setSelfDeafened(deafen);
                manager.setConnectionListener(new Listener());
                manager.openAudioConnection(channel);
                log("joining " + channel.getName() + " (self-deafened=" + deafen + ")");
                event.reply("Joining **" + channel.getName() + "**. I only watch who is speaking. I do not listen to or store any audio.")
                        .setEphemeral(true).queue();
            }
            case "leave" -> {
                manager.closeAudioConnection();
                log("left");
                event.reply("Left.").setEphemeral(true).queue();
            }
            default -> event.reply("?").setEphemeral(true).queue();
        }
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        String from = event.getChannelLeft() == null ? "-" : event.getChannelLeft().getName();
        String to = event.getChannelJoined() == null ? "-" : event.getChannelJoined().getName();
        log("voice: " + event.getMember().getEffectiveName() + "  " + from + " -> " + to);
    }

    private static final class Listener implements ConnectionListener {
        @Override
        public void onPing(long ping) {
            // quiet: the voice connection's ping is not interesting here
        }

        @Override
        public void onStatusChange(ConnectionStatus status) {
            log("connection: " + status);
        }

        @Override
        public void onUserSpeakingModeUpdate(User user, EnumSet<SpeakingMode> modes) {
            log("speaking: " + user.getName() + "  " + (modes.isEmpty() ? "STOPPED" : modes.toString()));
        }

        @Override
        public void onUserSpeakingModeUpdate(UserSnowflake user, EnumSet<SpeakingMode> modes) {
            log("speaking (id only): " + user.getId() + "  " + (modes.isEmpty() ? "STOPPED" : modes.toString()));
        }
    }

    private static void log(String message) {
        System.out.println(LocalTime.now().format(TIME) + "  " + message);
    }
}
