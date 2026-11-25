# Realtime Voice Lab

A small JavaFX desktop playground for learning OpenAI Realtime API directly from the JVM.

**Status:** Connect and Disconnect manage a GA Realtime session. Typed messages receive streaming text replies. Hold the microphone to record a voice turn and release it to hear a streamed reply with an assistant transcript. To display approximate microphone transcripts, select an input transcription model before connecting. Transcription of microphone input is off by default. See [RVL-005](../../issues/11).

The Event Inspector shows the latest 256 Realtime events in memory. Filter by type, direction or event ID (shown in the row tooltip and JSON), then select a row and expand **Redacted JSON**. Audio chunks show their decoded byte counts. See [RVL-006](../../issues/12).

## Goals
- Inspect GA WebSocket events and learn the session lifecycle.
- Talk to `gpt-realtime-2.1-mini` using text and push-to-talk audio.
- Experiment with prompts, transcripts, VAD, barge-in and safe local function calling.
- Measure latency, usage and estimated costs without storing private conversation data.

## Stack
The single desktop module uses Java 27, JavaFX, the Gradle wrapper and JUnit. The build selects an installed Java 27 toolchain, and CI uses Eclipse Temurin. Exact versions are pinned in [build.gradle](build.gradle), [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) and the [build workflow](.github/workflows/build.yml). The Realtime transport uses JDK HttpClient/WebSocket and Jackson. Microphone capture and speaker playback use Java Sound.

## Documentation
See the [UI design specification](DESING.md) for the approved dark JavaFX workspace, component behavior and visual acceptance criteria.

The [documentation home](docs/wiki/Home.md) is the canonical, version-controlled wiki-ready source.
See [roadmap](docs/wiki/Roadmap.md), [architecture](docs/wiki/Architecture.md), [decisions](docs/wiki/Architecture-Decisions.md), [security](docs/wiki/Security-and-Cost.md) and [testing](docs/wiki/Testing.md).

## Development

See the [project agent workflow](.agents/README.md) and the [40-file pull request guard](docs/verification/pr-size-guard.md) for development and review guidance.

Install a released JDK 27 and point `JAVA_HOME` to it. The first build downloads Gradle and the pinned dependencies. On Windows, use `gradlew.bat`.

```sh
./gradlew clean build
./gradlew run
```

`./gradlew test` runs the JUnit tests with JavaFX's headless backend and a fake Realtime transport. The build, app launch and tests require no API key. `run` opens a desktop window, while `test` also works without a display.

Edit [workspace.fxml](src/main/resources/io/github/stevdrey/realtimevoicelab/ui/workspace.fxml) for layout and [styles.css](src/main/resources/styles.css) for styling.

### Linux desktop and audio prerequisites

- For the desktop window, use a graphical session with GTK 3. The package is `gtk3` on Fedora and `libgtk-3-0t64` on Ubuntu 24.04.
- For Java Sound, use a working ALSA setup with `libasound` (`alsa-lib` on Fedora, `libasound2t64` on Ubuntu 24.04). On PipeWire desktops, use your distribution's ALSA integration, such as `pipewire-alsa` on Fedora.
- Select your default input and output in your desktop sound settings. Each device must expose signed mono PCM16 at 24 kHz in little-endian byte order. `arecord -l` from `alsa-utils` can list ALSA capture hardware. The app opens without audio hardware, and tests use synthetic PCM.

Set `OPENAI_API_KEY` in your local process environment only for an explicitly authorized live test after session limits are in place. Keep the key out of commits and prompts. For those tests, use headphones, the lowest-cost model, and short sessions.

See the testing guide for [push-to-talk controls](docs/wiki/Testing.md#rvl-004-push-to-talk-verification), [offline playback and transcript verification](docs/wiki/Testing.md#rvl-005-playback-and-transcript-verification) and the [manual text smoke](docs/wiki/Testing.md#rvl-003-manual-text-smoke).

[Backlog](../../issues) · [Official Realtime guide](https://developers.openai.com/api/docs/guides/realtime)
