# Live Stream Auto-Recorder (Early Stage)

This project is a small Java-based utility that monitors creators on supported streaming platforms and automatically starts a local recording when they go live. It’s currently in active development; configuration, packaging, and documentation are still being finalized.

## What it does (in short)
- Periodically checks if a specified streamer is live.
- When a stream starts, it triggers a single recording job and avoids duplicate downloads.
- Shuts down gracefully and logs basic progress.

## Status
- Early alpha.
- Configuration format and defaults are not finalized.
- Expect breaking changes.

## Prerequisites
- Java 24 (JDK).
- Platform API credentials (e.g., for Twitch).
- An external recording/downloading tool available on your system (for example, a CLI recorder such as streamlink).

## Quick start (high level)
1. Clone the repository.
2. Provide your platform credentials and basic settings (streamer name, polling interval, output location) via your preferred local configuration method. Exact keys/format will be documented once finalized.
3. Build and run the application using your preferred Java build tool.
4. Ensure your external recorder is installed and on your PATH.

Note: Concrete config examples and packaging commands will be added once the configuration schema is settled.

## How it works (high level)
- A scheduler periodically checks live status on the chosen platform.
- When a stream is detected, the app hands off recording to a local tool and waits for completion.
- Only one recording runs at a time to prevent conflicts.

## Roadmap
- Finalize configuration schema and documented defaults.
- Support multiple platforms and multiple streamers.
- Improved error handling, retries, and observability (structured logs/metrics).
- Cross-platform packaging and optional Docker image.
- Pluggable recording backends and post-processing steps.

## Contributing
Early contributions are welcome. Please open an issue to discuss ideas or report problems.

## Security
Do not commit secrets. Keep credentials in a secure, local-only configuration.

## License
To be announced.
