# Testing and evaluation

Offline first: JUnit tests use a fake transport and synthetic event/PCM fixtures, fake clock and stub devices. CI must not require API credentials or physical microphone and must not call OpenAI. Add tests for JSON fragmentation, ordering, state, bounded queues, malformed/unknown events, tool deduplication/validation, cancellation, redaction and budget arithmetic. Verify FX thread handoff and late-event isolation.

## Opt-in manual smoke (after limits are implemented)
Use a dedicated restricted API key, headphones and own adult or synthetic speech.
- Connect mini; verify session acknowledgment and text two-turn exchange.
- Push-to-talk a short question; confirm incremental audio and transcript.
- Exercise safe workspace tools and invalid arguments.
- Interrupt active generation and queued playback; verify truncation.
- Exercise timeout and disconnect; ensure mic/speaker/socket resources close.
- Check redacted inspector and reported usage vs provider dashboard where available.

Never publish transcripts, audio, tokens/keys or personal evidence. Report model/version, transport, OS/JDK, session length, event counts, observed errors, first-audio latency and estimated usage; label unmeasured fields. Define first-audio latency as local monotonic interval from response request (or VAD turn end) to first audio delta; playback start is separate. Compare identical short scenarios, repeated trials, p50/p95 only if sample size documented. No vendor-wide benchmark conclusions from small local experiments.
