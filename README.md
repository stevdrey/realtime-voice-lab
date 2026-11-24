# Realtime Voice Lab

A small JavaFX desktop playground for learning OpenAI Realtime API directly from the JVM.

**Status:** the offline desktop shell opens in a disconnected state. Session controls are disabled and metrics are unavailable while the corresponding features are under development. See [RVL-001](../../issues/7).

## Goals
- Inspect GA WebSocket events and learn the session lifecycle.
- Talk to `gpt-realtime-2.1-mini` using text and push-to-talk audio.
- Experiment with prompts, transcripts, VAD, barge-in and safe local function calling.
- Measure latency, usage and estimated costs without storing private conversation data.

## Stack
The single desktop module uses Java 27, JavaFX 27.0.1, the Gradle 9.9.1 wrapper, and JUnit 6.1.3. The build selects an installed Java 27 toolchain. CI uses Eclipse Temurin 27.0.1.1+4. The planned transport and audio stack uses JDK HttpClient/WebSocket, Jackson and Java Sound.

## Documentation
See the [UI design specification](DESING.md) for the approved dark JavaFX workspace, component behavior and visual acceptance criteria.

The [documentation home](docs/wiki/Home.md) is the canonical, version-controlled wiki-ready source.
See [roadmap](docs/wiki/Roadmap.md), [architecture](docs/wiki/Architecture.md), [decisions](docs/wiki/Architecture-Decisions.md), [security](docs/wiki/Security-and-Cost.md) and [testing](docs/wiki/Testing.md).

## Development

Install a released JDK 27 and point `JAVA_HOME` to it. The first build downloads Gradle and the pinned dependencies. On Windows, use `gradlew.bat`.

```sh
./gradlew clean build
./gradlew run
```

`./gradlew test` runs the JUnit tests with JavaFX's headless backend. The tests check the disconnected window, disabled controls, unknown metrics and layout at the minimum size. The build and app launch require no API key. `run` opens a desktop window, while `test` also works without a display.

### Linux audio prerequisites

- For the desktop window, use a graphical session with GTK 3. The package is `gtk3` on Fedora and `libgtk-3-0t64` on Ubuntu 24.04.
- For future Java Sound capture and playback, use a working ALSA setup with `libasound` (`alsa-lib` on Fedora, `libasound2t64` on Ubuntu 24.04). On PipeWire desktops, use your distribution's ALSA integration, such as `pipewire-alsa` on Fedora.
- Check the selected input and output in your desktop sound settings. `arecord -l` and `aplay -l` from `alsa-utils` can list ALSA capture and playback hardware. An audio device is unnecessary for the current shell and tests.

Set `OPENAI_API_KEY` in your local process environment only for an explicitly authorized live test after session limits are in place. Keep the key out of commits and prompts.

[Backlog](../../issues) · [Official Realtime guide](https://developers.openai.com/api/docs/guides/realtime)
