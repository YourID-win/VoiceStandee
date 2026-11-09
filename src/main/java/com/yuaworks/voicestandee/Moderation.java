package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What can be done about a picture somebody should not have put up.
 *
 * <ul>
 *   <li>Anyone can report a person's picture ({@code /standee report}). The report goes to the bot's owner as a private
 *       message with the picture and buttons: delete everything of that person, delete and ban, or do nothing.</li>
 *   <li>The owner has {@code /standee-admin}: look at a person's pictures, delete them, ban or unban a person.</li>
 *   <li>Streamers can hide a person on their own overlay (that is in {@link StandeeCommands}).</li>
 * </ul>
 *
 * Only the user named in VOICE_STANDEE_OWNER can do any of the owner's things; the commands are checked on every use, not
 * just hidden. Everything is logged without pictures.
 */
final class Moderation extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(Moderation.class);
    private static final DiscordLocale JA = DiscordLocale.JAPANESE;
    private static final String BUTTON_PREFIX = "mod:";
    private static final int MAX_REASON = 300;
    private static final int MAX_FILES = 10;

    private final Config config;
    private final StandeeStore store;
    private final Runnable onChange;
    private final RateLimiter reports = new RateLimiter(3, Duration.ofHours(1), System::nanoTime);

    Moderation(Config config, StandeeStore store, Runnable onChange) {
        this.config = config;
        this.store = store;
        this.onChange = onChange;
    }

    // ---- definitions -----------------------------------------------------------------------------------

    /** The owner's command. It is switched off for everybody by default (a server admin could turn it on, but it still checks the owner). */
    static SlashCommandData adminDefinition() {
        return Commands.slash("standee-admin", "Moderation of standee pictures (the bot's owner only)")
                .setNameLocalization(JA, "立ち絵管理")
                .setDescriptionLocalization(JA, "立ち絵の確認・削除・登録禁止(Botの管理者のみ)")
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .addSubcommands(
                        userSubcommand("inspect", "Look at a person's pictures", "確認", "その人の立ち絵を見る"),
                        userSubcommand("remove", "Delete all pictures of a person", "削除", "その人の立ち絵を、すべて削除する"),
                        userSubcommand("ban", "Delete all pictures of a person and stop them from registering new ones",
                                "登録禁止", "立ち絵をすべて削除し、新しい登録もできなくする"),
                        userSubcommand("unban", "Allow a person to register pictures again", "禁止解除", "登録禁止を解除する"));
    }

    private static SubcommandData userSubcommand(String name, String description, String nameJa, String descriptionJa) {
        return new SubcommandData(name, description)
                .setNameLocalization(JA, nameJa)
                .setDescriptionLocalization(JA, descriptionJa)
                .addOptions(new OptionData(OptionType.USER, "user", "The person", true)
                        .setNameLocalization(JA, "相手")
                        .setDescriptionLocalization(JA, "対象の人"));
    }

    // ---- /standee report -------------------------------------------------------------------------------

    /** The subcommand a user sees under /standee. */
    static SubcommandData reportSubcommand() {
        return new SubcommandData("report", "Report a person's standee picture to the bot's owner")
                .setNameLocalization(JA, "報告")
                .setDescriptionLocalization(JA, "不適切な立ち絵を、管理者に報告する")
                .addOptions(
                        new OptionData(OptionType.USER, "user", "The person whose picture you are reporting", true)
                                .setNameLocalization(JA, "相手")
                                .setDescriptionLocalization(JA, "立ち絵を報告したい人"),
                        new OptionData(OptionType.STRING, "reason", "What is wrong with it (optional)", false)
                                .setNameLocalization(JA, "理由")
                                .setDescriptionLocalization(JA, "何が問題か(任意)")
                                .setMaxLength(MAX_REASON));
    }

    void report(SlashCommandInteractionEvent event) {
        if (!config.hasOwner()) {
            event.reply("報告の受付先が設定されていません。Botの運営者に、直接連絡してください。").setEphemeral(true).queue();
            return;
        }
        User reporter = event.getUser();
        User target = event.getOption("user").getAsUser();
        String reason = event.getOption("reason") == null ? "" : event.getOption("reason").getAsString();
        if (target.isBot() || target.getIdLong() == reporter.getIdLong()) {
            event.reply("その相手は、報告の対象にできません。").setEphemeral(true).queue();
            return;
        }
        long guildId = event.isFromGuild() ? event.getGuild().getIdLong() : StandeeStore.SHARED;
        var key = store.imageKey(guildId, target.getIdLong());
        if (key.isEmpty()) {
            key = store.anyImageKey(target.getIdLong());
        }
        var bytes = key.flatMap(store::readImage);
        if (bytes.isEmpty()) {
            event.reply("その人は、立ち絵を登録していません。").setEphemeral(true).queue();
            return;
        }
        if (!reports.tryAcquire(reporter.getIdLong())) {
            event.reply("報告は、1時間に3件までです。少し時間をおいてから、もう一度お試しください。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();

        String where = event.isFromGuild() ? event.getGuild().getName() + "(" + event.getGuild().getId() + ")" : "DM";
        String text = "**立ち絵の報告**\n"
                + "報告した人: " + reporter.getName() + "(" + reporter.getId() + ")\n"
                + "報告された人: " + target.getName() + "(" + target.getId() + ")\n"
                + "場所: " + where + "\n"
                + "理由: " + (reason.isBlank() ? "(なし)" : reason);
        var message = new MessageCreateBuilder()
                .setContent(text)
                .addFiles(FileUpload.fromData(bytes.get(), "reported.png"))
                .setComponents(ActionRow.of(
                        Button.danger(BUTTON_PREFIX + "remove:" + target.getId(), "この人の立ち絵を、すべて削除"),
                        Button.danger(BUTTON_PREFIX + "ban:" + target.getId(), "削除して、登録も禁止"),
                        Button.secondary(BUTTON_PREFIX + "dismiss:" + target.getId(), "問題なし")))
                .build();
        LOG.info("Report: reporter={} target={} guild={}", reporter.getId(), target.getId(), event.isFromGuild() ? event.getGuild().getId() : "dm");
        event.getJDA().retrieveUserById(config.ownerId())
                .flatMap(User::openPrivateChannel)
                .flatMap(channel -> channel.sendMessage(message))
                .queue(sent -> event.getHook().editOriginal("報告しました。ありがとうございます。管理者が確認します。").queue(),
                        failure -> {
                            LOG.warn("Could not send a report to the owner", failure);
                            event.getHook().editOriginal("報告を送れませんでした。Botの運営者に、直接連絡してください。").queue();
                        });
    }

    // ---- the owner's buttons ---------------------------------------------------------------------------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(BUTTON_PREFIX)) {
            return;
        }
        if (!isOwner(event.getUser())) {
            event.reply("この操作は、Botの管理者だけができます。").setEphemeral(true).queue();
            return;
        }
        String[] parts = id.substring(BUTTON_PREFIX.length()).split(":");
        if (parts.length != 2) {
            return;
        }
        long target;
        try {
            target = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        String result;
        try {
            switch (parts[0]) {
                case "remove" -> {
                    int removed = store.removeAllImages(target);
                    onChange.run();
                    LOG.info("Moderation: owner removed {} picture(s) of {}", removed, target);
                    result = "→ 対応済み: " + removed + "枚を削除しました。";
                }
                case "ban" -> {
                    int removed = store.ban(target);
                    onChange.run();
                    LOG.info("Moderation: owner banned {} ({} picture(s) removed)", target, removed);
                    result = "→ 対応済み: " + removed + "枚を削除し、登録を禁止しました。";
                }
                case "dismiss" -> result = "→ 対応済み: 問題なしとしました。";
                default -> {
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Moderation action failed", e);
            event.reply("処理に失敗しました。ログを確認してください。").setEphemeral(true).queue();
            return;
        }
        event.editMessage(event.getMessage().getContentRaw() + "\n" + result).setComponents().queue();
    }

    // ---- /standee-admin --------------------------------------------------------------------------------

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("standee-admin")) {
            return;
        }
        if (!isOwner(event.getUser())) {
            event.reply("このコマンドは、Botの管理者だけが使えます。").setEphemeral(true).queue();
            return;
        }
        User target = event.getOption("user").getAsUser();
        try {
            switch (String.valueOf(event.getSubcommandName())) {
                case "inspect" -> inspect(event, target);
                case "remove" -> {
                    int removed = store.removeAllImages(target.getIdLong());
                    onChange.run();
                    LOG.info("Moderation: owner removed {} picture(s) of {}", removed, target.getId());
                    event.reply(target.getName() + " さんの立ち絵を、" + removed + "枚削除しました。").setEphemeral(true).queue();
                }
                case "ban" -> {
                    int removed = store.ban(target.getIdLong());
                    onChange.run();
                    LOG.info("Moderation: owner banned {} ({} picture(s) removed)", target.getId(), removed);
                    event.reply(target.getName() + " さんの立ち絵を" + removed + "枚削除し、登録を禁止しました。").setEphemeral(true).queue();
                }
                case "unban" -> {
                    boolean had = store.unban(target.getIdLong());
                    LOG.info("Moderation: owner unbanned {}", target.getId());
                    event.reply(had ? target.getName() + " さんの登録禁止を解除しました。" : "その人は、禁止されていません。")
                            .setEphemeral(true).queue();
                }
                default -> event.reply("?").setEphemeral(true).queue();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Command /standee-admin {} failed", event.getSubcommandName(), e);
            event.reply("処理に失敗しました。ログを確認してください。").setEphemeral(true).queue();
        }
    }

    private void inspect(SlashCommandInteractionEvent event, User target) {
        long id = target.getIdLong();
        JDA jda = event.getJDA();
        StringBuilder text = new StringBuilder(target.getName() + "(" + target.getId() + ")\n");
        text.append("登録禁止: ").append(store.isBanned(id) ? "はい" : "いいえ").append('\n');
        List<FileUpload> files = new ArrayList<>();
        store.imageKey(id).flatMap(store::readImage).ifPresentOrElse(png -> {
            files.add(FileUpload.fromData(png, "shared.png"));
            text.append("全サーバー共通: あり(shared.png)\n");
        }, () -> text.append("全サーバー共通: なし\n"));
        Map<Long, String> servers = store.serverImageKeys(id);
        text.append("サーバー別: ").append(servers.size()).append("件\n");
        for (Map.Entry<Long, String> entry : servers.entrySet()) {
            if (files.size() >= MAX_FILES) {
                text.append("  (表示は").append(MAX_FILES).append("枚まで)\n");
                break;
            }
            var guild = jda.getGuildById(entry.getKey());
            String name = guild == null ? "不明なサーバー" : guild.getName();
            store.readImage(entry.getValue()).ifPresent(png -> {
                files.add(FileUpload.fromData(png, "server-" + entry.getKey() + ".png"));
                text.append("  ").append(name).append("(").append(entry.getKey()).append(") → server-").append(entry.getKey()).append(".png\n");
            });
        }
        event.deferReply(true).queue();
        event.getHook().editOriginal(new MessageEditBuilder().setContent(text.toString()).setAttachments(files).build()).queue();
    }

    private boolean isOwner(User user) {
        return config.hasOwner() && user.getIdLong() == config.ownerId();
    }
}
