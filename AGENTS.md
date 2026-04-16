# Repository Guidelines

## Project Structure & Module Organization
- Main plugin code lives in `src/main/kotlin/com/github/ponyhuang/agentacpplugin`.
- UI entry is the Tool Window factory under `toolWindow/`; project lifecycle hooks are under `startup/`; project-scoped logic is under `services/`.
- Plugin metadata is in `src/main/resources/META-INF/plugin.xml`; localized strings are in `src/main/resources/messages/MyBundle.properties`.
- Tests are in `src/test/kotlin`, with fixture data in `src/test/testData`.
- This repository is an IntelliJ Platform plugin and should stay native Swing-based. Do not introduce browser/webview-based rendering for ACP UI flows.

## Build, Test, and Development Commands
- `./gradlew runIde` (Windows: `.\gradlew.bat runIde`): launch a sandbox IDE with the plugin.
- `./gradlew buildPlugin`: build distributable ZIP in `build/distributions`.
- `./gradlew check`: run tests and quality gates (includes coverage XML generation via Kover).
- `./gradlew verifyPlugin`: run plugin structure checks and Plugin Verifier tasks.
- `./gradlew runIdeForUiTests`: start IDE prepared for UI automation (robot server).

Use Java/Kotlin toolchain 21 as configured in Gradle.

## Coding Style & Naming Conventions
- Language: Kotlin (JVM). Use 4-space indentation and keep code formatter-compatible with IntelliJ defaults.
- Package prefix must remain `com.github.ponyhuang.agentacpplugin`.
- Class/object names: `UpperCamelCase`; functions/variables: `lowerCamelCase`; constants: `UPPER_SNAKE_CASE`.
- Keep services focused and project-scoped where appropriate (`@Service(Service.Level.PROJECT)`).
- For ACP client work, prefer clear transport/session abstractions and keep Swing UI code separated from protocol/state logic.

## ACP Reference Baseline
- Protocol overview: https://agentclientprotocol.com/protocol/overview
- Kotlin SDK: https://github.com/agentclientprotocol/kotlin-sdk
- Local ACP Kotlin SDK checkout: `E:\workplace\kotlin-sdk`
- **ACP SDK API Reference**: `docs/ACP_SDK_REFERENCE.md` - Comprehensive guide for ACP Kotlin SDK types, interfaces, and integration patterns
- When inspecting ACP SDK source or types, prefer reading the local checkout at `E:\workplace\kotlin-sdk` instead of decompiling or unpacking Maven cache artifacts.
- Treat protocol and SDK docs as the source of truth for ACP message/session behavior.

## Testing Guidelines
- Test framework is IntelliJ Platform test framework with JUnit4 (`BasePlatformTestCase`).
- Name tests as `test...` methods (for example, `testRename`, `testProjectService`).
- Store file-based fixtures under `src/test/testData/...` and keep before/after pairs explicit (`foo.xml`, `foo_after.xml`).
- Run `./gradlew check` before opening a PR.

## Commit & Pull Request Guidelines
- Current history uses short imperative-style subjects (for example, `Template cleanup`). Keep subject lines concise and action-oriented.
- One logical change per commit; include rationale in the body when behavior changes.
- PRs should include: summary, risk/impact, test evidence (`check`/`verifyPlugin` results), and screenshots or GIFs for Tool Window UI changes.
- Link related issues/tasks and note any ACP protocol/SDK compatibility implications.

## Security & Configuration Tips
- Never commit publishing secrets. Use environment variables: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- Keep plugin compatibility settings (`pluginSinceBuild`, platform version) aligned with `gradle.properties` and verify with `verifyPlugin`.
