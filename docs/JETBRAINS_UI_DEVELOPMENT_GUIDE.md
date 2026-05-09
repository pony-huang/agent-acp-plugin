# JetBrains Plugin UI 开发指南

本文档整理自 `D:\workspace\intellij-community\platform\platform-impl\internal` 目录下的 UI 示例代码，供日后 UI 相关开发参考。

---

## 目录结构概览

| 类别 | 路径 |
|------|------|
| 主 UI Sandbox | `com/intellij/internal/ui/sandbox/` |
| Kotlin UI DSL 展示 | `com/intellij/internal/ui/uiDslShowcase/` |
| UI 组件示例 | `com/intellij/internal/ui/sandbox/components/` |
| DSL 面板 | `com/intellij/internal/ui/sandbox/dsl/` |
| 验证相关 | `com/intellij/internal/ui/sandbox/dsl/validation/` |
| ListCellRenderer | `com/intellij/internal/ui/sandbox/dsl/listCellRenderer/` |
| Grid Layout | `com/intellij/internal/ui/gridLayoutTestAction/` |
| JCEF 示例 | `com/intellij/internal/jcef/test/detailed/` |
| Focus 相关 | `com/intellij/internal/focus/` |

---

## 1. 对话框开发 (DialogWrapper)

所有 JetBrains 对话框都继承 `com.intellij.openapi.ui.DialogWrapper`。

### 基础结构

```kotlin
internal class MyDialog(project: Project?) : DialogWrapper(project) {
    init {
        title = "My Dialog"
        init()
    }

    override fun createCenterPanel(): JComponent? {
        return panel {
            // 使用 Kotlin UI DSL 构建面板
        }
    }
}
```

### 带导航树的对话框

`UISandboxDialog.kt` 展示了树形导航+搜索过滤的实现模式：

```kotlin
internal class UISandboxDialog(private val project: Project?) : DialogWrapper(...) {
    // 使用 FilteringTreeModel, SimpleTree, ElementFilter
    // Breadcrumbs 导航
    // OnePixelSplitter 布局
}
```

---

## 2. Kotlin UI DSL

Kotlin UI DSL 是 JetBrains 推荐的表单构建方式。

### 2.1 基础面板构建

```kotlin
// DemoBasics.kt
panel {
    row("Label:") {
        textField()
    }
}
```

### 2.2 RowLayout 类型

| 类型 | 说明 |
|------|------|
| `PARENT_GRID` | 父网格布局 |
| `INDEPENDENT` | 独立布局 |
| `LABEL_ALIGNED` | 标签对齐 |

### 2.3 常用组件

```kotlin
// DemoComponents.kt
checkBox("Check me")
radioButton("Option A")
button("Click") { }
textField()
intTextField()
spinner()
textArea()
comboBox(items)
passwordField()
slider()
segmentedButton(items)
tabbedPaneHeader()
label("Text")
link("Click me") { }
contextHelp("Help text")
```

### 2.4 数据绑定

```kotlin
// DemoBinding.kt
val panel = panel {
    row {
        textField().bindText(myProperty)
        checkBox("Enable").bindSelected(myEnabledProperty)
        comboBox(items).bindItem(mySelectedItem)
    }
}

// 控制方法
panel.reset()      // 重置为初始值
panel.apply()       // 应用到模型
panel.isModified()  // 检查是否被修改
```

---

## 3. UI 组件示例

### 3.1 文本输入

```kotlin
// JBTextField - 带验证
val field = JBTextField()
field.emptyText.text = "Placeholder"
field.validationInfo("Error message") // 错误状态

// SearchTextField - 带搜索
val searchField = SearchTextField()

// 验证状态
validationInfo("Error", Level.ERROR)
validationInfo("Warning", Level.WARNING)
```

### 3.2 按钮

```kotlin
// JButton
button.addActionListener { }

// GotItButton
val gotItButton = JButton()
gotItButton.buttonType = ButtonType.help

// ActionToolbar
ActionToolbar.smallVariant
```

### 3.3 复选框和单选按钮

```kotlin
// 验证集成
checkBox("Option").validationInfo("Error", Level.ERROR)

// 同高对齐 (CheckBoxRadioButtonPanel.kt)
radioButton("A")
radioButton("B")
// 使用 RowLayout.INDEPENDENT 实现 baseline 对齐
```

### 3.4 标签页 (JBTabs)

```kotlin
// JBTabsPanel.kt
val tabs = JBTabsImpl()
tabs.addTab(TabInfo(label1, component1))
tabs.addTab(TabInfo(label2, component2))

// 侧边操作
tabs.addTab(TabInfo(label), actionGroup = DefaultActionGroup())

// 侧边组件
tab.setSideComponent(component, Position.left)
```

### 3.5 树形结构

```kotlin
// TreeWithComplexEditors.kt
val tree = Tree(TreeModel)
tree.cellRenderer = CustomTreeCellRenderer()
tree.cellEditor = CustomTreeCellEditor()

// 复杂单元格编辑器
class CustomTreeCellEditor : DefaultCellEditor {
    // 包含 checkbox + label 的组合编辑器
}
```

---

## 4. 验证 (Validation)

### 4.1 验证类型

```kotlin
// validationOnInput - 输入时验证
textField().validationOnInput { field ->
    if (field.text.isEmpty()) error("Required") else null
}

// validationOnApply - 应用时验证
textField().validationOnApply { field ->
    if (!field.text.matches(Regex("\\d+"))) error("Numbers only") else null
}

// cellValidation - 单元格验证
cellValidation {
    validateCustom { component ->
        if (invalid) ValidationInfo("Error", component) else null
    }
}
```

