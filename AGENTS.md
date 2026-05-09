# Repository Guidelines

## Project Scope
- This repository is an IntelliJ Platform plugin.
- Keep ACP UI flows native Swing-based; do not introduce browser/webview rendering.
- Main code lives in `src/main/kotlin/com/github/ponyhuang/agentacpplugin`.
- Tool window UI code belongs under `toolWindow/`; startup hooks under `startup/`; project-scoped logic under `services/`.
- Plugin metadata is in `src/main/resources/META-INF/plugin.xml`.
- Localization files are in `src/main/resources/messages/`.
- Tests live in `src/test/kotlin`; file fixtures live in `src/test/testData`.

## Build And Verification
- Use the Java/Kotlin 21 toolchain configured by Gradle.
- `./gradlew runIde` or `.\gradlew.bat runIde`: launch a sandbox IDE with the plugin.
- `./gradlew buildPlugin`: build the distributable ZIP in `build/distributions`.
- `./gradlew check`: run tests and quality gates, including Kover XML coverage.
- `./gradlew verifyPlugin`: run plugin structure checks and Plugin Verifier tasks.
- `./gradlew runIdeForUiTests`: launch the IDE configured for UI automation.

## Code Style
- Language: Kotlin (JVM), 4-space indentation, IntelliJ formatter-compatible.
- Package prefix must remain `github.ponyhuang.acpplugin`.
- Use `UpperCamelCase` for classes/objects, `lowerCamelCase` for functions/variables, and `UPPER_SNAKE_CASE` for constants.
- Keep services focused and project-scoped where appropriate with `@Service(Service.Level.PROJECT)`.
- For ACP client code, keep transport/session logic separate from Swing presentation.

## Localization
- All user-visible strings must go through `MyBundle.message()`.
- When adding UI strings, update both `messages/MyBundle.properties` and `messages/MyBundle_zh.properties`.

## Reference Sources
- Treat protocol and SDK documentation as the source of truth for ACP message and session behavior.
- Prefer the local ACP Kotlin SDK checkout at `E:\workplace\kotlin-sdk` when inspecting SDK code or types.
- Prefer the local IntelliJ Community source at `D:\workspace\intellij-community` for IntelliJ Platform API behavior.
- For concrete IntelliJ internal examples, check `D:\workspace\intellij-community\platform\platform-impl\internal\src\com\intellij\internal` before generic samples.
- Key references:
  - ACP protocol overview: https://agentclientprotocol.com/protocol/overview
  - ACP Kotlin SDK: https://github.com/agentclientprotocol/kotlin-sdk
  - ACP SDK reference: `docs/ACP_SDK_REFERENCE.md`
  - JetBrains UI guide: `docs/JETBRAINS_UI_DEVELOPMENT_GUIDE.md`
  - IntelliJ SDK code samples: https://github.com/JetBrains/intellij-sdk-code-samples

## Debugging Expectations
- For non-trivial bugs, use a layered debugging approach instead of speculative fixes.
- Separate the flow into layers such as event ingestion, state mutation, state propagation, derived-state mapping, and rendering.
- Add focused logs at layer boundaries and compare stable identifiers such as message id, session id, tool call id, file path, content length, entry count, or status.
- When mutating shared state, prefer atomic APIs such as `MutableStateFlow.update { ... }`; avoid read-transform-write patterns on shared `.value`.
- If downstream data is correct but the UI is wrong, inspect filtering, selection, and presentation rules before changing transport or session logic.
- Keep narrow rules local to the owning component or adapter; avoid global filtering unless the behavior is truly global.
- Add a regression test near the layer that actually contained the bug.
- Reference: `docs/DEBUGGING_WORKFLOW_GUIDE.md`

## Testing
- Use the IntelliJ Platform test framework with JUnit4 and `BasePlatformTestCase`.
- Name tests with `test...` methods such as `testRename`.
- Keep before/after fixture pairs explicit under `src/test/testData`.
- Run `./gradlew check` before opening a PR.
- If the user explicitly says they will handle follow-up test execution, stopping at successful compilation is acceptable.
- If some tests are flaky, environment-specific, or already failing, do not let them block normal implementation work; prioritize keeping compilation green and call out any skipped verification.

## Commits And PRs
- Use short imperative commit subjects, for example `Template cleanup`.
- Keep one logical change per commit; add body context when behavior changes.
- PRs should include summary, risk or impact, test evidence from `check` or `verifyPlugin`, and screenshots or GIFs for tool window UI changes.
- Link related issues or tasks and note ACP protocol or SDK compatibility implications when relevant.

## Security And Compatibility
- Never commit publishing secrets.
- Use environment variables for sensitive values: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- Keep plugin compatibility settings such as `pluginSinceBuild` and platform version aligned with `gradle.properties`, and validate them with `verifyPlugin`.
