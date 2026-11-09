package com.yuaworks.voicestandee;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.concurrent.TimeUnit;

/**
 * VoiceStandee: the bot that sits in a voice channel, and the web page OBS shows. See README.md.
 *
 * <p>Needs the environment variable VOICE_STANDEE_TOKEN; the others are optional (see {@link Config}).
 */
public final class StandeeApp {

    private StandeeApp() {
    }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        StandeeStore store = new StandeeStore(config.dataDir());
        VoiceCoordinator voice = new VoiceCoordinator(store);
        OverlayServer server = new OverlayServer(config.bind(), config.port(), store, voice::snapshot);
        StandeeCommands commands = new StandeeCommands(config, store, server::publish);

        JDA jda = JDABuilder.createLight(config.discordToken(), GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                .addEventListeners(voice, commands)
                .build()
                .awaitReady();
        voice.attach(jda, server::publish);
        server.start();

        if (!config.devGuildId().isBlank() && jda.getGuildById(config.devGuildId()) != null) {
            jda.getGuildById(config.devGuildId()).updateCommands().addCommands(StandeeCommands.definitions()).queue();
        } else {
            jda.updateCommands().addCommands(StandeeCommands.definitions()).queue();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            voice.leaveAll();
            server.stop();
            jda.shutdown();
            try {
                jda.awaitShutdown(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "voice-standee-shutdown"));

        System.out.println("Ready as " + jda.getSelfUser().getName() + ". Overlay server on " + config.bind() + ":" + server.port()
                + ", addresses are built on " + config.baseUrl());
    }
}
