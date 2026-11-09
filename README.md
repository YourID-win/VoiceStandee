# VoiceStandee (working name)

A Discord bot + OBS overlay: the people in a voice channel are shown as their own standing pictures, and a picture moves a little while its owner speaks.
General purpose (not tied to the YuaRealm Minecraft server).

## How it is used
1. **Everyone** registers a standee picture: `/立ち絵 登録` (English: `/standee set`) with an image (PNG / JPG / GIF, up to 3 MB).
   Used in a server it registers the picture **for that server**; in a DM with the bot, or with the option `共通` (`shared`), it registers the picture shared by all servers.
   What is shown in a server: that server's own picture, else the shared one, else the Discord avatar. Up to 20 server pictures per person.
2. **The streamer** runs `/立ち絵 obs` (`/standee obs`) and pastes the address it shows into an OBS **Browser** source. The address is secret and belongs to that streamer.
3. When the streamer enters a voice channel, the bot joins by itself (`/立ち絵 自動参加` switches that off). The overlay shows everyone in that channel: their standee, or their Discord avatar when they have none. Whoever is speaking is bright and hops lightly.

Other commands: `/立ち絵 確認` (`show`) the registered picture, `/立ち絵 削除` (`remove`), and `/立ち絵 obs 再発行:True` (`reset`) a new address when one has leaked.

The look is adjusted in a **settings page** at the overlay address plus `/settings` (`/立ち絵 obs` shows both). It has a live preview and sliders: hop height, **length of one hop** (shorter = finer and quicker), stretch/squash, lean, how dim quiet people are, picture height, spacing, alignment, names, and presets (小刻み / ふつう / ゆったり / 控えめ). Changes save by themselves and reach OBS at once; the address in OBS stays the same. The settings are kept on the server per streamer (`OverlaySettings`), every value clamped to a sane range.

## What it does with voice
Discord tells a bot who is speaking only by sending that person's voice packets. The bot therefore has to be in the channel **not deafened**, and it looks at **who sent a packet and when**, nothing more. The audio is never read, decoded, kept or passed on (see `SpeakingTracker`). Opus (the audio codec library) is deliberately left out of the program.

## Run it (on your PC, with a test bot and a test server)
1. Discord Developer Portal: New Application -> Bot -> Reset Token (copy it; never paste it into a chat or a file in git). No privileged intents are needed.
2. Invite the bot with this address (replace APPLICATION_ID with the Application ID from General Information):
   `https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot%20applications.commands&permissions=1049600`
   (permissions = View Channels + Connect)
3. In PowerShell: `$env:VOICE_STANDEE_TOKEN = "<token>"`, optionally `$env:VOICE_STANDEE_GUILD = "<test server id>"` (commands then appear at once), and `.\gradlew run`.
4. Stop it with Ctrl+C: it leaves the voice channel by itself first.

Settings (environment variables): `VOICE_STANDEE_TOKEN` (required), `VOICE_STANDEE_GUILD`, `VOICE_STANDEE_BIND` (default 127.0.0.1), `VOICE_STANDEE_PORT` (default 8780),
`VOICE_STANDEE_BASE_URL` (the address people put into OBS; default `http://127.0.0.1:PORT`, so only this PC can open it), `VOICE_STANDEE_DATA` (default `./data`: the pictures and the user list).

## Running it on a server
`docs/deployment.md` (in Japanese) has the whole procedure for an Ubuntu server: a service user, a systemd unit with limits, a staged and hash-pinned deployment that rolls back when the new version does not start, and publishing the overlay through Cloudflare Tunnel. Scripts are in `deploy/`; `config/voice-standee.env.example` lists the settings.

## Try the look without Discord
`.\gradlew demo` starts only the overlay with three made-up people who take turns speaking, and prints the address to open in a browser or an OBS browser source.

## Tests
`.\gradlew test` (picture checks, storage, and the web server including the live feed).

## Notes
- Discord allows **one voice connection per server** for a bot. If two streamers are in different channels of the same server, the first one is served.
- Pictures are always decoded and drawn again (metadata and trailing data are dropped), size-checked before decoding, and an animated GIF becomes its first frame.
- The overlay page is only reachable with the secret in the address; a wrong secret looks like a missing page. The feed is limited to 8 connections per address.
- Not done yet: reporting/removing someone else's picture (moderation), several languages, publishing the web server (Cloudflare Tunnel), running as a service on the Linux server.

## Prototype
`.\gradlew probe` runs `SpeakingProbe`, the first test that only logs who speaks (`/probe join`, `/probe leave`).
