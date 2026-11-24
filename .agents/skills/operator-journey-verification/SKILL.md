---
name: operator-journey-verification
description: Verify a JavaFX desktop journey that requires observing controls, playback state, cancellation or cleanup at runtime.
---

# Desktop journey verification

Use this when the acceptance criterion is observable only in a running desktop application. Confirm the relevant build and fixture support exists. Run the app with fake transport and audio devices or synthetic fixtures in a clean environment without credentials.

Record starting state, actions and visible outcomes. Include the relevant interrupted or failed path, and check late callbacks, resource cleanup and operator-visible feedback where applicable. Use synthetic inputs in shareable screenshots. Report the OS and JDK if relevant. Record any display, device or fixture limitation and what headless tests did verify.
