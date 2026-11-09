package com.yuaworks.voicestandee;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /standee} (shown as {@code /立ち絵} to Japanese users): register or remove the picture, get the address to put
 * into OBS, and switch the bot's automatic joining. Every answer is private to the person who asked.
 */
final class StandeeCommands extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(StandeeCommands.class);
    private static final DiscordLocale JA = DiscordLocale.JAPANESE;
    private static final long UPLOAD_COOLDOWN_MILLIS = 10_000;
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "voice-standee-image");
        t.setDaemon(true);
        return t;
    });

    private final Config config;
    private final StandeeStore store;
    private final Runnable onChange;
    private final Moderation moderation;
    private final Map<Long, Long> lastUpload = new ConcurrentHashMap<>();

    StandeeCommands(Config config, StandeeStore store, Runnable onChange, Moderation moderation) {
        this.config = config;
        this.store = store;
        this.onChange = onChange;
        this.moderation = moderation;
    }

    static SlashCommandData definition() {
        return Commands.slash("standee", "Your standee picture and the OBS overlay")
                .setNameLocalization(JA, "立ち絵")
                .setDescriptionLocalization(JA, "立ち絵の登録と、OBS用の表示")
                .addSubcommands(
                        new SubcommandData("set", "Register your standee picture for this server (or for all servers)")
                                .setNameLocalization(JA, "登録")
                                .setDescriptionLocalization(JA, "このサーバー用の立ち絵を登録する(DMや「共通」なら全サーバー共通)")
                                .addOptions(new OptionData(OptionType.ATTACHMENT, "image", "PNG / JPG / GIF, up to 3 MB", true)
                                                .setNameLocalization(JA, "画像")
                                                .setDescriptionLocalization(JA, "立ち絵にする画像(PNG / JPG / GIF、3MBまで)"),
                                        sharedOption("Register it for all servers instead of only this one",
                                                "このサーバーだけでなく、全サーバー共通の立ち絵にする")),
                        new SubcommandData("show", "Show the picture that is used in this server")
                                .setNameLocalization(JA, "確認")
                                .setDescriptionLocalization(JA, "このサーバーで使われる立ち絵を見る")
                                .addOptions(sharedOption("Show the picture shared by all servers", "全サーバー共通の立ち絵を見る")),
                        new SubcommandData("remove", "Remove the picture registered for this server")
                                .setNameLocalization(JA, "削除")
                                .setDescriptionLocalization(JA, "このサーバー用の立ち絵を削除する")
                                .addOptions(sharedOption("Remove the picture shared by all servers", "全サーバー共通の立ち絵を削除する")),
                        new SubcommandData("obs", "Get the address to put into OBS")
                                .setDescriptionLocalization(JA, "OBSのブラウザソースに貼るURLを表示する")
                                .addOptions(new OptionData(OptionType.BOOLEAN, "reset", "Issue a new address; the old one stops working", false)
                                        .setNameLocalization(JA, "再発行")
                                        .setDescriptionLocalization(JA, "URLを作り直す(古いURLは使えなくなります)")),
                        new SubcommandData("auto", "Let the bot join your voice channel by itself, or not")
                                .setNameLocalization(JA, "自動参加")
                                .setDescriptionLocalization(JA, "ボイスチャンネルに入ったとき、Botが自動で入るかどうか")
                                .addOptions(new OptionData(OptionType.BOOLEAN, "enabled", "On or off", true)
                                        .setNameLocalization(JA, "有効")
                                        .setDescriptionLocalization(JA, "オン / オフ")),
                        new SubcommandData("hide", "On your overlay, show this person's Discord avatar instead of their standee")
                                .setNameLocalization(JA, "非表示")
                                .setDescriptionLocalization(JA, "あなたのOBS表示で、その人の立ち絵をDiscordのアイコンに置き換える")
                                .addOptions(personOption()),
                        new SubcommandData("unhide", "Show this person's standee on your overlay again")
                                .setNameLocalization(JA, "非表示解除")
                                .setDescriptionLocalization(JA, "その人の立ち絵を、あなたのOBS表示に戻す")
                                .addOptions(personOption()),
                        Moderation.reportSubcommand());
    }

    private static OptionData personOption() {
        return new OptionData(OptionType.USER, "user", "The person", true)
                .setNameLocalization(JA, "相手")
                .setDescriptionLocalization(JA, "対象の人");
    }

    private static OptionData sharedOption(String description, String descriptionJa) {
        return new OptionData(OptionType.BOOLEAN, "shared", description, false)
                .setNameLocalization(JA, "共通")
                .setDescriptionLocalization(JA, descriptionJa);
    }

    /** Which picture a command is about: this server's own, or the shared one (always in a DM, or when asked for). */
    private static long scopeOf(SlashCommandInteractionEvent event) {
        boolean shared = event.getOption("shared") != null && event.getOption("shared").getAsBoolean();
        return shared || !event.isFromGuild() ? StandeeStore.SHARED : event.getGuild().getIdLong();
    }

    private static String scopeName(SlashCommandInteractionEvent event, long scope) {
        return scope == StandeeStore.SHARED ? "全サーバー共通" : "このサーバー「" + event.getGuild().getName() + "」用";
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("standee")) {
            return;
        }
        try {
            switch (String.valueOf(event.getSubcommandName())) {
                case "set" -> set(event);
                case "show" -> show(event);
                case "remove" -> remove(event);
                case "obs" -> obs(event);
                case "auto" -> auto(event);
                case "hide" -> hide(event, true);
                case "unhide" -> hide(event, false);
                case "report" -> moderation.report(event);
                default -> reply(event, "?");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Command /standee {} failed", event.getSubcommandName(), e);
            if (event.isAcknowledged()) {
                event.getHook().editOriginal("エラーが起きました。少し待ってから、もう一度お試しください。").queue();
            } else {
                reply(event, "エラーが起きました。少し待ってから、もう一度お試しください。");
            }
        }
    }

    private void set(SlashCommandInteractionEvent event) {
        long userId = event.getUser().getIdLong();
        if (store.isBanned(userId)) {
            reply(event, "いま、立ち絵を登録できない状態です。心当たりがない場合は、Botの管理者に連絡してください。");
            return;
        }
        Message.Attachment attachment = event.getOption("image").getAsAttachment();
        long now = System.currentTimeMillis();
        Long last = lastUpload.get(userId);
        if (last != null && now - last < UPLOAD_COOLDOWN_MILLIS) {
            reply(event, "続けて登録するときは、少し(10秒ほど)待ってください。");
            return;
        }
        if (attachment.getSize() > ImageIntake.MAX_BYTES) {
            reply(event, "ファイルが大きすぎます(" + ImageIntake.MAX_BYTES / 1_000_000 + "MBまで)。");
            return;
        }
        lastUpload.put(userId, now);
        long scope = scopeOf(event);
        String scopeName = scopeName(event, scope);
        event.deferReply(true).queue();
        attachment.getProxy().download().thenAcceptAsync(in -> process(event, userId, scope, scopeName, in), WORK).exceptionally(err -> {
            LOG.warn("Could not download an uploaded picture", err);
            event.getHook().editOriginal("画像を受け取れませんでした。もう一度お試しください。").queue();
            return null;
        });
    }

    private void process(SlashCommandInteractionEvent event, long userId, long scope, String scopeName, InputStream in) {
        try (in) {
            byte[] data = in.readNBytes(ImageIntake.MAX_BYTES + 1);
            byte[] png = ImageIntake.toStandeePng(data);
            if (!store.setImage(userId, scope, png)) {
                event.getHook().editOriginal("サーバー別の立ち絵は、1人" + StandeeStore.MAX_SERVER_PICTURES
                        + "サーバー分までです。使わないサーバーの立ち絵を、先に削除してください。").queue();
                return;
            }
            onChange.run();
            LOG.info("Picture registered: user={} scope={}", userId, scope == StandeeStore.SHARED ? "shared" : scope);
            String priority = scope == StandeeStore.SHARED
                    ? "サーバー用の立ち絵を登録しているサーバーでは、そちらが優先されます。"
                    : "このサーバーでは、共通の立ち絵より、こちらが優先されます。";
            String next = store.token(userId).isPresent()
                    ? "OBS側の表示は、そのまま新しい立ち絵に切り替わります。"
                    : "OBSで使うには、`/立ち絵 obs` でURLを出して、ブラウザソースに貼ってください。";
            String dm = scope == StandeeStore.SHARED && !event.isFromGuild()
                    ? "(DMでの登録は、全サーバー共通になります。サーバー用は、そのサーバーで登録してください。)\n" : "";
            event.getHook().editOriginal(new MessageEditBuilder()
                    .setContent("立ち絵を登録しました(" + scopeName + "、この形で表示されます)。\n" + priority + "\n" + dm + next)
                    .setAttachments(FileUpload.fromData(png, "standee.png"))
                    .build()).queue();
        } catch (ImageIntake.Rejected rejected) {
            event.getHook().editOriginal("登録できませんでした。" + rejected.getMessage()).queue();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not register a picture", e);
            event.getHook().editOriginal("エラーが起きました。少し待ってから、もう一度お試しください。").queue();
        }
    }

    private void show(SlashCommandInteractionEvent event) {
        long userId = event.getUser().getIdLong();
        long scope = scopeOf(event);
        boolean ownPicture = scope != StandeeStore.SHARED && store.imageKeyOf(userId, scope).isPresent();
        // in a server the shared picture stands in when the server has none of its own
        var key = scope == StandeeStore.SHARED ? store.imageKey(userId) : store.imageKey(scope, userId);
        var bytes = key.flatMap(store::readImage);
        if (bytes.isEmpty()) {
            reply(event, scope == StandeeStore.SHARED
                    ? "全サーバー共通の立ち絵は、まだ登録されていません。"
                    : "この場所で使われる立ち絵は、まだ登録されていません(Discordのアイコンが表示されます)。`/立ち絵 登録` で画像を付けてください。");
            return;
        }
        String which = scope == StandeeStore.SHARED ? "全サーバー共通の立ち絵です。"
                : ownPicture ? "このサーバー用の立ち絵です。" : "このサーバー用の立ち絵はないので、全サーバー共通の立ち絵が使われます。";
        event.reply(which).setFiles(FileUpload.fromData(bytes.get(), "standee.png")).setEphemeral(true).queue();
    }

    private void remove(SlashCommandInteractionEvent event) throws IOException {
        long scope = scopeOf(event);
        boolean had = store.removeImage(event.getUser().getIdLong(), scope);
        onChange.run();
        if (!had) {
            reply(event, scope == StandeeStore.SHARED ? "全サーバー共通の立ち絵は登録されていません。" : "このサーバー用の立ち絵は登録されていません。");
        } else if (scope == StandeeStore.SHARED) {
            reply(event, "全サーバー共通の立ち絵を削除しました。サーバー用の立ち絵がないサーバーでは、Discordのアイコンが表示されます。");
        } else {
            reply(event, "このサーバー用の立ち絵を削除しました。共通の立ち絵があればそれが、なければDiscordのアイコンが表示されます。");
        }
    }

    private void obs(SlashCommandInteractionEvent event) throws IOException {
        long userId = event.getUser().getIdLong();
        boolean reset = event.getOption("reset") != null && event.getOption("reset").getAsBoolean();
        String token = reset ? store.resetToken(userId) : store.ensureToken(userId);
        String url = config.baseUrl() + "/o/" + token;
        StringBuilder text = new StringBuilder();
        text.append(reset ? "URLを作り直しました。古いURLは使えなくなりました。\n\n" : "");
        text.append("OBSの「ブラウザ」ソースに、次のURLを貼ってください。**このURLは他の人に見せないでください**(あなた専用です)。\n");
        text.append("```\n").append(url).append("\n```");
        text.append("・表示されるのは、あなたと同じボイスチャンネルにいる人です。立ち絵が未登録の人は、Discordのアイコンで表示されます。\n");
        text.append("・Botは、あなたがこのBotのいるサーバーのボイスチャンネルに入ると、自動で入ります(`/立ち絵 自動参加` で切り替え)。\n");
        text.append("・Botは「誰が話しているか」だけを見ています。音声は聞かず、保存もしません。\n");
        text.append("・見た目(大きさ、跳ね方、並びなど)は、次の設定ページで調整できます。変更はOBSにすぐ反映されます。\n");
        text.append("```\n").append(url).append("/settings\n```");
        if (config.baseUrlIsLocal()) {
            text.append("\n\n(いまは、このBotを動かしているPCだけで開けるURLです。)");
        }
        reply(event, text.toString());
    }

    private void auto(SlashCommandInteractionEvent event) throws IOException {
        boolean enabled = event.getOption("enabled").getAsBoolean();
        store.setAuto(event.getUser().getIdLong(), enabled);
        onChange.run();
        reply(event, enabled
                ? "自動参加をオンにしました。あなたがボイスチャンネルに入ると、Botも入ります。"
                : "自動参加をオフにしました。Botは自分では入らなくなります。");
    }

    private void hide(SlashCommandInteractionEvent event, boolean hide) throws IOException {
        long streamer = event.getUser().getIdLong();
        var target = event.getOption("user").getAsUser();
        String name = target.getName();
        if (hide) {
            if (!store.hide(streamer, target.getIdLong())) {
                reply(event, "非表示にできるのは、" + StandeeStore.MAX_HIDDEN + "人までです。");
                return;
            }
            onChange.run();
            reply(event, "あなたのOBS表示で、" + name + " さんの立ち絵を、Discordのアイコンに置き換えました。"
                    + "元に戻すには `/立ち絵 非表示解除` を使ってください。\n"
                    + "不適切な立ち絵は、`/立ち絵 報告` で、Botの管理者に知らせることもできます。");
        } else {
            boolean had = store.unhide(streamer, target.getIdLong());
            onChange.run();
            reply(event, had ? name + " さんの立ち絵を、あなたのOBS表示に戻しました。" : name + " さんは、非表示にしていません。");
        }
    }

    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).queue();
    }

    /** The command definitions to register. */
    static List<SlashCommandData> definitions() {
        return List.of(definition(), Moderation.adminDefinition());
    }
}
