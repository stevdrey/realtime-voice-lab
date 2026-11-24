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

## Opt-in manual smoke (after limits are implemented)
Use a dedicated restricted API key, headphones and own adult or synthetic speech.
- Connect mini; verify session acknowledgment and text two-turn exchange.
- Push-to-talk a short question; confirm incremental audio and transcript.
- Exercise safe workspace tools and invalid arguments.
- Interrupt active generation and queued playback; verify truncation.
- Exercise timeout and disconnect; ensure mic/speaker/socket resources close.
- Check redacted inspector and reported usage vs provider dashboard where available.

Never publish transcripts, audio, tokens/keys or personal evidence. Report model/version, transport, OS/JDK, session length, event counts, observed errors, first-audio latency and estimated usage; label unmeasured fields. Define first-audio latency as local monotonic interval from response request (or VAD turn end) to first audio delta; playback start is separate. Compare identical short scenarios, repeated trials, p50/p95 only if sample size documented. No vendor-wide benchmark conclusions from small local experiments.
