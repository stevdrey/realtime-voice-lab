# ADR-002 — GA WebSocket without SDK
Status: Accepted (planning), 2026-11-23.

Context: the course uses older Realtime API; the goal is understanding events. Alternatives: official SDK, WebRTC, JDK WebSocket. Decision: JDK HttpClient/WebSocket + Jackson and current GA shapes; configurable default gpt-realtime-2.1-mini. No legacy beta header. Consequence: explicit framing, backpressure, event ordering and playback ownership are our responsibility. Test with mock transport. Revisit SDK after learning goals met.
