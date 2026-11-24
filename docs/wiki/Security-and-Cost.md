# Security, privacy and cost

Threat model: local developer with personal API credentials; accidental long sessions, untrusted model tool requests, prompt injection, audio feedback loops, secret leakage and resource exhaustion. Not a multi-tenant security boundary.

- API key only via environment, not prompts, UI logs, fixtures or commits. HTTPS/WSS official endpoint only; no arbitrary destination or shell execution.
- Explicit Connect and mic permission/action; visible capture indicator; release resources on stop, close or error. Headphones recommended.
- Baseline limits before any paid smoke include one active connection, a 5-minute monotonic deadline from Connect, 30 seconds of streamed PCM per response, 10 function-call completion events per session, and 512 output tokens per response. The setup queue, inbound message buffer and pending JavaFX status updates are bounded. On a limit the client stops processing events and closes, with a three-second abort deadline and explicit reconnection.
- Microphone input is capped at 30 seconds and held in a bounded in-memory queue until release. Only released audio is sent. RVL-009 must pass tool events through the session limit before dispatch, deduplicate call IDs and validate tool arguments and completion independently.
- Tools restricted to toy state. Validate schema, names, ranges, timeouts and call IDs. Never grant tools based on model instructions.
- Restricted Interviewer is a prompt and policy experiment, not guaranteed injection defense. No secrets or unrelated tools in context. Offline enforcement tests plus benign/adversarial manual test cases.
- No audio/transcript persistence or raw Base64 logging. Redact credentials and personal text; use synthetic fixtures or consenting adult voice. API provider retention is separate from local retention.
- Cost display is an estimate. Use dated configurable rates and cached/noncached modality usage; reasoning already included in output must not be counted twice. Account for missing fields, auxiliary transcription, pending requests and errors. Never treat displayed estimate as billing truth or guaranteed dollar cap.

## Reference rates (USD per 1M tokens, 2026-11-23)
gpt-realtime-2.1-mini: text in 0.60, cached text 0.06, text out 2.40; audio in 10, cached audio 0.30, audio out 20. Verify against [model pricing](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini) before use. No hard-coded entitlement or assumption about credit eligibility.
