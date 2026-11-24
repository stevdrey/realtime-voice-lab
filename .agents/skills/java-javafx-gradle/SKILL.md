---
name: java-javafx-gradle
description: Implement or review Java 27, JavaFX, single-module Gradle, WebSocket, audio and offline JUnit changes in Realtime Voice Lab.
---

# Java, JavaFX and Gradle

- Verify `java -version` and the Gradle toolchain when building. Once the wrapper exists, use it for targeted `test --tests ...` and final `check` or `build` as appropriate. Check the repository's pinned JDK and dependency versions. Before bootstrap, report the missing wrapper.
- Keep domain state and protocol parsing independent of JavaFX and the concrete transport. Use the JDK WebSocket/HttpClient and Java Sound boundaries described by the ADRs. Prefer instance helpers in instantiable classes unless a static helper has independent utility or is required.
- Read `DESING.md` for UI changes. Confine scene-graph mutations to the FX Application Thread and keep blocking audio/network work off it. Guard callbacks against late generations and verify cancellation and cleanup.
- Verify fragmented and malformed event handling, bounded buffers or queues, and tool validation where affected. Use fake transports and devices, synthetic PCM and fake clocks. Keep credentials out of test environments and retain only redacted evidence.
