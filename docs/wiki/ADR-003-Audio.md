# ADR-003 — PCM and push-to-talk first
Status: Accepted (planning), 2026-11-23.

Context: voice streaming and turn detection are independently challenging. Alternatives: automatic VAD first, WebRTC/native media stack, manual Java Sound. Decision: PCM16 LE mono 24 kHz using TargetDataLine/SourceDataLine, manual commit then response.create. Verify device format and use explicit conversion if necessary. Add VAD/barge-in later. Consequence: controlled experiments, but the user manages turn boundaries initially; queues and cleanup must be tested.
