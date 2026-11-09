# VoiceStandee (working name)

A Discord bot + OBS overlay: members of a voice channel are shown as their own standing pictures, and the picture moves a little when they speak.
General purpose (not tied to the YuaRealm Minecraft server).

## Status: prototype 0 (does it work at all?)
`SpeakingProbe`: the bot joins your voice channel and logs who starts / stops speaking. It does not listen to, decode or store audio.

### Run it (on your PC, with a test bot and a test server)
1. Discord Developer Portal: New Application -> Bot -> Reset Token (copy it; never paste it into a chat or a file in git). No privileged intents are needed.
2. OAuth2 -> URL Generator: scopes `bot` and `applications.commands`; permissions View Channels + Connect. Open the URL and add the bot to your test server.
3. In PowerShell:
   `$env:VOICE_STANDEE_TOKEN = "<token>"` and `$env:VOICE_STANDEE_GUILD = "<test server id>"`, then `.\gradlew run`
4. In Discord: join a voice channel, type `/probe join`, talk / stay quiet / talk with a friend, read the console. `/probe leave` to stop.

### What to look for
- connection: CONNECTED (the encrypted voice connection works)
- `speaking: <name> [VOICE]` when you talk and `STOPPED` when you stop, while the bot is self-deafened (`/probe join deafen:true`, the default) and while it is not
- several people at once; moving to another channel; restarting the bot
