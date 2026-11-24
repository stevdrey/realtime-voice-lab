# Realtime protocol (GA)

RVL-002 implements the session lifecycle and RVL-011 adds its safety limits. The text, audio devices, tool execution and usage display are later work. See [Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations), [Realtime WebSockets](https://developers.openai.com/api/docs/guides/realtime-websocket) and the [default model](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini).

Connect opens `wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini` with bearer authorization from the local environment. The client waits for `session.created`, writes `session.update` and enters ready state after a successful write and a matching `session.updated` that confirms a finite output cap of at most 512 tokens.

```json
{"type":"session.update","session":{"type":"realtime","instructions":"Respond briefly.","max_output_tokens":512,"output_modalities":["audio"],"audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"turn_detection":null},"output":{"format":{"type":"audio/pcm","rate":24000},"voice":"marin"}}}}
```

The upgrade has a 10-second timeout, and readiness has a 15-second deadline from Connect. Inbound fragmented text is limited to 256 Ki UTF-16 code units per message. Disconnect sends a normal close frame and waits up to three seconds for the peer. Cancellation during setup and window shutdown abort the active socket or cancel the pending handshake. The app surfaces fixed status messages for failures.

The five-minute deadline starts at Connect and expires even on an idle socket with no usage events. The client counts decoded PCM bytes up to 1,440,000 per audio response and allows ten completed function-call argument events per session, including repeats. PCM is counted without retaining decoded audio. A local limit stops event processing, sends `response.cancel` and closes the socket. A response that the provider already ended at the token cap goes directly to close. The client aborts if cancellation or close stalls for three seconds. A new session requires an explicit Connect.

Text: use output_modalities ["text"], conversation.item.create with input_text, response.create, response.output_text.delta/done. Treat response.done status as authoritative for completion; not every terminal response is successful.

Push-to-talk: raw signed mono PCM16 LE at 24 kHz, even-byte chunks, no WAV headers. Append Base64 chunks in order, commit on release only for valid nonempty audio, then response.create. Cancel incomplete capture with input_audio_buffer.clear. Use explicit device check or conversion; changing sample rate metadata is not resampling.

Output: decode response.output_audio.delta to a bounded playback queue. Handle response.output_audio_transcript.delta/done. Input transcription is separately enabled and approximate; it need not match what the audio model perceived.

Tools: set session tool declarations; wait for completed function arguments, validate and correlate by call_id, send conversation.item.create function_call_output with the same call_id and request continuation after pending calls are resolved. Unknown or unauthorized names receive a safe failure; deduplicate calls.

VAD: compare server_vad and semantic_vad with manual mode; when auto response is disabled explicitly request response after policy approval. On interruption stop and flush local audio immediately, cancel active generation where needed, send conversation.item.truncate with item_id, content_index and actually played audio_end_ms; reject late deltas. Do not confuse generated duration with heard duration. Test the case where response.done arrives before playback finishes.

Usage: parse response.done usage once per response ID; record rate_limits.updated separately; distinguish missing fields from zero. Never log full raw payloads by default.
