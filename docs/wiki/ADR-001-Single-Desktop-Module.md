# ADR-001 — Single desktop module
Status: Accepted (planning), 2026-11-23.

Context: a personal learning app must be easy to launch and debug. Alternatives: Spring Boot + browser, multi-module enterprise architecture, or one desktop module. Decision: Java 27 + JavaFX, Gradle wrapper and small packages with explicit constructors; no server/database/auth. Pin compatible releases in #7. Consequence: minimal setup; no production multi-user capabilities. Revisit only if use case changes.
