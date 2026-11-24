# Vision and MVP

## Learning outcomes
Understand the GA protocol without an SDK hiding events; implement the audio loop, cancellation and tool call lifecycle; inspect costs and failures.

## Core workflow
1. Launch offline, select a model and edit a short prompt.
2. Explicitly connect; observe session acknowledgment.
3. Send text or hold the mic button; release to commit and request a response.
4. Listen to incremental audio and see approximate transcripts.
5. Inspect sanitized events, time to first audio, response completion and usage.
6. Try an allowlisted toy workspace action; disconnect cleanly.

Presets are configuration, not separate apps: Free Conversation, English Coach, Voice Calculator/Workspace and Restricted Interviewer. Basic UI: connection controls and model at top, conversation and inspector side by side, mic/stop controls below, metrics/status footer.

## Non-goals
No SaaS, accounts, database, CV ingestion, web research, browser/WebRTC, MCP server, persistent history, deployment service or shared SDK extraction. Live is optional after MVP. Linux/headphones first; other devices are best-effort.

## Success
A clean offline build and tests, two-turn text and speech sessions, safe tool execution, correct stop/interrupt behavior and documented bounded manual smoke results.
