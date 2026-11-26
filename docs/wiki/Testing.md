# Testing and evaluation

Offline first: JUnit tests use a fake transport and synthetic event/PCM fixtures, fake clock and stub devices. CI must not require API credentials or physical microphone and must not call OpenAI. Add tests for JSON fragmentation, ordering, state, bounded queues, malformed/unknown events, tool deduplication/validation, cancellation, redaction and budget arithmetic. Verify FX thread handoff and late-event isolation.

## RVL-003 text verification

With Java 27, run the protocol tests and the headless JavaFX workspace journey with the mock transport.

```sh
env -u OPENAI_API_KEY ./gradlew --offline test --tests '*RealtimeTextConversationTest' --tests '*RealtimeVoiceLabAppTest'
```

The tests cover a two-turn exchange, replacement of streamed text by the terminal response, a single in-flight turn, cancellation, correlated provider errors and late callback isolation. The workspace keeps up to 60 turns in memory and clears them on the next connection or when the window closes.

### RVL-003 manual text smoke

After giving explicit opt-in for a paid test, use a dedicated restricted key in the local process environment. Launch `./gradlew --offline run` and use one connection with the default `gpt-realtime-2.1-mini` model. Budget at most three text requests and 60 seconds of connection time for this smoke. The app also enforces the 512-token response cap and 5-minute session limit.

1. Verify Send and Cancel are disabled while disconnected. Connect and wait for the Connected status.
2. Send `Remember this fictional code word: cobalt. Reply OK.` Then send `What was the code word?` Verify that the reply refers to the first turn, that each reply appears in a single entry, and that Send becomes available after each response.
3. Send `Count from one to one hundred, one number per line.` Select Cancel as soon as Send disables. Verify that the turn terminates, the workspace becomes ready for another message, and any partial reply stays in the same entry. Record if generation wins the race and completes first.
4. Disconnect and verify that the composer and its buttons are disabled. Record the model, OS/JDK and outcome using synthetic data only.

## RVL-004 push-to-talk verification

In a ready session, hold the microphone with the primary mouse button, or focus it and hold Space or Enter. The recording label shows elapsed captured audio and the meter shows the current input level. Release to send an in-memory take of up to 30 seconds. Escape or loss of focus during the hold discards the take. The voice turn appears as “Voice input” and requests an audio reply.

```sh
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline test --tests '*RealtimeAudioInputTest' --tests '*RealtimeVoiceLabAppTest'
```

The synthetic checks exercise the audio format and explicit start, device closure on release/cancel/disconnect, the 30-second cap, empty and short takes, and ordered raw PCM appends followed by commit and response creation. The JavaFX journey exercises mouse release outside the button, keyboard capture, Escape, focus loss, window close and recording feedback. No physical input device or live Realtime session is needed.

## RVL-005 playback and transcript verification

Select **Input transcription** before connecting to enable approximate microphone transcripts for that session. The default is **Off**. The **Stop** button flushes playback and cancels a reply that is still generating. It stays available after the reply finishes generating while audio is still queued.

```sh
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline test --tests '*RealtimeAudioOutputTest' --tests '*RealtimeVoiceLabAppTest'
```

The fake socket and speaker verify playback starts before `response.done`, PCM stays in order across fragmented events, and late events cannot restart a finished or cancelled reply. The headless workspace exercises the speaker meter, Stop after generation, window-close cleanup and transcript updates while the device is active. Input results are matched to committed recordings, including out-of-order results and a redacted failure.

## RVL-006 event inspector verification

