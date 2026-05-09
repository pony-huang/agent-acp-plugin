# ACP Chat IntelliJ Plugin

![Build](https://github.com/pony-huang/agent-acp-plugin/workflows/Build/badge.svg)

ACP Chat 是一个 IntelliJ Platform 插件，用于在 IDE 内接入 ACP Agent，提供聊天窗口、Agent 管理和基础配置能力。

> [!WARNING]
> 项目仍处于早期开发阶段，不建议直接用于生产环境。更适合测试、演示和二次开发。

<!-- Plugin description -->
ACP Chat 是一个 IntelliJ Platform 插件，用于在 IDE 内接入 ACP Agent，提供聊天窗口、Agent 管理和基础配置能力。

当前版本仍处于开发早期，更适合测试、演示与二次开发，不建议直接用于生产环境。
<!-- Plugin description end -->

## 功能

- `ACP Chat` 工具窗口
- Agent 查看与切换
- 设置页中的 Agent 安装与管理入口

## 界面

![ACP Chat Tool Window](./image/example1.png)
![ACP Chat Settings](./image/example2.png)

## 环境要求

- JDK 21
- JetBrains IDE

## 本地使用

构建插件：

```powershell
.\gradlew.bat buildPlugin
```

启动 Sandbox IDE：

```powershell
.\gradlew.bat runIde
```

运行检查：

```powershell
.\gradlew.bat check
```

构建产物位于 `build/distributions/`，可通过 IDE 的 `Install Plugin from Disk...` 手动安装。

## 目录

- `src/main/kotlin/github/ponyhuang/acpplugin/toolwindow/`：聊天窗口 UI
- `src/main/kotlin/github/ponyhuang/acpplugin/settings/`：设置页与 Agent 管理
- `src/main/kotlin/github/ponyhuang/acpplugin/services/`：会话与连接相关服务
- `src/main/resources/META-INF/plugin.xml`：插件声明

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
