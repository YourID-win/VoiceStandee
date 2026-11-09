# サーバーへの設置と公開(Linux + Cloudflare Tunnel)

Ubuntu 24.04 のサーバーに、systemd のサービスとして置きます。OBS 用のページは、サーバーの内側(`127.0.0.1:8780`)だけで待ち受け、
外へは Cloudflare Tunnel が出します。ルーターのポート開放は要りません。

`sudo` が要る手順は、すべて「`--check-only` で内容を見る → 問題なければ実行」の2段階になっています。

## 0. 前提

- Java 25(`java -version`)。Minecraft 用に入っているものをそのまま使えます。
- このリポジトリを、サーバーに取得しておく: `git clone https://github.com/YourID-win/VoiceStandee.git ~/voice-standee`
- ドメイン(`yuaworks.com`)が Cloudflare で管理されていること。

## 1. 最初の準備(1回だけ・sudo)

```bash
cd ~/voice-standee
sudo bash deploy/setup-first-time-with-sudo.sh --check-only   # 何をするかを見るだけ
sudo bash deploy/setup-first-time-with-sudo.sh --apply        # 実行
```

作るもの: 専用ユーザー `voice-standee`(ログイン不可)、データ置き場 `/var/lib/voice-standee`、設定ファイル `/etc/voice-standee/voice-standee.env`、
systemd のサービス `voice-standee.service`。サービスは、まだ起動しません。

## 2. トークンを設定ファイルに入れる

```bash
sudoedit /etc/voice-standee/voice-standee.env
```

`VOICE_STANDEE_TOKEN=` の右に、Bot のトークンを貼ります(引用符なし、空白なし)。**トークンは、この設定ファイル以外には書かないでください。**
トークンを作り直すには、開発者ポータルの Bot ページで Reset Token します(古いトークンは、使えなくなります)。

同じファイルの `VOICE_STANDEE_OWNER=` の右に、**管理者(あなた)の Discord ユーザー ID** を入れます。
(Discord の設定 → 詳細設定 → 開発者モードをオン → 自分のアイコンを右クリック → 「ユーザーIDをコピー」)
これを入れると、不適切な立ち絵の報告が、あなたの DM に届き、`/立ち絵管理` が使えるようになります。空のままだと、報告と管理は、オフです。

## 3. 最初のデプロイ

```bash
cd ~/voice-standee
git fetch origin && git checkout main && git pull --ff-only
bash deploy/stage-voice-standee.sh                                   # ビルド(テスト込み)。ハッシュが表示される
sudo bash deploy/deploy-voice-standee-with-sudo.sh --check-only      # 確認だけ
sudo bash deploy/deploy-voice-standee-with-sudo.sh --confirm <表示されたハッシュ>
```

成功すると、サービスが起動し、Discord へのログインと、Web サーバーの応答を確認して終わります。
2回目以降も同じ手順です。失敗したときは、前の版に自動で戻ります(初回だけは、戻る先がないので、停止したままになります)。

ログ: `journalctl -u voice-standee -f`

## 4. 公開する(Cloudflare Tunnel)

ホスト名: `standee.yuaworks.com` → サーバー内の `http://127.0.0.1:8780`

```bash
# cloudflared を入れる(Cloudflare の apt リポジトリ)
sudo mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared noble main' | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt-get update && sudo apt-get install cloudflared

# Cloudflare にログイン(表示される URL を、自分の PC のブラウザで開き、yuaworks.com を選んで許可する)
cloudflared tunnel login

# トンネルを作る(表示される UUID と、認証ファイルの場所を控える)
cloudflared tunnel create voice-standee

# standee.yuaworks.com をこのトンネルに向ける(Cloudflare の DNS に、自動で記録が足される)
cloudflared tunnel route dns voice-standee standee.yuaworks.com
```

設定ファイルを作り、サービスとして常時動かします(`<UUID>` は、作ったトンネルのもの)。

```bash
sudo mkdir -p /etc/cloudflared
sudo cp ~/.cloudflared/<UUID>.json /etc/cloudflared/
sudo tee /etc/cloudflared/config.yml >/dev/null <<'EOF'
tunnel: <UUID>
credentials-file: /etc/cloudflared/<UUID>.json

ingress:
  - hostname: standee.yuaworks.com
    service: http://127.0.0.1:8780
  - service: http_status:404
EOF
sudo chmod 600 /etc/cloudflared/<UUID>.json

cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate     # 設定の確認
sudo cloudflared --config /etc/cloudflared/config.yml service install
sudo systemctl enable --now cloudflared
systemctl status cloudflared
```

確認: `curl -sI https://standee.yuaworks.com/assets/overlay.css`(`200` が返れば、公開できています)。

- Minecraft 用の `mc.yuaworks.com` の設定には、触れません。
- 認証ファイル(`<UUID>.json`)は、トンネルの鍵です。他の場所に置いたり、共有したりしないでください。
- `cloudflared` を再起動すると、開いている OBS のページは、一度つながりが切れます。自動でつなぎ直します。

## 5. 動作確認

1. Discord で、Bot を入れたサーバーのボイスチャンネルに入る(Bot が自動で入ってくる)。
2. `/立ち絵 登録` で画像を付け、`/立ち絵 obs` で URL を出す。URL は `https://standee.yuaworks.com/o/...` になっています。
3. その URL を、別の PC の OBS のブラウザソースに貼って、表示されることを確かめる。

## 運用メモ

- 更新するとき: `git pull` → `stage` → `deploy`(上の手順3)。立ち絵と設定は、`/var/lib/voice-standee` にあり、更新しても消えません。
- バックアップ: `/var/lib/voice-standee` をコピーしておけば、立ち絵、設定、OBS 用の URL が、すべて元に戻せます。
- メモリ: プログラムは 512 MB まで(サービスの上限は 900 MB)。Minecraft の邪魔にならないようにしてあります。
- Bot を止める: `sudo systemctl stop voice-standee`(ボイスチャンネルから、先に退出します)。
