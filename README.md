# agent-acp-plugin

<!-- Plugin description -->
`agent-acp-plugin` is an IntelliJ Platform plugin for connecting to multiple
AI agents through the Agent Client Protocol. The current codebase focuses on
the ACP bridge, session lifecycle, and update normalization while the plugin
GUI is being redesigned.

The product direction is explicit:

- Connect to multiple ACP-compatible agents within the same IDE project.
- Keep agent and conversation state isolated while supporting concurrent ACP
  sessions.
- Preserve clear transport, session, and render-state boundaries so a future
  native JetBrains UI can be rebuilt on top cleanly.
- Avoid JCEF or other embedded browser rendering for ACP flows.

## Development

- `.\gradlew.bat runIde`: launch a sandbox IDE with the plugin
- `.\gradlew.bat buildPlugin`: build the distributable ZIP
- `.\gradlew.bat check`: run tests and quality gates
- `.\gradlew.bat verifyPlugin`: run plugin structure and compatibility checks
- `.\gradlew.bat runIdeForUiTests`: launch the IDE configured for UI automation

## Project Structure

- `src/main/kotlin/com/github/ponyhuang/agentacpplugin/services/`:
  project-scoped ACP bridge, session, and render-state logic
- `src/main/kotlin/com/github/ponyhuang/agentacpplugin/startup/`: project
  lifecycle hooks
- `src/main/resources/META-INF/plugin.xml`: plugin metadata
- `src/test/kotlin/` and `src/test/testData/`: automated tests and fixtures

## Quality Bar

- ACP protocol behavior is the source of truth for connection and session flows.
- Multi-agent concurrency is a first-class requirement, not a later extension.
- Any future ACP surface must remain native Swing/JetBrains UI.
- `check` is required before merge, and `verifyPlugin` is required when release
  compatibility matters.

## Current MVP

- A project service launches local ACP agent commands over STDIO.
- ACP session updates are normalized into testable session and timeline state.
- The previous GUI has been removed and will be rebuilt separately.
<!-- Plugin description end -->
