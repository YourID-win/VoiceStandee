# 更新の手順

最初の設置と、Cloudflare Tunnel の設定は、[deployment.md](deployment.md)。ここは、**2 回目以降の更新**の手順です。

## 流れ
作業 PC で開発 → PR → `main` にマージ → **サーバーで** `main` を取得してビルド（テスト込み）→ `--check-only` → `--confirm <ハッシュ>`。

## 1. 開発（作業 PC）
```powershell
.\gradlew test
```
作業は、ブランチで、PR にして、`main` にマージする。動作の確認は、テスト用の Bot とテスト用のサーバーで（`.\gradlew run`。トークンは、自分の PC の環境変数に）。

## 2. サーバーでビルドする
```bash
cd ~/voice-standee
git fetch origin && git checkout main && git pull --ff-only
bash deploy/stage-voice-standee.sh
```
ビルド（テスト込み）をして、**ツリーのハッシュ**を表示する。本番へは、まだ何も入れない。

## 3. 確認する（何も変えない）
```bash
sudo bash deploy/deploy-voice-standee-with-sudo.sh --check-only
```

## 4. 配備する
```bash
sudo bash deploy/deploy-voice-standee-with-sudo.sh --confirm <表示されたハッシュ>
```
- ステージのコピーを検証 → サービスを止める → 現在のインストールを `/opt/voice-standee.backup-<時刻>` へ → 新しいものを配置 → 起動 → **Discord へのログインと、オーバーレイの Web サーバーの応答**を確認。
- **失敗したら、自動で、旧版に戻す**（初回の設置だけは、戻る先がないので、停止したまま）。
- データ（立ち絵と設定）と、環境ファイルは、触らない。

## 5. 出したあとの確認
```bash
sudo systemctl status voice-standee --no-pager | head -8
sudo journalctl -u voice-standee --since "10 minutes ago" --no-pager | tail -20
```
Discord で `/立ち絵 確認`。OBS のブラウザソースが、表示されること。

## 戻す
自動の巻き戻しが効かなかったとき: `/opt/voice-standee.backup-<最新の時刻>` を、`/opt/voice-standee` に戻して、サービスを起動する。

## 守ること
- トークンは、環境ファイルだけ。トークンを作り直すときは、開発者ポータルの Bot のページで Reset Token（古いものは、使えなくなる）。
- 利用者向けの案内（告知の文面）を変えたら、[user-guide.md](user-guide.md) を直す。
