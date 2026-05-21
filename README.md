# Livescribe

A Java CLI tool that monitors streaming channels and automatically starts
recording when a streamer goes live.

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Maven](https://img.shields.io/badge/Build-Maven-blue)
![Status](https://img.shields.io/badge/Status-Alpha-yellow)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Features

- Automatic live detection via the Twitch Helix API
- Simultaneous recording of multiple channels
- Streamlink integration for reliable stream resolution
- Automatic MP4 conversion after recording via FFmpeg
- Extensible provider architecture (Twitch now, Kick and YouTube planned)
- Configuration via `.properties` file and environment variables

---

## Requirements

- Java 21+
- Maven 3.8+
- [Streamlink](https://streamlink.github.io/) — `pip install streamlink`
- [FFmpeg](https://ffmpeg.org/) — for MP4 conversion

---

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/DennySesay/livescribe.git
cd livescribe
```

### 2. Set environment variables

```bash
# Linux / Mac
export LIVESCRIBE_TWITCH_ID=your_client_id
export LIVESCRIBE_TWITCH_SECRET=your_client_secret

# Windows (PowerShell)
$env:LIVESCRIBE_TWITCH_ID="your_client_id"
$env:LIVESCRIBE_TWITCH_SECRET="your_client_secret"
```

Get your Twitch credentials from the
[Twitch Developer Portal](https://dev.twitch.tv/console).

### 3. Configure your streamers

Create a `config.properties` in the project root
(template: `config.example.properties`):

```properties
streamers=twitch:channelname

scribe.output.path=./scribe
check.interval.seconds=30
```

Multiple channels:
```properties
streamers=twitch:ludwig, twitch:pokimane, kick:xqc

scribe.output.path.twitch=./scribe/twitch
scribe.output.path.kick=./scribe/kick
```

### 4. Build and run

```bash
mvn clean package
java -jar target/livescribe-1.0.jar
```

---

## Output

Recordings are automatically named and saved:
~/livescribe/
ludwig-2026-04-09-203000.ts    ← raw stream file
ludwig-2026-04-09-203000.mp4   ← converted output

---

## Roadmap

- [ ] System tray GUI (JavaFX)
- [ ] Launch on system startup (Windows / Mac / Linux)
- [ ] Kick and YouTube provider support
- [ ] SQLite recording history
- [ ] Docker image

---

## Contributing

Contributions are welcome. Please open an issue to discuss changes before
submitting a pull request.

## Adding a new provider:
1. Implement `StreamingClient`
2. Add a case to the provider switch in `StreamerConfig`
   
---

## Security

Never commit credentials to the repository. Always provide secrets via
environment variables. `config.properties` is listed in `.gitignore`.

---

## Disclaimer

This tool is intended for personal use only. Usage is subject to the
[Twitch Terms of Service](https://www.twitch.tv/p/en/legal/terms-of-service/).

---

## License

MIT License — see [LICENSE](LICENSE)
