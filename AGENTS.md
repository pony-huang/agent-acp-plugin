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
- Package prefix must remain `github.ponyhuang.acpplugin`.
- Class/object names: `UpperCamelCase`; functions/variables: `lowerCamelCase`; constants: `UPPER_SNAKE_CASE`.
- Keep services focused and project-scoped where appropriate (`@Service(Service.Level.PROJECT)`).
- For ACP client work, prefer clear transport/session abstractions and keep Swing UI code separated from protocol/state logic.

## Internationalization (i18n)
- All user-visible strings must use `MyBundle.message()` (no hardcoded strings)
- Resource files: `messages/MyBundle.properties` (English) and `messages/MyBundle_zh.properties` (Chinese)
- Add keys to both files when introducing new UI strings

## ACP Reference Baseline
- Protocol overview: https://agentclientprotocol.com/protocol/overview
- Kotlin SDK: https://github.com/agentclientprotocol/kotlin-sdk
- Local ACP Kotlin SDK checkout: `E:\workplace\kotlin-sdk`
- **ACP SDK API Reference**: `docs/ACP_SDK_REFERENCE.md` - Comprehensive guide for ACP Kotlin SDK types, interfaces, and integration patterns
- When inspecting ACP SDK source or types, prefer reading the local checkout at `E:\workplace\kotlin-sdk` instead of decompiling or unpacking Maven cache artifacts.
- Treat protocol and SDK docs as the source of truth for ACP message/session behavior.

## IntelliJ Plugin API Reference Baseline
- When the task is about JetBrains IntelliJ Platform / IntelliJ Plugin API usage, prefer reading local IntelliJ Community source at `D:\workspace\intellij-community`.
- For concrete internal usage examples, prefer checking `D:\workspace\intellij-community\platform\platform-impl\internal\src\com\intellij\internal` before falling back to generic examples.
- **JetBrains UI 开发指南**: `docs/JETBRAINS_UI_DEVELOPMENT_GUIDE.md` - 整理自 IntelliJ Community 的 UI 示例代码，包含对话框、Kotlin UI DSL、组件、验证、布局等开发参考。
- **IntelliJ SDK Code Samples**: https://github.com/JetBrains/intellij-sdk-code-samples - Official JetBrains collection of plugin development examples covering action, dialog, toolwindow, projectWizard, and more.
- Use local IntelliJ source and bundled examples as the primary reference for IntelliJ Plugin API behavior in this repository.

## Debugging Workflow
- For non-trivial bugs, prefer a layered debugging approach instead of speculative fixes.
- First separate the problem into likely layers, such as: event ingestion, service state mutation, state propagation, derived-state mapping, and final rendering/presentation.
- Add focused logs at the boundary between those layers, reproduce once, and compare the same entity across layers using stable identifiers such as message id, session id, tool call id, file path, content length, entry count, or status.
- When mutating shared state, prefer atomic update APIs such as `MutableStateFlow.update { ... }`; avoid read-transform-write patterns on shared `.value` state when updates can interleave.
- If logs show downstream layers receive correct final data but the user-visible result is still wrong, inspect filtering, selection, and presentation rules before changing upstream transport or session logic.
- Keep narrow rules local to the component or adapter that owns them; avoid broad message-level or global filtering unless the behavior is truly global.
- Add a regression test after identifying the failing layer, usually near the layer that actually contained the bug.
- Reference: `docs/DEBUGGING_WORKFLOW_GUIDE.md`

## Testing Guidelines
- Test framework is IntelliJ Platform test framework with JUnit4 (`BasePlatformTestCase`).
- Name tests as `test...` methods (for example, `testRename`, `testProjectService`).
- Store file-based fixtures under `src/test/testData/...` and keep before/after pairs explicit (`foo.xml`, `foo_after.xml`).
- Run `./gradlew check` before opening a PR.
- If the user explicitly says they will handle follow-up test execution themselves, it is acceptable to stop at successful compilation for the current change and leave test execution to the user.

## Commit & Pull Request Guidelines
- Current history uses short imperative-style subjects (for example, `Template cleanup`). Keep subject lines concise and action-oriented.
- One logical change per commit; include rationale in the body when behavior changes.
- PRs should include: summary, risk/impact, test evidence (`check`/`verifyPlugin` results), and screenshots or GIFs for Tool Window UI changes.
- Link related issues/tasks and note any ACP protocol/SDK compatibility implications.

## Security & Configuration Tips
- Never commit publishing secrets. Use environment variables: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- Keep plugin compatibility settings (`pluginSinceBuild`, platform version) aligned with `gradle.properties` and verify with `verifyPlugin`.
