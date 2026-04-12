# agent-acp-plugin

<!-- Plugin description -->
`agent-acp-plugin` is an IntelliJ Platform plugin for connecting to multiple
AI agents through the Agent Client Protocol and chatting with them concurrently
inside a native JetBrains tool window.

The product direction is explicit:

- Connect to multiple ACP-compatible agents within the same IDE project.
- Keep agent and conversation state isolated while supporting concurrent chats.
- Render the entire ACP experience with Swing and JetBrains platform UI
  components.
- Preserve a fluid IDE experience without introducing JCEF or other embedded
  browser rendering for ACP flows.

## Development

- `.\gradlew.bat runIde`: launch a sandbox IDE with the plugin
- `.\gradlew.bat buildPlugin`: build the distributable ZIP
- `.\gradlew.bat check`: run tests and quality gates
- `.\gradlew.bat verifyPlugin`: run plugin structure and compatibility checks
- `.\gradlew.bat runIdeForUiTests`: launch the IDE configured for UI automation

## Project Structure

- `src/main/kotlin/com/github/ponyhuang/agentacpplugin/toolWindow/`: native UI
  entry points and tool window rendering
- `src/main/kotlin/com/github/ponyhuang/agentacpplugin/services/`:
  project-scoped services and ACP/session logic
- `src/main/kotlin/com/github/ponyhuang/agentacpplugin/startup/`: project
  lifecycle hooks
- `src/main/resources/META-INF/plugin.xml`: plugin metadata
- `src/test/kotlin/` and `src/test/testData/`: automated tests and fixtures

## Quality Bar

- ACP protocol behavior is the source of truth for connection and session flows.
- Multi-agent concurrency is a first-class requirement, not a later extension.
- Native Swing and JetBrains UI components are required for ACP surfaces.
- `check` is required before merge, and `verifyPlugin` is required when release
  compatibility matters.

## Current MVP

- The tool window launches a local ACP agent command over STDIO.
- Session updates are normalized into timeline snapshots before any Swing
  component renders them.
- The active session view stays in a vertical splitter with `Chat Conversation`
  above `User Input`, while session/meta side panels remain native Swing.
<!-- Plugin description end -->
