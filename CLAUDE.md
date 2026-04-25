# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See [AGENTS.md](./AGENTS.md) for the full project guidelines including build commands, architecture overview, coding conventions, ACP protocol references, and development workflows.

## Quick Reference

### Build & Test Commands
- `.\gradlew.bat runIde` — launch sandbox IDE with plugin
- `.\gradlew.bat buildPlugin` — build distributable ZIP to `build/distributions`
- `.\gradlew.bat check` — run tests and quality gates (includes Kover coverage)
- `.\gradlew.bat verifyPlugin` — plugin structure and compatibility checks
- `.\gradlew.bat runIdeForUiTests` — IDE configured for UI automation

### Architecture Overview
This is an IntelliJ Platform plugin for connecting to multiple ACP-compatible AI agents. Key architectural boundaries:

- **Transport/Session/Render-state** layers are kept separate for future native JetBrains UI rebuild
- **ACP bridge** lives in project-scoped services (`services/`) — not UI code
- **Tool Window UI** is native Swing under `toolwindow/ui/` — no JCEF or embedded browser
- **Multi-agent concurrency** is a first-class requirement

### Key Files
- `src/main/resources/META-INF/plugin.xml` — plugin metadata
- `src/main/resources/messages/MyBundle.properties` — localized strings (Chinese `MyBundle_zh.properties`)
- `docs/ACP_SDK_REFERENCE.md` — ACP Kotlin SDK integration patterns
- `docs/JETBRAINS_UI_DEVELOPMENT_GUIDE.md` — IntelliJ UI开发参考

### Reference Codebases
- Local ACP SDK: `E:\workplace\kotlin-sdk`
- Local IntelliJ Community source: `D:\workspace\intellij-community\platform\platform-impl\internal\src\com\intellij\internal`
