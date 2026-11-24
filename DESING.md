# Realtime Voice Lab — UI Design Specification

**Status:** Approved visual direction for implementation; no UI is implemented by this document.
**Platform:** Java 27, JavaFX, JavaFX CSS; desktop-first, Linux-first.
**Scope:** Main workspace and related component states. This is a visual and interaction contract, not an expansion of the MVP. Implement each capability in its assigned issue.

## 1. Visual direction

A compact, dark, developer-oriented voice laboratory: deep navy background, slightly elevated slate panels, indigo accent, clean typography, subtle borders, and an uncluttered layout. The conversation and raw protocol inspection have equal visual importance. Make the microphone the primary action, not a generic chat send button. Preserve the approved concept rather than replacing it with the default JavaFX Modena appearance, a web dashboard, a sidebar navigation system, or a light-themed chatbot.

All initial UI labels are English. The model may converse in any supported language.

## 2. Reference layout

The original concept is represented below. The example conversation, events, waveform and metrics are illustrative; never show them as real observations.

```text
 Realtime Voice Lab                                     JavaFX Desktop
 ┌───────────────────────────────────────────────────────────────────┐
 │ ● Connected                            gpt-realtime-2.1-mini  ▾ │
 │                                                                   │
 │ ┌──────────────────────────────┐ ┌──────────────────────────────┐ │
 │ │ CONVERSATION                 │ │ EVENT INSPECTOR              │ │
 │ │                              │ │                              │ │
 │ │ Assistant                    │ │ ↑ session.update            │ │
 │ │ Hello! What would you like   │ │ ↓ session.updated            │ │
 │ │ to practice today?           │ │ ↑ input_audio_buffer.append  │ │
 │ │ ──────────────────────────── │ │ ↓ ...speech_started          │ │
 │ │ You                          │ │ ↓ ...speech_stopped          │ │
 │ │ Let's practice my English    │ │ ↓ response.created           │ │
 │ │ for a technical interview.   │ │ ↓ response.output_audio.delta│ │
 │ │ ──────────────────────────── │ │ ↓ response.done              │ │
 │ │ Assistant                    │ │                              │ │
 │ │ Great! Can you tell me about │ │                              │ │
 │ │ your experience with Java?   │ │                              │ │
 │ └──────────────────────────────┘ └──────────────────────────────┘ │
 │                                                                   │
 │           ▂▃▅▂▇▃▁▅▇▂▃▅▁▃▇▅▂▃▁▇▃▅▂▃▅▁▃▇▃▅▂▃                  │
 │                                                                   │
 │                      (⚙)    ( MIC )    (⏻)                       │
 │ ───────────────────────────────────────────────────────────────── │
 │ Latency                 Session                 Est. cost         │
 │ 420 ms                  02:34                   $0.034            │
 └───────────────────────────────────────────────────────────────────┘
```

## 3. Layout and sizing

Logical JavaFX pixels, independent of display scaling. Initial window 1100 × 720; minimum 760 × 520. Use responsive layout containers rather than fixed coordinates. User may resize the window.

| Area | Layout and behavior |
|---|---|
| App header | HBox with waveform icon, title (20 px semibold), secondary “JavaFX Desktop” caption right-aligned. |
| Workspace | VBox or BorderPane, padding 16–20 px, gap 12–16 px, rounded 12 px surface. |
| Session toolbar | HBox: status dot and text left, model ComboBox right; flexible spacer. Model selection disabled while connected if changing requires reconnect. |
| Main content | Horizontal SplitPane, initial divider 0.50; panels fill available height. Minimum panel width 260 px where feasible. |
| Conversation | Scrollable chronological entries, speaker label then message; quiet divider between messages. Optional text composer at bottom when text feature is available. |
| Event inspector | Virtualized ListView or TableView; independent scroll, compact rows, direction and type always visible. Filter and event detail added by RVL-006; detail shown in an inspector-local expandable area or dialog. |
| Audio visualization | Full-width 44–64 px canvas/region below panels. Approximately 64–80 narrow rounded bars in indigo; centered baseline; idle waveform flat or quiet. |
| Controls | Centered HBox: Settings (36–40 px), primary microphone (56–64 px), Connect/Disconnect (36–40 px); 16 px gap. |
| Metrics | Subtle top divider, three equal-width columns for Latency, Session and Est. cost; labels above values, aligned left. |

At narrower sizes, allow wrapping in toolbar, horizontal scrolling or truncation with tooltip for event names, and independent panel scrolling. Do not clip critical controls. Keep both panels visible above minimum size; let the divider be adjusted. Do not add extra pages or navigation unless a future issue requires them.

## 4. Color and typography tokens

