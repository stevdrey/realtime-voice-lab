# Security, privacy and cost

Threat model: local developer with personal API credentials; accidental long sessions, untrusted model tool requests, prompt injection, audio feedback loops, secret leakage and resource exhaustion. Not a multi-tenant security boundary.

- API key only via environment, not prompts, UI logs, fixtures or commits. HTTPS/WSS official endpoint only; no arbitrary destination or shell execution.
- Explicit Connect and mic permission/action; visible capture indicator; release resources on stop, close or error. Headphones recommended.
- Baseline limits before any paid smoke include one active connection, a 5-minute monotonic deadline from Connect, 30 seconds of streamed PCM per response, 10 function-call completion events per session, and 512 output tokens per response. The setup queue, inbound message buffer and pending JavaFX status updates are bounded. On a limit the client stops processing events and closes, with a three-second abort deadline and explicit reconnection.
- Push to talk keeps up to 30 seconds of microphone audio in memory and sends it when the operator releases the control. Server VAD and Semantic VAD stream audio to OpenAI while the microphone is listening, for up to 30 seconds per listening window. Stopping VAD discards unsent audio and clears the provider's uncommitted input buffer. Complete turns the provider already committed appear in the conversation and can receive a reply.
- RVL-009 must pass tool events through the session limit before dispatch, deduplicate call IDs and validate tool arguments and completion independently.
- Tools restricted to toy state. Validate schema, names, ranges, timeouts and call IDs. Never grant tools based on model instructions.
- Restricted Interviewer is a prompt and policy experiment, not guaranteed injection defense. No secrets or unrelated tools in context. Offline enforcement tests plus benign/adversarial manual test cases.
- No audio/transcript persistence or raw Base64 logging. Redact credentials and personal text; use synthetic fixtures or consenting adult voice. API provider retention is separate from local retention.
- The cost display uses reported Realtime text and audio tokens, including cached input and reasoning within output. It shows pending responses separately and displays an em dash when the estimate is unavailable. Input transcription has its own charges. Actual charges may differ from the estimate.

## Pricing and optional cutoff

The bundled [pricing file](../../src/main/resources/io/github/stevdrey/realtimevoicelab/realtime/realtime-pricing.properties) records rates in USD per million tokens, verified on 2026-11-27. Review the provider's current rates before use. [GPT-Realtime-2.1 mini pricing](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini) · [GPT-Realtime-2.1 pricing](https://developers.openai.com/api/docs/models/gpt-realtime-2.1)

- Set `RVL_PRICING_FILE` before starting the app to replace the bundled rates. Use the same properties with an ISO date and six nonnegative decimal rates per model. An omitted or invalid row leaves that model's pricing unavailable.
- Set `RVL_ESTIMATED_SPEND_LIMIT_USD` to a positive decimal, such as `0.05`, to enable the cutoff. When reported response costs reach 80% of the configured amount, the app stops the session before starting more work. The remaining 20% is a safety margin. With the cutoff enabled, invalid settings or missing model pricing block Connect, and unavailable terminal usage ends the session.
- The cutoff evaluates usage when responses finish. In-flight responses, separate transcription charges, rate changes, and charges outside this client can exceed the configured amount. The baseline time, audio, output and tool limits apply whether the cutoff is enabled or disabled.
