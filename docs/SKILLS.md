# Skills and workflows for this repository

This file captures the skill selection for improving this Android/Kotlin project.

## Selected skill sources

- `skill-installer` (available in this Codex session): useful for discovering/installing Codex skills. In this environment, listing remote curated skills was blocked by the proxy with HTTP 403, so no skill was installed.
- Android Skills repository (`android/skills`): relevant for Android-specific workflows such as build troubleshooting, R8 analysis, edge-to-edge guidance, and profiling.
- JetBrains Skills catalog (`JetBrains/skills`): relevant Kotlin/Gradle skills include `gradle-kotlin-dsl-doctor`, `kotlin-tooling-agp9-migration`, `performance-concurrency-advisor`, and `test-suite-builder`.
- OpenAI Skills catalog (`openai/skills`): relevant general skills include `gh-fix-ci`, `gh-address-comments`, and `security-best-practices`.

## Build/tooling decisions

- Keep Android, Kotlin, and library versions centralized in `gradle/libs.versions.toml`.
- Keep Gradle Wrapper enabled so CI and developer machines use the same Gradle version.
- Keep AGP 8.7.x on Gradle 8.9/JDK 17 until the app intentionally migrates to AGP 9.x.
- Treat `:app:assembleDebug` and `:app:lintDebug` as the minimum PR checks.

## Recommended workflow for the next iterations

1. Build hygiene: make dependency or plugin changes only through the version catalog.
2. CI hygiene: validate every PR with a debug APK build and upload the APK artifact.
3. Android hygiene: avoid app code changes that are not backed by an APK build or a clearly documented environment limitation.
4. Coroutine hygiene: keep `Bridge` helpers small, explicit, cancellable, and fair to the dispatcher.

## Current environment limitation

The local container cannot download remote Gradle distributions or Maven artifacts because outbound HTTPS requests to the required hosts return HTTP 403. GitHub CI or a developer machine with normal network access should run the authoritative APK build.