| Token | Hex | Role |
|---|---|---|
| app background | #11151E | Outer application chrome |
| workspace | #151923 | Main dark surface from the reference |
| panel | #1E2430 | Conversation and event inspector |
| control | #272D3A | Secondary controls, selector |
| divider | #343B49 | Quiet borders and separators |
| text primary | #F3F4F6 | Main content |
| text secondary | #D1D5DB | Labels and icons |
| text muted | #9CA3AF | Metadata and captions |
| indigo accent | #818CF8 | Waveform and selected/focus accents |
| indigo heading | #A5B4FC | Panel headings |
| indigo primary | #6366F1 | Microphone button background |
| indigo hover | #5558DB | Primary button hover |
| connected / incoming | #4ADE80 | Connection status and received events |
| outgoing | #60A5FA | Sent event direction |
| danger / disconnect | #F87171 | Errors and disconnect icon |
| warning | #FBBF24 | Limits or unavailable resources |

Use system sans-serif (e.g. available Inter or Noto Sans, otherwise JavaFX System), no font download required. 20 px semibold app title, 11–12 px uppercase panel headings, 14 px conversation, 12 px metadata, 12–13 px monospace event names (system monospace fallback), 18–20 px semibold metrics. Use tabular digits if available. Spacing scale: 4, 8, 12, 16, 20, 24 px. Panel corner radius 8–10 px; circular icon controls. Avoid gradients, heavy shadows, saturated surfaces or decorative animation. Retain contrast on hover, pressed, disabled and selected states; use text or icon semantics in addition to color. Use white text/icon on a sufficiently dark primary control, checking contrast for actual font sizes.

## 5. JavaFX implementation guidance

Define view structure in FXML using JavaFX layout panes and controls such as BorderPane, VBox, HBox, SplitPane, and GridPane. Use Java code for controller behavior and dynamic rendering. Apply visual styles through JavaFX CSS wherever possible and keep shared tokens in `src/main/resources/styles.css`. The stylesheet starts with these tokens.

```css
.root {
    -rv-app: #11151e;
    -rv-workspace: #151923;
    -rv-panel: #1e2430;
    -rv-control: #272d3a;
    -rv-divider: #343b49;
    -rv-text: #f3f4f6;
    -rv-secondary: #d1d5db;
    -rv-muted: #9ca3af;
    -rv-accent: #818cf8;
    -rv-heading: #a5b4fc;
    -rv-primary: #6366f1;
    -rv-primary-hover: #5558db;
    -rv-success: #4ade80;
    -rv-outgoing: #60a5fa;
    -rv-danger: #f87171;
    -rv-warning: #fbbf24;
    -fx-background-color: -rv-app;
    -fx-font-family: "System";
    -fx-font-size: 14px;
    -fx-accent: -rv-accent;
    -fx-focus-color: -rv-accent;
    -fx-faint-focus-color: transparent;
}
.workspace {
    -fx-background-color: -rv-workspace;
    -fx-background-radius: 12;
    -fx-padding: 16;
    -fx-spacing: 12;
}
.lab-panel {
    -fx-background-color: -rv-panel;
    -fx-background-radius: 9;
    -fx-padding: 12;
    -fx-spacing: 8;
}
.panel-title {
    -fx-text-fill: -rv-heading;
    -fx-font-size: 12px;
    -fx-font-weight: bold;
}
.label { -fx-text-fill: -rv-text; }
.muted-label {
    -fx-text-fill: -rv-muted;
    -fx-font-size: 12px;
}
.round-control {
    -fx-background-color: -rv-control;
    -fx-background-radius: 999;
    -fx-text-fill: -rv-secondary;
    -fx-min-width: 40;
    -fx-min-height: 40;
    -fx-pref-width: 40;
    -fx-pref-height: 40;
}
.mic-button {
    -fx-background-color: -rv-primary;
    -fx-text-fill: white;
    -fx-min-width: 60;
    -fx-min-height: 60;
    -fx-pref-width: 60;
    -fx-pref-height: 60;
    -fx-background-radius: 999;
}
.mic-button:hover { -fx-background-color: -rv-primary-hover; }
.round-control:focused, .mic-button:focused {
    -fx-border-color: -rv-accent;
    -fx-border-width: 2;
    -fx-border-radius: 999;
}
.event-outgoing { -fx-text-fill: -rv-outgoing; }
.event-incoming { -fx-text-fill: -rv-success; }
.metric-value {
    -fx-font-size: 19px;
    -fx-font-weight: bold;
    -fx-text-fill: -rv-text;
}
```

Style ComboBox popup, ListView cells, TextArea, ScrollPane viewport, scrollbars, SplitPane divider, disabled controls and dialog backgrounds consistently; default light control skins must not remain visible in the dark workspace. Use lightweight SVGPath or JavaFX vector shapes for simple icons; no emoji-only controls, remote icon/font fetching or image dependency required. Give each icon-only button an accessible name and tooltip.

## 6. Component behavior and states

