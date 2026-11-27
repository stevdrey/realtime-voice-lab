# Realtime protocol (GA)

RVL-002 implements the session lifecycle and RVL-011 adds its safety limits. RVL-003 through RVL-005 cover typed turns, push-to-talk, and streamed audio replies with transcripts. See [Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations), [Realtime WebSockets](https://developers.openai.com/api/docs/guides/realtime-websocket) and the [default model](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini).

Connect opens `wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini` by default, or uses the selected `gpt-realtime-2.1` model. The bearer credential comes from the local environment. The client waits for `session.created`, writes `session.update` and enters ready state after a successful write and a matching `session.updated`. It checks the effective model, instructions, voice and output modality, plus the existing safety limits. Audio mode requires confirmed PCM output at 24 kHz. A PCM format without an explicit rate uses the sole GA PCM rate of 24 kHz.

```json
{"type":"session.update","event_id":"rvl-settings-1","session":{"type":"realtime","instructions":"Respond briefly.","max_output_tokens":512,"output_modalities":["audio"],"audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"turn_detection":null,"transcription":null},"output":{"format":{"type":"audio/pcm","rate":24000},"voice":"marin"}}}}
```

With the session idle, one update at a time can change instructions, mode or the voice before the first generated audio. The client pauses new turns until the write succeeds and the provider confirms the effective settings for that session. The client event ID correlates provider errors. The acknowledgment has its own ID, so the client matches the session ID and effective values. Rejection, a mismatched acknowledgment or a live update timeout ends the session with a sanitized message and requires an explicit reconnect. Changing the model, input transcription or a locked voice requires a new connection. See the [Realtime API reference](https://developers.openai.com/api/reference/resources/realtime).

The upgrade has a 10-second timeout, and readiness has a 15-second deadline from Connect. Inbound fragmented text is limited to 256 Ki UTF-16 code units per message. Disconnect sends a normal close frame and waits up to three seconds for the peer. Once closing starts, its own deadline governs cleanup. Cancellation during setup and window shutdown abort the active socket or cancel the pending handshake. The app surfaces fixed status messages for failures.

The five-minute deadline starts at Connect and expires even on an idle socket with no usage events. The client counts decoded PCM bytes up to 1,440,000 per audio response, with at most four audio responses tracked at once, and allows ten completed function-call argument events per session, including repeats. Only PCM for the matching voice reply can enter the in-memory playback queue. A local limit stops event processing, flushes local audio, sends `response.cancel` and closes the socket. A response that the provider already ended at the token cap goes directly to close. The client aborts if cancellation or close stalls for three seconds. A new session requires an explicit Connect.

Text: use output_modalities ["text"], conversation.item.create with input_text, response.create, response.output_text.delta/done. Treat response.done status as authoritative for completion; not every terminal response is successful.

Push-to-talk: raw signed mono PCM16 LE at 24 kHz, even-byte chunks, no WAV headers. Append Base64 chunks in order, commit on release only for valid nonempty audio, then response.create. Cancel incomplete capture with input_audio_buffer.clear. Use explicit device check or conversion; changing sample rate metadata is not resampling.

Voice replies request `output_modalities: ["audio"]`. The client decodes each `response.output_audio.delta` once and enqueues complete PCM frames for a dedicated Java Sound worker. It buffers at most 30 seconds of PCM in memory. `response.output_audio_transcript.delta` streams the assistant transcript. Part-level `done` events finalize it, with the terminal response filling in any unfinished parts that it supplies.

After the final completed `response.done` for a voice turn, the worker checks played frames until the last written frame has played. Stop, disconnect and window close can interrupt this wait. Cancellation or three seconds without device progress while PCM is pending triggers a flush. The workspace shows speaker cleanup before the worker calls the device to stop or close. If cleanup takes more than four seconds, the workspace reports audio unavailable. Text turns remain available once the response ends. The worker retains exclusive ownership until it returns, so reconnecting during cleanup cannot start another microphone or speaker worker.

Input transcription is opt-in per connection through `audio.input.transcription.model`. The client checks that the acknowledgment enables or disables transcription as requested, since the provider may normalize the model name, and matches `conversation.item.input_audio_transcription.completed` and `.failed` to the committed `item_id`. Results can arrive after the reply. The workspace labels the transcript as approximate because the transcription model processes audio separately. See the [Realtime API reference](https://developers.openai.com/api/reference/resources/realtime).

RVL-009 declares `calculate_workspace_counter` in `session.tools` and checks the effective configuration. The function takes integers `current` (0–100) and `delta` (−10 to 10) and returns the resulting value if it stays between 0 and 100. It is a pure local calculation.

RVL-010 adds the **Toy workspace** tool preset. The operator selects it before connecting, and the client verifies the effective preset in `session.updated`. Each session advertises the functions for its selected preset.

| Function | Arguments |
|---|---|
| `changeColor` | `color` is `indigo`, `blue`, `green`, or `amber`. |
| `setTheme` | `theme` is `slate` or `midnight` and applies to the toy panel. |
| `incrementCounter` | Integer `delta` from −10 to 10. The result must stay between 0 and 100. |
| `resetWorkspace` | An empty object. Restores indigo, slate, and 0. |

Each successful workspace call returns `ok: true` and a `color`, `theme`, and `counter` snapshot after that call. An invalid call returns `ok: false` and a fixed `error` code. A batch applies valid calls in response order. The desktop app dispatches each batch to the JavaFX Application Thread. The client checks the session, turn, and deadline again before applying the batch. Toy state stays in memory while the application is open, including across reconnects.

The app reads the complete argument strings from the `function_call` items in a successful, correlated `response.done`. Argument deltas and early completion signals never trigger execution. Call IDs are deduplicated for the connection. Each accepted call receives one `conversation.item.create` with a `function_call_output` item that echoes its `call_id`. Errors contain a fixed code and omit the supplied name and arguments. Invalid IDs end the session because a result cannot be correlated safely.

After each output write and matching `conversation.item.added` acknowledgment, the app advances to the next output. The output exchange has a 15-second deadline. Once all outputs are accepted, the app requests one follow-up in the original text or audio modality with `tool_choice: "none"`. An audio follow-up waits until the app's PCM queue empties into the speaker device, which can finish playing its own buffer while the follow-up starts. This keeps the app queue within its 30-second capacity. On interruption, the provider keeps fully played items from the first response and truncates unheard audio where needed.

Cancellation suppresses unsent tool outputs and any pending follow-up. Toy workspace changes that have already been applied remain visible. If an output has already been sent, the client waits for its write and matching provider acknowledgment within the original output deadline. New responses wait for that acknowledgment and any required audio truncation. A failed write, correlated rejection or missing acknowledgment at the deadline aborts the session. The 10-call session cap from RVL-011 includes argument completion signals and terminal-only calls. See the [Realtime conversations guide](https://developers.openai.com/api/docs/guides/realtime-conversations).

VAD: compare server_vad and semantic_vad with manual mode; when auto response is disabled explicitly request response after policy approval. On interruption stop and flush local audio immediately, cancel active generation where needed, send conversation.item.truncate with item_id, content_index and actually played audio_end_ms; reject late deltas. Do not confuse generated duration with heard duration. Test the case where response.done arrives before playback finishes.

RVL-012 counts usage at `response.done` for every terminal status. The session retains response IDs to ignore duplicates across the connection, including responses outside conversation history. It stops at the protocol limit of 4,096 tracked responses. The client reconciles text and audio token counts against their totals, prices cached input at its own rate, and counts any reported reasoning within output. Missing, inconsistent or unsupported token breakdowns make the session estimate unavailable.

Pending usage begins when the client sends `response.create` or sees a new `response.created`, and settles at the corresponding terminal event or explicit rejection of creation. Closing with responses in flight leaves their usage unreported. The latest valid quotas from `rate_limits.updated` are kept separately because the provider can reserve quota during generation. Input transcription usage is billed separately and excluded. The inspector retains its redacted event summaries.

The voice latency metric measures from sending `response.create` to receiving the first playable PCM chunk for the matching response. This also applies after a VAD commit and for tool follow-up responses. Session elapsed time is monotonic from Connect and freezes at the start of close.
