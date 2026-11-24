# Realtime Voice Lab

A small JavaFX desktop playground for learning OpenAI Realtime API directly from the JVM.

**Status:** planning only; no runnable implementation yet. Start with [RVL-001](../../issues/7).

## Goals
- Inspect GA WebSocket events and learn the session lifecycle.
- Talk to `gpt-realtime-2.1-mini` using text and push-to-talk audio.
- Experiment with prompts, transcripts, VAD, barge-in and safe local function calling.
- Measure latency, usage and estimated costs without storing private conversation data.

## Stack
Java 27, JavaFX, Gradle wrapper, JDK HttpClient/WebSocket, Java Sound, Jackson and JUnit. Pin compatible released versions during bootstrap. Single desktop module; no web backend, database, authentication or agent framework.

## Documentation
See the [UI design specification](DESING.md) for the approved dark JavaFX workspace, component behavior and visual acceptance criteria.

The [documentation home](docs/wiki/Home.md) is the canonical, version-controlled wiki-ready source.
See [roadmap](docs/wiki/Roadmap.md), [architecture](docs/wiki/Architecture.md), [decisions](docs/wiki/Architecture-Decisions.md), [security](docs/wiki/Security-and-Cost.md) and [testing](docs/wiki/Testing.md).

## Development
See the [project agent workflow](.agents/README.md) and the [40-file pull request guard](docs/verification/pr-size-guard.md) for development and review guidance.

Once RVL-001 lands, use the Gradle wrapper to build, test and launch (planned tasks: `./gradlew test`, `./gradlew run`). Do not assume these commands exist yet.
Set OPENAI_API_KEY in your local process environment only when performing an explicit live test. Never commit it or enter it in a prompt. No API calls are required for offline tests. Use headphones; start with the lowest-cost model and short sessions.

[Backlog](../../issues) · [Official Realtime guide](https://developers.openai.com/api/docs/guides/realtime)
