# Realtime protocol (GA)

Reference checked 2026-11-23: [Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations), [GA migration](https://developers.openai.com/api/docs/guides/realtime), [model](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini). Re-check schemas during implementation. This is a proposed contract, not an implemented client.

Connect to wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini with Authorization: Bearer from local environment. Do not send the legacy beta header. Wait for session.created and session.updated after configuration.

```json
{"type":"session.update","session":{"type":"realtime","instructions":"Respond briefly.","output_modalities":["audio"],"audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"turn_detection":null},"output":{"format":{"type":"audio/pcm","rate":24000},"voice":"marin"}}}}
```

Text: use output_modalities ["text"], conversation.item.create with input_text, response.create, response.output_text.delta/done. Treat response.done status as authoritative for completion; not every terminal response is successful.

Push-to-talk: raw signed mono PCM16 LE at 24 kHz, even-byte chunks, no WAV headers. Append Base64 chunks in order, commit on release only for valid nonempty audio, then response.create. Cancel incomplete capture with input_audio_buffer.clear. Use explicit device check or conversion; changing sample rate metadata is not resampling.

Output: decode response.output_audio.delta to a bounded playback queue. Handle response.output_audio_transcript.delta/done. Input transcription is separately enabled and approximate; it need not match what the audio model perceived.

Tools: set session tool declarations; wait for completed function arguments, validate and correlate by call_id, send conversation.item.create function_call_output with the same call_id and request continuation after pending calls are resolved. Unknown or unauthorized names receive a safe failure; deduplicate calls.

VAD: compare server_vad and semantic_vad with manual mode; when auto response is disabled explicitly request response after policy approval. On interruption stop and flush local audio immediately, cancel active generation where needed, send conversation.item.truncate with item_id, content_index and actually played audio_end_ms; reject late deltas. Do not confuse generated duration with heard duration. Test the case where response.done arrives before playback finishes.

Usage: parse response.done usage once per response ID; record rate_limits.updated separately; distinguish missing fields from zero. Never log full raw payloads by default.