| State | Visual behavior |
|---|---|
| Disconnected | Neutral dot + “Disconnected”; Connect enabled, model selectable; mic disabled, metrics shown as em dash when unknown. |
| Connecting | “Connecting…” + progress indicator, disable repeated connect; cancellation available where supported. |
| Connected / ready | Green dot + “Connected”; microphone enabled when audio feature exists; idle waveform. |
| Capturing | Clearly visible “Recording” label and active mic state; waveform from microphone level only; elapsed recording available; release to end push-to-talk. |
| Responding / playback | “Responding” or “Speaking” status, playback waveform from output audio, Stop/Interrupt available once implemented; never silently keep playing after disconnect. |
| Closing | Disable new actions; “Disconnecting…” until cleanup completes. |
| Error / limit | Red or amber inline status, human-readable sanitized reason, recovery action when safe; do not expose raw secrets/errors. |

Settings opens a small dialog or overlay with model, voice, prompt preset, editable instructions and turn detection as features arrive. No credentials in the UI; use environment configuration. Default model is `gpt-realtime-2.1-mini`. Immutable settings require reconnect; do not present unapplied changes as active.

Conversation entries stream progressively; finalize once. Auto-scroll only if the user is already near the bottom. Preserve deliberate scroll position. Label input transcriptions as approximate where relevant. Empty state: “Connect to start a conversation.” No canned conversation shown as live. Event inspector shows timestamps, incoming/down and outgoing/up indicators, exact event types, and redacted detail. Audio events show size only, never raw Base64. Bound history and update on the FX thread in batches.

Use Canvas or a lightweight region for waveform, normalized level data and capped update rate (e.g. 30 fps), not one JavaFX node per audio sample. No microphone capture merely to animate the UI. Offline preview can use synthetic deterministic samples explicitly labeled Demo. Stop animation and clear stale data on disconnect. No FFT or advanced audio visualizer needed for MVP.

Metrics: latency is request-to-first-audio (or documented VAD equivalent); not a fabricated network RTT. Session uses monotonic elapsed time and freezes on close. Est. cost is an approximate value based on available usage and dated rates, with tooltip explaining exclusions; unknown is “—”, never zero unless known. During initial issues, unavailable metrics stay disabled or show “—”.

## 7. Accessibility and interaction

- Keyboard tab order follows toolbar, conversation, inspector, settings, mic, disconnect. Keep visible focus rings; support Enter/Space on controls and an explicit keyboard push-to-talk interaction once audio exists. Release, cancel and focus loss must not leave capture running.
- Use accessible text for icons, status changes and errors; direction arrows and labels supplement colors.
- Keep body text readable under OS display scaling and long transcripts; avoid clipping or fixed-height message bubbles.
- Use restrained 120–180 ms visual transitions where helpful, with no animation required for correctness. Waveform activity must not be the only recording indicator.
- All UI updates occur on JavaFX Application Thread; device and socket work off it. No blocking UI operations.

## 8. Issue-by-issue delivery

| Issue | UI responsibility |
|---|---|
| [RVL-001 / #7](../../issues/7) | Main shell, dark theme, layout, accessible placeholder/disabled controls. No fake live state. |
| [RVL-002 / #8](../../issues/8) | Connect, cancel and disconnect controls, connection status and sanitized session errors. |
| [RVL-003 / #9](../../issues/9) | Conversation text. |
| [RVL-004 / #10](../../issues/10) | Push-to-talk and capture indicator. |
| [RVL-005 / #11](../../issues/11) | Playback and transcripts. |
| [RVL-006 / #12](../../issues/12) | Event inspector filters and detail. |
| [RVL-007 / #13](../../issues/13) | Settings dialog and prompts. |
| [RVL-008 / #14](../../issues/14) | VAD and interruption UI. |
| [RVL-010 / #16](../../issues/16) | Optional toy workspace panel or dialog, preserving main layout. |
| [RVL-012 / #18](../../issues/18) | Actual latency, usage and estimated cost. |
| [RVL-014 / #20](../../issues/20) | Final visual and interaction verification. |

## 9. Design acceptance checklist

- [ ] Dark navy workspace, slate panels, indigo waveform and large primary mic match the reference composition.
- [ ] Conversation and Event Inspector are visible side by side, with adjustable divider and independent scroll.
- [ ] Toolbar, audio visualization, centered controls and three footer metrics retain their positions on window resize.
- [ ] All controls, popups and dialogs use consistent dark CSS; no unintentional default light skin.
- [ ] Status, keyboard focus, disabled states and connection/capture/playback indicators remain distinguishable without relying on color alone.
- [ ] No placeholder content or fabricated metrics are represented as live data.
- [ ] Inspector remains bounded and redacted; no key, private audio payload or raw Base64 appears.
- [ ] Test at 1100×720, minimum supported size and OS-scaled desktop; check text clipping, scrolling and focus.
- [ ] Attach sanitized screenshots of offline synthetic/empty states when reviewing visual changes; no paid API calls or personal data required.

The original mockup is the baseline. Changes to its visual direction should be explicit and reviewed rather than introduced accidentally during individual features.