- With dependencies cached, run `env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline test --tests '*EventInspectorTest' --tests '*RealtimeAudioInputTest' --tests '*RealtimeVoiceLabAppTest.inspectsFiltersAndBatchesSyntheticEventsInTheWorkspace'`.
- The synthetic workspace test covers both directions, filtering, selection, redacted JSON, a batched burst of unknown events, and cleanup on window close. Session tests cover fragmented messages, ring eviction, and decoded audio byte counts in both directions.
- The inspector retains the last 256 entries in memory until window close. It shows exact client-generated event IDs and app-local aliases for provider event IDs. Outgoing entries indicate attempted writes. Unknown server types use a fixed label. The JSON view contains timestamps, directions, types, IDs when available, and decoded byte counts for audio chunks. Payloads and credentials are excluded, and the inspector has no disk or network export.

## RVL-007 session settings verification

```sh
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline test --tests '*RealtimeSettingsTest' --tests '*RealtimeVoiceLabAppTest.editsSessionSettingsAndShowsOnlyAcknowledgedChangesInTheWorkspace'
```

The headless dialog journey selects both presets, edits the instructions, saves the model, voice and Text mode, and connects over a fake socket. It checks that Audio mode becomes available only after acknowledgment, that the model is fixed while connected, and that a synthetic audio event locks the voice. It also exercises sanitized rejection and the return to editable settings after the session ends. Protocol tests cover acknowledgment and write ordering, mismatched settings, timeouts, allowlists and explicit reconnect. Use synthetic instructions and PCM in any captured evidence.

## RVL-008 VAD and barge-in verification

In Audio mode, select **Server VAD** or **Semantic VAD** under **Turn detection**. Click the microphone or press Space or Enter with the microphone focused to start listening. Click again, press Escape, or leave the window to stop listening and clear partial input. Each listening window lasts up to 30 seconds. Stop listening before sending a typed turn or changing settings. For `gpt-realtime-whisper` input transcription, use **Push to talk**.

```sh
env -u OPENAI_API_KEY -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --offline test --tests '*RealtimeBargeInTest' --tests '*RealtimeAudioInputTest' --tests '*RealtimeAudioOutputTest' --tests '*RealtimeVoiceLabAppTest'
```

Synthetic tests cover both VAD modes, interruption during generation and queued playback after `response.done`, and an interruption before the speaker opens. They also cover speech during stalled speaker cleanup and a complete VAD turn arriving while the client clears partial input. The next response waits for the server to acknowledge `conversation.item.truncate` with the correct item and duration. The duration comes from the Java Sound frame position after stopping the line, rounded down to whole milliseconds. The tests also exercise overlapping WebSocket writes, duplicate speech events, stale deltas, rejected or missing truncation acknowledgments, and switching back to manual PTT. The headless JavaFX journey verifies explicit start and stop on window blur.

The client disables automatic response creation and cancellation in both VAD modes. It sends a targeted `response.cancel` when generation is still active and clears the local transcript for an interrupted assistant audio reply because the stream has no word-level playback timing. This verification uses synthetic PCM and devices. The live service and physical audio timing require an explicitly authorized manual smoke. [OpenAI VAD guide](https://developers.openai.com/api/docs/guides/realtime-vad) · [Realtime client event reference](https://developers.openai.com/api/reference/resources/realtime/client-events#conversation-item-truncate)

## Opt-in manual smoke across MVP features
Use a dedicated restricted API key, headphones and own adult or synthetic speech.
- Connect mini; verify session acknowledgment and text two-turn exchange.
- Push-to-talk a short question; confirm incremental audio and transcript.
- Exercise safe workspace tools and invalid arguments.
- Interrupt active generation and queued playback; verify truncation.
- Exercise timeout and disconnect; ensure mic/speaker/socket resources close.
- Check redacted inspector and reported usage vs provider dashboard where available.

Never publish transcripts, audio, tokens/keys or personal evidence. Report model/version, transport, OS/JDK, session length, event counts, observed errors, first-audio latency and estimated usage; label unmeasured fields. Define first-audio latency as local monotonic interval from response request (or VAD turn end) to first audio delta; playback start is separate. Compare identical short scenarios, repeated trials, p50/p95 only if sample size documented. No vendor-wide benchmark conclusions from small local experiments.