### 4.2 验证级别

```kotlin
ValidationInfo("Error message", Level.ERROR)
ValidationInfo("Warning message", Level.WARNING)
```

---

## 5. 分组 (Groups)

### 5.1 可折叠分组

```kotlin
// collapsibleGroup - 可折叠分组
collapsibleGroup("Advanced Settings") {
    row { ... }
    row { ... }
}

// groupRowsRange - 行范围分组
groupRowsRange("Group Label") {
    row { checkBox("Option 1") }
    row { checkBox("Option 2") }
}
```

### 5.2 可见性/启用状态绑定

```kotlin
// 可见性绑定
row {
    checkBox("Show advanced").attachTo(myVisibleProperty)
}

// enabledIf - 条件启用
textField().enabledIf(myEnabledProperty)
```

---

## 6. GridLayout 布局

### 6.1 基础用法

```kotlin
// GridLayoutTestAction.kt
val panel = JPanel(GridLayout())
RowsGridBuilder(panel)
    .defaultBaselineAlign(true)
    .row()
        .label(VerticalAlign.TOP, 14)
        .resizableRow()
        .cell(component) {
            horizontalAlign(HorizontalAlign.LEFT)
            verticalAlign(VerticalAlign.TOP)
        }
```

### 6.2 单元格属性

| 属性 | 说明 |
|------|------|
| `horizontalAlign` | 水平对齐 (LEFT, CENTER, RIGHT, FILL) |
| `verticalAlign` | 垂直对齐 (TOP, CENTER, BOTTOM, FILL) |
| `resizable()` | 可调整大小 |
| `spanCols()` | 列跨度 |
| `spanRows()` | 行跨度 |
| `gap(left, right)` | 单元格间距 |

---

## 7. ListCellRenderer

### 7.1 基础渲染器

```kotlin
// LcrListPanel.kt
listCellRenderer { list, value, index, selected, hasFocus ->
    icon(value.icon)
    append(value.name)
    if (selected) {
        foreground = JBUI.CurrentTheme.List.selectionForeground()
    }
}
```

### 7.2 文本渲染

```kotlin
textListCellRenderer { list, value, index, selected, hasFocus ->
    append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    toolTipText = value.description
}
```

### 7.3 常用属性

```kotlin
background = JBUI.CurrentTheme.List.background()
foreground = JBUI.CurrentTheme.List.foreground()
border = JBBorderFactory.createBorder()
icon = value.icon
toolTipText = "Tooltip"
```

---

## 8. JCEF (Java Chromium Embedded Framework)

### 8.1 浏览器框架

```java
// MainFrame.java
CefClient client = CefApp.getInstance().createClient();
CefLoadHandler loadHandler = new CefLoadHandlerAdapter() {
    @Override
    public void onLoadEnd(CefBrowser browser, int httpStatusCode) {
        // 页面加载完成
    }
};
client.addLoadHandler(loadHandler);
```

### 8.2 常用组件

| 组件 | 说明 |
|------|------|
| `ControlPanel` | 地址栏、导航按钮、进度条 |
| `StatusPanel` | 状态文本显示 |
| `MenuBar` | 书签管理 |

### 8.3 对话框示例

- `CookieManagerDialog` - 基于 JTable 的 Cookie 管理
- `PasswordDialog` - 密码管理
- `SearchDialog` - 搜索功能
- `DevToolsDialog` - 开发者工具

---

## 9. 提示工具 (GotItTooltip)

```kotlin
// ShowGotItDemoAction.kt
GotItTooltip("unique.id", Component)
    .withTitle("Title")
    .withText("Description")
    .withIcon(icon)
    .withLink("Learn more", ActionLinkInfo { navigate() })
    .withPosition(balloonPosition)
    .withContrastColor(Color)
    .show()
```

---

## 10. 常用 JB 组件一览

| 组件 | 说明 | 对应 Swing |
|------|------|------------|
| `JBTextField` | 文本输入框 | `JTextField` |
| `JBTextArea` | 多行文本 | `JTextArea` |
| `JBPasswordField` | 密码输入 | `JPasswordField` |
| `JBSpinner` | 微调器 | `JSpinner` |
| `JBCheckBox` | 复选框 | `JCheckBox` |
| `JBRadioButton` | 单选按钮 | `JRadioButton` |
| `JBSplitter` | 分隔面板 | `JSplitPane` |
| `JBScrollPane` | 滚动面板 | `JScrollPane` |
| `JBTabs` | 标签页 | `JTabbedPane` |
| `SearchTextField` | 搜索框 | - |
| `OnePixelSplitter` | 单像素分隔器 | - |

---

## 11. 最佳实践

### 11.1 优先使用 JB 组件
避免直接使用原生 Swing 组件，使用 `com.intellij.ui.components.*` 下的组件。

### 11.2 使用 Kotlin UI DSL
```kotlin
// 推荐
panel {
    row("Name:") {
        textField().bindText(person.name)
    }
}

// 避免
JPanel().apply {
    add(JLabel("Name:"))
    add(JTextField())
}
```

### 11.3 验证时机选择
- `validationOnInput` - 即时反馈
- `validationOnApply` - 提交时验证
- 根据实际需求选择验证时机

### 11.4 布局选择
- `RowLayout` - 表单类布局
- `GridLayout` - 网格类布局
- `OnePixelSplitter` - 分栏布局

---

## 12. 相关资源

- JetBrains Platform UI 文档
- `com.intellij.openapi.ui`
- `com.intellij.ui.dsl`
- `com.intellij.ui.components`

---

*文档生成时间: 2026-04-23*
*来源: IntelliJ Community Platform UI Examples*
