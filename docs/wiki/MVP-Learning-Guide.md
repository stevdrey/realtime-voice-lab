# MVP learning and verification guide

[RVL-014](https://github.com/stevdrey/realtime-voice-lab/issues/20) covers the desktop lab's text, audio, interruption, and local tool journeys. Start offline and use the optional live checklist for observations that need the provider or real devices.

## Build and exercise the lab offline

Use the Java 27 toolchain pinned in [the build workflow](../../.github/workflows/build.yml). On a fresh checkout, let the wrapper and build dependencies download once by running the build without `--offline`. The test task uses fake transport and audio devices in both cases.

```sh
java -version
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline --no-daemon clean build
```

To open the desktop and explore settings and disabled controls without credentials, run `env -u OPENAI_API_KEY ./gradlew --offline run` in a graphical session. To exercise connected controls without the provider, run the headless JavaFX and protocol journeys.

```sh
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline --no-daemon test \
  --tests '*RealtimeVoiceLabAppTest' --tests '*RealtimeAudioOutputTest' \
  --tests '*RealtimeBargeInTest' --tests '*RealtimeToolCallingTest' \
  --tests '*RealtimeSafetyTest' --tests '*RealtimeUsageTest' \
  --tests '*EventInspectorTest' --tests '*RestrictedInterviewerTest'
```

CI builds the app and runs the offline suite on a headless runner. See [Testing](Testing.md) for feature-specific fixtures and one-test commands.

## Translate the course's Beta examples to GA

| If the older example uses | Use in this lab |
| --- | --- |
| A preview model and the `OpenAI-Beta: realtime=v1` header | Open the GA Realtime WebSocket with the selected model and bearer authentication. The default model is `gpt-realtime-2.1-mini`. Omit the Beta header. |
| Flat session fields such as `modalities`, `voice`, `input_audio_format`, or `turn_detection` | Set `session.type` to `realtime` and choose `output_modalities`. Configure input format, transcription, and turn detection under `audio.input`. Configure the voice and output format under `audio.output`. The lab uses signed mono PCM16 little-endian at 24 kHz for audio. |
| `response.text.delta`, `response.audio.delta`, or `response.audio_transcript.delta` | Consume `response.output_text.delta`, `response.output_audio.delta`, or `response.output_audio_transcript.delta`, respectively. Check the terminal `response.done` status for the response outcome. Audio bytes arrive in the audio deltas. |

Follow the event order in the inspector. Connect waits for `session.created` and a matching `session.updated`. Typed messages wait for the item acknowledgment, and voice waits for `input_audio_buffer.committed`, before the client requests a response. Push to talk commits on release. In VAD, the provider commits detected speech while the client explicitly requests the response. Study why a tool follow-up waits for the function output acknowledgment and why interruption truncates the assistant item at the played frame position. [OpenAI Beta-to-GA migration](https://developers.openai.com/api/docs/guides/realtime#beta-to-ga-migration) · [Lab protocol walkthrough](Realtime-Protocol.md)

## Optional paid manual smoke

Run this checklist only after explicit approval of the live test and its budget. Use a dedicated restricted key in the local process environment, headphones, fictional text, and your own adult voice or synthetic speech. Review current pricing and configure the [estimated spend cutoff](Security-and-Cost.md#pricing-and-optional-cutoff) within the approved budget before launching `./gradlew --offline run`. The cutoff uses completed response usage and billing can exceed it. The [app's baseline limits](Security-and-Cost.md) include one active connection, five minutes per session, 512 output tokens and up to 30 seconds of streamed PCM per response, and ten function-call completions per session, including repeats.

Use the default mini model, one connection per selected row, and at most 60 seconds from Connect per row. Stay within the response-request cap for the row, counting tool follow-ups and interrupted requests. End the connection as soon as you finish the row or reach the approved budget or time limit. Keep **Input transcription** off unless specifically testing it within the approved budget, since its usage is billed separately. If a race or the model's behavior makes a result unobservable, record it as inconclusive.

| Check | Steps | Expected observation |
| --- | --- | --- |
| Text and cancellation (3 requests) | In **Settings**, select **Free Conversation** and **Text** mode, then **Save**. Check that Send is disabled before connecting. After **Connected**, send `Remember the fictional code word cobalt. Reply OK.` and then `What was the code word?` Use the third request to ask for a count to one hundred, then select **Cancel** during generation. | A reply streams into one entry per turn. The second reply uses the first turn's context. While generation is active, Cancel ends the third turn and the composer becomes ready again. |
| Push to talk and playback (2 requests) | In **Audio** mode with **Push to talk**, hold the microphone and say “Name a fictional moon in one sentence.” Release, listen, then repeat with “What did you name it?” If approved, select `gpt-transcribe` in **Input transcription** before connecting to exercise input transcription. | The microphone meter shows capture. Release commits the take, output audio starts in chunks, and the assistant transcript appears. The inspector shows append, commit, response, and audio events. With transcription enabled, a separate approximate input transcript may arrive after the reply. |
| Tool validation (4 requests) | Start from a freshly launched app with **Audio**, **Push to talk**, and **Toy workspace** selected. Use the microphone to request green, midnight, and a counter increase of 3. Once it settles, type `Try incrementCounter with delta 11 and report the result.` | The panel displays green, midnight, and 3 after the first turn. Look for the function output conversation item and audio follow-up request. If the model actually submits the out-of-range call, the counter stays at 3. A model refusal alone leaves the validator unobserved. |
| Interrupt active generation (2 requests) | In **Audio** mode select **Server VAD**. Click the mic to listen, ask for a slow count to fifty, and speak “Stop. Say cobalt.” during playback while generation is active. | The current playback stops, the inspector shows `response.cancel` and truncation if there is unheard audio, and the next response waits for required truncation acknowledgment. |
| Stop queued playback and close (1 request) | In **Push to talk**, request a short spoken list that leaves audio playing after `response.done`. Click **Stop** while audio is still queued. After the mic becomes ready, start a second capture and close the window while holding the mic. Relaunch without the key to check the startup state. | Stop flushes queued playback. Truncation reconciles any unheard audio without another cancellation for the completed response. The window closes without sending the partial capture. The relaunched app is disconnected with an empty conversation and inspector. |

After the first four scenarios, check that **Disconnect** ends the session with the mic and composer disabled. The window-close row exercises the alternate shutdown path. Use synthetic timeout and late-event fixtures in [Testing](Testing.md) to verify resources close and stale callbacks stay isolated. For the restricted preset, follow the [interview and adversarial scenarios](Testing.md#rvl-013-restricted-interviewer-verification).

## Read metrics and record sanitized evidence

- **Latency** is a local monotonic interval from dispatch of `response.create` to receipt of the first playable PCM chunk for that response. The same start point applies after a VAD commit and for a tool follow-up. The value excludes recording, time waiting for the commit and device playback delay. The footer retains the last measured voice value in the current session. Measure end-of-speech to audible playback separately if you need the perceived delay.
- **Response completion** is the arrival of `response.done`. The speaker may still be playing buffered audio. Compare request-to-terminal times separately from request-to-first-audio times.
- **Session** counts elapsed monotonic time from Connect to the start of closing. **Est. cost** uses token usage reported at `response.done` and dated local pricing. Hover for usage and the latest quota report. Pending or missing usage can leave the estimate incomplete, and input transcription is billed separately.

For each smoke row, record the revision, date, OS/JDK, model, connection type, relevant settings, duration, pass/fail/inconclusive status, event-type counts and fixed app error labels. Record latency only when observed. For comparisons, repeat the same short script and settings, report the number of trials, and label the metric being compared. Include real charges only when a live run occurred and the provider's billing data can be attributed to that run. Keep local estimates separate from billed charges.

Use the inspector's **Redacted JSON** to check event types and PCM byte counts. Its recent-event window is bounded to 256 entries, so count the relevant events as they occur or label the count as a sample. Keep the evidence to types, counts, timing, and outcomes. Exclude credentials, recordings, raw PCM/Base64, original event payloads, and transcripts.

## Troubleshoot

| Symptom | Check |
| --- | --- |
| The app fails to start or Gradle cannot resolve dependencies | Verify `java -version`, the Java 27 toolchain, and the first dependency download. For desktop startup, check the graphical session and [native prerequisites](../../README.md#linux-desktop-and-audio-prerequisites). Use headless tests to isolate display issues. |
| Connect fails or stays pending | For an approved live run, verify that the launching process has a restricted key with model access, that it can reach the official WebSocket endpoint, and that the inspector shows `session.created` followed by `session.updated`. Review the fixed app status for authentication, settings mismatch, timeout, or spend configuration. |
| The mic is disabled or no audio is heard | Check **Connected**, **Audio** mode and the chosen turn detection. Make sure the system's default devices support mono PCM16 at 24 kHz. For **Push to talk**, hold long enough to capture speech, then release. Look for the commit, output deltas, and the speaker status. With VAD, click to listen and keep the window focused. Use **Push to talk** with `gpt-realtime-whisper`. |
| Stop appears idle or a turn stays blocked | Generation may have finished before Cancel. When audio remains queued, look for **Stop**. After interruption, allow speaker cleanup and any truncation acknowledgment to settle. If the session reports a timeout or mismatch, disconnect if still connected. Retry with an explicit Connect only within the approved smoke budget. |
| The panel stays unchanged or the estimate is incomplete | Verify the tool preset before connecting and check whether the model sent a function call. State changes require a valid call. Inspect the cost footer for pending usage, the pricing date, or unavailable usage. The provider dashboard may reflect charges beyond the lab's response estimate. |
