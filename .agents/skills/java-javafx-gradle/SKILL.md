---
name: java-javafx-gradle
description: Implement or review Java 27, JavaFX views with FXML and CSS, single-module Gradle, WebSocket, audio and offline JUnit changes in Realtime Voice Lab.
---

# Java, JavaFX and Gradle

- Verify `java -version` and the Gradle toolchain when building. Once the wrapper exists, use it for targeted `test --tests ...` and final `check` or `build` as appropriate. Check the repository's pinned JDK and dependency versions. Before bootstrap, report the missing wrapper.
- Keep domain state and protocol parsing independent of JavaFX and the concrete transport. Use the JDK WebSocket/HttpClient and Java Sound boundaries described by the ADRs. Prefer instance helpers in instantiable classes unless a static helper has independent utility or is required.
- Verify fragmented and malformed event handling, bounded buffers or queues, and tool validation where affected. Use fake transports and devices, synthetic PCM and fake clocks. Keep credentials out of test environments and retain only redacted evidence.

## JavaFX views

- Read `DESING.md` before changing a view. Define screens, dialogs, and reusable layouts in FXML under `src/main/resources`. Load packaged resources with `FXMLLoader`. Reserve programmatic node construction for genuinely dynamic content or custom rendering within an FXML view.
- Keep controllers focused on UI events, bindings, and presentation state. Delegate session, protocol, audio, and tool logic to their owning classes. Confine scene-graph mutations to the FX Application Thread and keep blocking audio and network work off it. Guard callbacks against late generations and verify cancellation and cleanup.
- Apply visual styles through JavaFX CSS wherever possible. Keep shared tokens in `src/main/resources/styles.css` and express visual states with style classes or pseudo-classes. Avoid inline `style` attributes and `setStyle()` when a stylesheet can express the result.
- Include `javafx.fxml` in the JavaFX configuration. If the app uses a JPMS module descriptor, declare `requires javafx.fxml` and use qualified `opens` for the packages that need reflective FXML access. Verify the changed views load with their controllers and stylesheet in an offline startup check.
