package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentIconService
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.Icon
import javax.swing.event.DocumentEvent

class AcpSettingsComponent(
    private val onRefreshRegistry: () -> Unit,
    private val onInstallAgent: (
        AcpAgentRegistryService.RegistryAgent,
        AcpPluginSettings.InstalledAgentSetting?,
        Boolean
    ) -> Unit,
    private val onUninstallAgent: (AcpPluginSettings.InstalledAgentSetting) -> Unit,
    private val onOpenLink: (String) -> Unit,
) {
    companion object {
        private val REFRESH_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        private val DEFAULT_BROWSER_HEIGHT = JBUI.scale(620)
        private val MIN_BROWSER_HEIGHT = JBUI.scale(460)
        private val ICON_BOX_SIZE = JBUI.scale(40)
        private val ICON_RENDER_SIZE = JBUI.scale(24)
    }

    internal enum class BrowserTabKind {
        OFFICIAL,
    }

    internal data class DebugActionState(
        val installEnabled: Boolean,
        val upgradeEnabled: Boolean,
        val uninstallEnabled: Boolean,
        val openLinkEnabled: Boolean,
    )

    internal data class DebugGroupState(
        val title: String,
        val itemKeys: List<String>,
    )

    internal data class DebugDetailState(
        val title: String,
        val status: String,
        val selectedSubTab: String,
        val overviewLines: List<String>,
        val detailsLines: List<String>,
    )

    internal data class DebugTabState(
        val query: String,
        val searchMode: Boolean,
        val groups: List<DebugGroupState>,
        val searchResults: List<String>,
        val selectedKey: String?,
        val selectedDetail: DebugDetailState?,
        val actions: DebugActionState,
    )

    // Settings page root. The agent browser now renders directly into the configurable content area.
    private val rootPanel = JPanel(BorderLayout())
    // Retained as a keyed map so tests/debug helpers can address tabs consistently even with one visible section.
    private val tabPanels = linkedMapOf(
        BrowserTabKind.OFFICIAL to AgentBrowserTab(),
    )
    private val agentIconService: AcpAgentIconService by lazy {
        ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)
    }

    private var allAgentItems: List<AgentItem> = emptyList()
    private var registrySnapshot: AcpAgentRegistryService.RegistrySnapshot? = null

    init {
        rootPanel.border = JBUI.Borders.empty()
        rootPanel.add(tabPanels.getValue(BrowserTabKind.OFFICIAL).component, BorderLayout.CENTER)
    }

    fun getPanel(): JComponent = rootPanel

    fun getPreferredFocusedComponent(): JComponent? = null

    fun setRegistryData(
        snapshot: AcpAgentRegistryService.RegistrySnapshot?,
        installedAgents: List<AcpPluginSettings.InstalledAgentSetting>,
    ) {
        // Keep one normalized in-memory list so grouped view, search results, and detail panel all resolve from
        // the same source of truth.
        registrySnapshot = snapshot
        allAgentItems = buildAgentItems(snapshot, installedAgents)
        val officialItems = buildOfficialItems(allAgentItems)
        tabPanels.getValue(BrowserTabKind.OFFICIAL).setData(
            items = officialItems,
            statusText = officialStatusText(installedAgents),
        )
    }

    private fun buildAgentItems(
        snapshot: AcpAgentRegistryService.RegistrySnapshot?,
        installedAgents: List<AcpPluginSettings.InstalledAgentSetting>,
    ): List<AgentItem> {
        val installedByRegistryId = installedAgents
            .filter { it.registryAgentId.isNotBlank() && !it.isLegacy }
            .associateBy { it.registryAgentId }

        val registryItems = snapshot?.agents.orEmpty().map { agent ->
            AgentItem.Registry(agent, installedByRegistryId[agent.id])
        }
        val legacyItems = installedAgents
            .filter { it.isLegacy || it.registryAgentId.isBlank() }
            .sortedBy { it.displayName.lowercase() }
            .map { AgentItem.Legacy(it) }
        return registryItems + legacyItems
    }

    private fun buildOfficialItems(items: List<AgentItem>): List<AgentItem> = items

    private fun resolveAgentIcon(item: AgentItem, size: Int): Icon {
        fun load(agentId: String, iconUrl: String?): Icon {
            val iconPath = agentIconService.resolveCachedIconPath(agentId, iconUrl)
            return agentIconService.loadIcon(iconPath, size = size)
        }

        return when (item) {
            is AgentItem.Registry -> load(item.agent.id, item.agent.icon)
            is AgentItem.Legacy -> {
                val registryAgent = registrySnapshot?.agents?.firstOrNull { it.id == item.installed.registryAgentId }
                load(item.installed.registryAgentId, registryAgent?.icon)
            }
        }
    }

    private fun officialStatusText(installedAgents: List<AcpPluginSettings.InstalledAgentSetting>): String {
        val count = registrySnapshot?.agents?.size ?: 0
        val refreshedAt = AcpPluginSettings.getInstance().registryLastRefreshMillis.takeIf { it > 0 }
            ?.let { REFRESH_TIME_FORMATTER.format(Instant.ofEpochMilli(it)) }
            ?: MyBundle.message("settings.never")
        val legacyCount = installedAgents.count { it.isLegacy || it.registryAgentId.isBlank() }
        return MyBundle.message("settings.registryStatus", count, refreshedAt, legacyCount)
    }

    private fun officialGroups(items: List<AgentItem>): List<GroupDefinition> {
        val updates = items.filterIsInstance<AgentItem.Registry>().filter { it.upgradeAvailable }
        val installed = items.filterIsInstance<AgentItem.Registry>().filter { it.installed != null && !it.upgradeAvailable }
        val available = items.filterIsInstance<AgentItem.Registry>().filter { it.installed == null }
        val legacy = items.filterIsInstance<AgentItem.Legacy>()
        return listOf(
            GroupDefinition(MyBundle.message("settings.groupUpdatesAvailable"), updates),
            GroupDefinition(MyBundle.message("settings.groupInstalled"), installed),
            GroupDefinition(MyBundle.message("settings.groupAvailable"), available),
            GroupDefinition(MyBundle.message("settings.groupLegacyImports"), legacy),
        )
    }

    private fun searchItems(items: List<AgentItem>, query: String): List<AgentItem> {
        if (query.isBlank()) {
            return emptyList()
        }
        val normalized = query.trim().lowercase()
        return items.asSequence()
            .filter { it.searchText.contains(normalized) }
            .sortedWith(
                compareBy<AgentItem>(
                    { if (it.title.lowercase().contains(normalized)) 0 else 1 },
                    { it.searchPriority },
                    { it.title.lowercase() }
                )
            )
            .toList()
    }

    private fun installMethodText(installMethod: InstallMethod?): String {
        return when (installMethod) {
            InstallMethod.NPX -> "NPX"
            InstallMethod.UVX -> "UVX"
            InstallMethod.BINARY -> "Binary"
            null -> "-"
        }
    }

    private fun distributionSummary(agent: AcpAgentRegistryService.RegistryAgent): String? {
        return when (agent.distribution.primaryInstallMethod()) {
            InstallMethod.NPX -> agent.distribution.npx?.let { distribution ->
                listOf(distribution.`package`) + distribution.args
            }?.joinToString(" ")

            InstallMethod.UVX -> agent.distribution.uvx?.let { distribution ->
                listOf(distribution.`package`) + distribution.args
            }?.joinToString(" ")

            InstallMethod.BINARY -> agent.distribution.binary.entries
                .sortedBy { it.key }
                .joinToString(" | ") { (platform, distribution) ->
                    "$platform: ${distribution.cmd}"
                }

            null -> null
        }
    }

    private fun detailState(item: AgentItem): DetailState {
        return when (item) {
            is AgentItem.Registry -> {
                val overview = buildList {
                    add(MyBundle.message("settings.detailLatestVersion", item.agent.version.ifBlank { "-" }))
                    add(MyBundle.message("settings.detailSource", item.installed?.sourceLabel?.ifBlank { MyBundle.message("agents.source.official") } ?: MyBundle.message("agents.source.official")))
                    add(MyBundle.message("settings.detailLegacy", MyBundle.message("settings.noValue")))
                }
                val details = buildList {
                    add(MyBundle.message("settings.detailAuthors", item.agent.authors.joinToString(", ").ifBlank { "-" }))
                    add(MyBundle.message("settings.detailLicense", item.agent.license.orDash()))
                    add(MyBundle.message("settings.detailInstallMethod", installMethodText(item.agent.distribution.primaryInstallMethod())))
                    add(MyBundle.message("settings.detailDistribution", distributionSummary(item.agent).orDash()))
                    add(MyBundle.message("settings.detailInstallRoot", item.installed?.installRoot.orDash()))
                    add(MyBundle.message("settings.detailCommand", item.installed?.command.orDash()))
                    add(MyBundle.message("settings.detailArguments", item.installed?.args?.joinToString(" ").orDash()))
                    add(MyBundle.message("settings.detailRepository", item.agent.repository.orDash()))
                    add(MyBundle.message("settings.detailWebsite", item.agent.website.orDash()))
                }
                DetailState(
                    title = item.title,
                    status = item.status,
                    description = item.descriptionText(),
                    overviewLines = overview,
                    detailLines = details,
                    installEnabled = !item.isInstalled,
                    upgradeEnabled = item.upgradeAvailable,
                    uninstallEnabled = item.isInstalled,
                    openLinkEnabled = item.link != null,
                    openLink = item.link,
                    registryAgent = item.agent,
                    uninstallTarget = item.installed,
                )
            }

            is AgentItem.Legacy -> {
                val overview = buildList {
                    add(MyBundle.message("settings.detailSource", item.installed.sourceLabel.orDash()))
                    add(MyBundle.message("settings.detailLegacy", MyBundle.message("settings.yesValue")))
                }
                val details = buildList {
                    add(MyBundle.message("settings.detailAuthors", "-"))
                    add(MyBundle.message("settings.detailLicense", "-"))
                    add(MyBundle.message("settings.detailInstallMethod", installMethodText(item.installed.installMethod)))
                    add(MyBundle.message("settings.detailDistribution", "-"))
                    add(MyBundle.message("settings.detailInstallRoot", item.installed.installRoot.orDash()))
                    add(MyBundle.message("settings.detailCommand", item.installed.command.orDash()))
                    add(MyBundle.message("settings.detailArguments", item.installed.args.joinToString(" ").orDash()))
                    add(MyBundle.message("settings.detailRepository", "-"))
                    add(MyBundle.message("settings.detailWebsite", "-"))
                }
                DetailState(
                    title = item.title,
                    status = item.status,
                    description = item.descriptionText(),
                    overviewLines = overview,
                    detailLines = details,
                    installEnabled = false,
                    upgradeEnabled = false,
                    uninstallEnabled = true,
                    openLinkEnabled = false,
                    openLink = null,
                    registryAgent = null,
                    uninstallTarget = item.installed,
                )
            }
        }
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"

    private inner class AgentBrowserTab {
        val component: JComponent

        private val searchField = SearchTextField()
        private val viewCardLayout = CardLayout()
        private val viewCardPanel = JPanel(viewCardLayout)
        private val defaultGroupsPanel = JPanel()
        private val searchListModel = CollectionListModel<AgentItem>()
        private val rowRenderer = AgentItemCellRenderer()
        private val searchList = createAgentList(searchListModel, "agentSearchResultsList")
        private val searchPanel = ScrollPaneFactory.createScrollPane(searchList, true)
        private val detailPanel = AgentDetailPanel()
        private val refreshButton = JButton(MyBundle.message("settings.refresh"))
        private val groupSections = mutableListOf<GroupSection>()

        private var items: List<AgentItem> = emptyList()
        private var visibleSearchItems: List<AgentItem> = emptyList()
        private var selectedKey: String? = null
        private var updatingSelection: Boolean = false

        init {
            searchField.textEditor.emptyText.text = MyBundle.message("settings.searchAgents")
            searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    rebuildVisibleState(selectedKey)
                }
            })

            defaultGroupsPanel.layout = BoxLayout(defaultGroupsPanel, BoxLayout.Y_AXIS)
            defaultGroupsPanel.isOpaque = false

            searchList.emptyText.text = MyBundle.message("settings.searchEmpty")
            searchPanel.border = JBUI.Borders.empty()
            searchPanel.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            searchPanel.viewport.isOpaque = false

            viewCardPanel.add(ScrollPaneFactory.createScrollPane(defaultGroupsPanel, true).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                viewport.isOpaque = false
            }, "default")
            viewCardPanel.add(searchPanel, "search")

            val toolbarPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(
                    searchField,
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = 0
                        weightx = 1.0
                        fill = GridBagConstraints.HORIZONTAL
                        insets = Insets(0, 0, 0, JBUI.scale(8))
                    }
                )
                add(
                    refreshButton.apply {
                        addActionListener { onRefreshRegistry() }
                        preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
                    },
                    GridBagConstraints().apply {
                        gridx = 1
                        gridy = 0
                        weightx = 0.0
                        fill = GridBagConstraints.NONE
                    }
                )
            }

            val leftPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8))).apply {
                border = JBUI.Borders.empty(8)
                add(
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(toolbarPanel, BorderLayout.NORTH)
                        add(viewCardPanel, BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER
                )
                minimumSize = Dimension(JBUI.scale(220), JBUI.scale(280))
            }

            component = OnePixelSplitter(false, 0.42f, 0.28f, 0.72f).apply {
                name = "agentBrowserSplitter"
                preferredSize = Dimension(0, DEFAULT_BROWSER_HEIGHT)
                minimumSize = Dimension(0, MIN_BROWSER_HEIGHT)
                // Keep the divider draggable even when either side temporarily reports an aggressive minimum size.
                setHonorComponentsMinimumSize(false)
                dividerWidth = JBUI.scale(3)
                firstComponent = leftPanel
                secondComponent = detailPanel.component
            }
        }

        fun setData(items: List<AgentItem>, statusText: String) {
            this.items = items
            rebuildVisibleState(selectedKey)
        }

        fun debugSetSearchQuery(query: String) {
            searchField.text = query
            rebuildVisibleState(selectedKey)
        }

        fun debugSelectItem(key: String) {
            rebuildVisibleState(key)
        }

        fun debugState(): DebugTabState {
            return DebugTabState(
                query = searchField.text,
                searchMode = searchField.text.isNotBlank(),
                groups = currentGroups().map { group ->
                    DebugGroupState(group.title, group.items.map(AgentItem::key))
                },
                searchResults = visibleSearchItems.map(AgentItem::key),
                selectedKey = selectedKey,
                selectedDetail = detailPanel.debugState(),
                actions = detailPanel.actionState(),
            )
        }

        private fun rebuildVisibleState(preferredSelectionKey: String?) {
            // Every search edit or data refresh recomputes both grouped and search views, then restores a valid
            // selection so the detail pane never drifts from the visible list state.
            val query = searchField.text.trim()
            val searchMode = query.isNotBlank()
            val groups = currentGroups()
            rebuildDefaultGroups(groups)

            visibleSearchItems = searchItems(items, query)
            rebuildSearchRows()

            viewCardLayout.show(viewCardPanel, if (searchMode) "search" else "default")
            restoreSelection(preferredSelectionKey)
        }

        private fun currentGroups(): List<GroupDefinition> {
            return officialGroups(items).filter { it.items.isNotEmpty() }
        }

        private fun rebuildDefaultGroups(groups: List<GroupDefinition>) {
            defaultGroupsPanel.removeAll()
            groupSections.clear()
            groups.forEachIndexed { index, group ->
                if (index > 0) {
                    defaultGroupsPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
                }
                val section = GroupSection(group)
                groupSections.add(section)
                defaultGroupsPanel.add(section.container)
            }
            if (groups.isEmpty()) {
                defaultGroupsPanel.add(JBPanelWithEmptyText().apply {
                    emptyText.text = MyBundle.message("settings.searchEmpty")
                    preferredSize = Dimension(JBUI.scale(220), JBUI.scale(160))
                })
            }
            defaultGroupsPanel.revalidate()
            defaultGroupsPanel.repaint()
        }

        private fun rebuildSearchRows() {
            searchListModel.replaceAll(visibleSearchItems)
        }

        private fun restoreSelection(preferredSelectionKey: String?) {
            val query = searchField.text.trim()
            if (query.isNotBlank()) {
                // In search mode we can only select from the filtered result set.
                val key = preferredSelectionKey ?: selectedKey
                val selected = visibleSearchItems.firstOrNull { it.key == key } ?: visibleSearchItems.firstOrNull()
                updateRowSelection(selected?.key)
                if (selected == null) {
                    selectedKey = null
                    detailPanel.showEmpty()
                } else {
                    applySelection(selected)
                }
                return
            }

            val key = preferredSelectionKey ?: selectedKey
            val selected = groupSections.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { it.key == key }
                ?: groupSections.firstOrNull()?.items?.firstOrNull()
            updateRowSelection(selected?.key)
            if (selected == null) {
                selectedKey = null
                detailPanel.showEmpty()
            } else {
                applySelection(selected)
            }
        }

        private inner class GroupSection(group: GroupDefinition) {
            val items: List<AgentItem> = group.items
            val list: JBList<AgentItem> = createAgentList(CollectionListModel(items), "agentGroupList:${group.title}")
            val container: JComponent = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                add(TitledSeparator("${group.title} (${items.size})"), BorderLayout.NORTH)
                add(list, BorderLayout.CENTER)
            }

            init {
                list.emptyText.clear()
                list.preferredSize = Dimension(JBUI.scale(320), list.fixedCellHeight * items.size.coerceAtLeast(1))
            }
        }

        private fun applySelection(item: AgentItem) {
            selectedKey = item.key
            updateRowSelection(item.key)
            detailPanel.show(detailState(item))
        }

        private fun updateRowSelection(selectedKey: String?) {
            updatingSelection = true
            try {
                groupSections.forEach { section ->
                    selectItem(section.list, section.items.indexOfFirst { it.key == selectedKey })
                }
                selectItem(searchList, visibleSearchItems.indexOfFirst { it.key == selectedKey })
            } finally {
                updatingSelection = false
            }
        }

        private fun createAgentList(
            model: CollectionListModel<AgentItem>,
            name: String,
        ): JBList<AgentItem> {
            return JBList(model).apply {
                this.name = name
                cellRenderer = rowRenderer
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                fixedCellHeight = JBUI.scale(52)
                visibleRowCount = 0
                border = JBUI.Borders.empty()
                isOpaque = false
                background = UIUtil.getPanelBackground()
                setExpandableItemsEnabled(false)
                addListSelectionListener { event ->
                    if (!event.valueIsAdjusting && !updatingSelection) {
                        selectedValue?.let(::applySelection)
                    }
                }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val index = locationToIndex(e.point)
                        if (index < 0) {
                            return
                        }
                        val bounds = getCellBounds(index, index) ?: return
                        if (!bounds.contains(e.point)) {
                            return
                        }
                        val item = model.getElementAt(index)
                        if (isPrimaryActionClick(item, e.x, bounds.x + bounds.width)) {
                            applySelection(item)
                            performPrimaryAction(item)
                        }
                    }
                })
            }
        }

        private fun selectItem(list: JBList<AgentItem>, index: Int) {
            if (index >= 0) {
                list.selectedIndex = index
            } else {
                list.clearSelection()
            }
        }

        private fun isPrimaryActionClick(item: AgentItem, mouseX: Int, cellMaxX: Int): Boolean {
            return mouseX >= cellMaxX - rowRenderer.measureActionWidth(item) - JBUI.scale(18)
        }

        private inner class AgentItemCellRenderer : ListCellRenderer<AgentItem> {
            private val titleLabel = JBLabel()
            private val versionLabel = JBLabel()
            private val actionButton = JButton()
            private val iconLabel = JBLabel()
            private val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(versionLabel)
            }
            private val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                border = JBUI.Borders.empty(8, 10)
                add(iconLabel, BorderLayout.WEST)
                add(textPanel, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(actionButton)
                }, BorderLayout.EAST)
            }

            init {
                titleLabel.font = JBFont.label().deriveFont(Font.BOLD)
                versionLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                iconLabel.horizontalAlignment = SwingConstants.CENTER
                iconLabel.verticalAlignment = SwingConstants.CENTER
                iconLabel.preferredSize = Dimension(ICON_BOX_SIZE, ICON_BOX_SIZE)
                iconLabel.minimumSize = iconLabel.preferredSize
                iconLabel.maximumSize = iconLabel.preferredSize
                iconLabel.border = JBUI.Borders.empty()
                actionButton.isFocusable = false
                actionButton.isOpaque = false
                actionButton.isContentAreaFilled = false
                actionButton.margin = Insets(0, 0, 0, 0)
            }

            override fun getListCellRendererComponent(
                list: JList<out AgentItem>,
                value: AgentItem,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): JComponent {
                titleLabel.text = value.title
                val statusText = value.listStatusText()
                versionLabel.text = statusText
                versionLabel.isVisible = !statusText.isNullOrBlank()
                val actionText = primaryActionText(value)
                actionButton.text = actionText
                actionButton.isEnabled = primaryActionEnabled(value)
                styleActionButton(actionButton, actionText)
                iconLabel.name = "agentIcon:${value.key}"
                iconLabel.icon = resolveAgentIcon(value, ICON_RENDER_SIZE)
                iconLabel.text = null

                panel.background = if (isSelected) list.selectionBackground else list.background
                panel.foreground = if (isSelected) list.selectionForeground else list.foreground
                panel.isOpaque = true
                return panel
            }

            fun measureActionWidth(item: AgentItem): Int {
                val actionText = primaryActionText(item)
                actionButton.text = actionText
                styleActionButton(actionButton, actionText)
                return actionButton.preferredSize.width
            }

            private fun styleActionButton(button: JButton, actionText: String) {
                val borderColor = when (actionText) {
                    MyBundle.message("settings.install"),
                    MyBundle.message("settings.upgrade") -> JBColor(0x5FAF5F, 0x5FAF5F)

                    MyBundle.message("settings.uninstall") -> JBColor(0x4C88FF, 0x4C88FF)
                    else -> JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                }
                button.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor),
                    JBUI.Borders.empty(3, 10)
                )
            }
        }

        private fun primaryActionEnabled(item: AgentItem): Boolean {
            return when (item) {
                is AgentItem.Registry -> !item.isInstalled || item.installed != null || item.link != null
                is AgentItem.Legacy -> true
            }
        }

        private fun primaryActionText(item: AgentItem): String {
            return when (item) {
                is AgentItem.Registry -> when {
                    !item.isInstalled -> MyBundle.message("settings.install")
                    item.upgradeAvailable -> MyBundle.message("settings.upgrade")
                    item.installed != null -> MyBundle.message("settings.uninstall")
                    else -> MyBundle.message("settings.openLink")
                }

                is AgentItem.Legacy -> MyBundle.message("settings.uninstall")
            }
        }

        private fun performPrimaryAction(item: AgentItem) {
            when (item) {
                is AgentItem.Registry -> {
                    val link = item.link
                    when {
                        !item.isInstalled -> onInstallAgent(item.agent, null, false)
                        item.upgradeAvailable -> onInstallAgent(item.agent, item.installed, true)
                        item.installed != null -> onUninstallAgent(item.installed)
                        link != null -> onOpenLink(link)
                    }
                }

                is AgentItem.Legacy -> onUninstallAgent(item.installed)
            }
        }
    }

    private inner class AgentDetailPanel {
        val component: JComponent

        private val cardLayout = CardLayout()
        // Empty/content cards avoid leaving stale detail UI visible when the current selection disappears.
        private val cardPanel = JPanel(cardLayout)
        private val emptyPanel = JBPanelWithEmptyText()
        private val contentPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(12)))
        private val titleLabel = JBLabel()
        private val descriptionArea = JTextArea()
        private val overviewPanel = JPanel()
        private val detailsPanel = JPanel()
        private val tabbedPane = JBTabbedPane()
        private val installButton = JButton(MyBundle.message("settings.install"))
        private val upgradeButton = JButton(MyBundle.message("settings.upgrade"))
        private val uninstallButton = JButton(MyBundle.message("settings.uninstall"))

        private var currentState: DetailState? = null

        init {
            emptyPanel.emptyText.text = MyBundle.message("settings.agentDetailsEmpty")
            cardPanel.add(emptyPanel, "empty")

            titleLabel.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size.toFloat() + JBUI.scale(5))
            titleLabel.horizontalAlignment = SwingConstants.LEFT
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT

            descriptionArea.isEditable = false
            descriptionArea.isOpaque = false
            descriptionArea.lineWrap = true
            descriptionArea.wrapStyleWord = true
            descriptionArea.border = JBUI.Borders.empty()
            descriptionArea.font = JBFont.label()
            descriptionArea.alignmentX = Component.LEFT_ALIGNMENT

            overviewPanel.layout = BoxLayout(overviewPanel, BoxLayout.Y_AXIS)
            overviewPanel.isOpaque = false
            detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
            detailsPanel.isOpaque = false

            tabbedPane.addTab(MyBundle.message("settings.overviewTab"), JBScrollPane(overviewPanel).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            })
            tabbedPane.addTab(MyBundle.message("settings.detailsTab"), JBScrollPane(detailsPanel).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            })

            val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(installButton)
                add(upgradeButton)
                add(uninstallButton)
            }

            val headerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(descriptionArea)
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(actionPanel)
            }

            contentPanel.border = JBUI.Borders.empty(16)
            contentPanel.add(headerPanel, BorderLayout.NORTH)
            contentPanel.add(tabbedPane, BorderLayout.CENTER)

            cardPanel.add(contentPanel, "content")
            component = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                add(cardPanel, BorderLayout.CENTER)
            }

            installButton.addActionListener {
                val state = currentState ?: return@addActionListener
                val agent = state.registryAgent ?: return@addActionListener
                onInstallAgent(agent, null, false)
            }
            upgradeButton.addActionListener {
                val state = currentState ?: return@addActionListener
                val agent = state.registryAgent ?: return@addActionListener
                onInstallAgent(agent, state.uninstallTarget, true)
            }
            uninstallButton.addActionListener {
                currentState?.uninstallTarget?.let(onUninstallAgent)
            }

            showEmpty()
        }

        fun showEmpty() {
            currentState = null
            titleLabel.text = ""
            descriptionArea.text = ""
            overviewPanel.removeAll()
            detailsPanel.removeAll()
            updateActions()
            cardLayout.show(cardPanel, "empty")
        }

        fun show(state: DetailState) {
            currentState = state
            titleLabel.text = state.title
            descriptionArea.text = state.description
            // The detail body is rebuilt from immutable state each time to keep the right pane deterministic.
            fillLines(overviewPanel, state.overviewLines)
            fillLines(detailsPanel, state.detailLines)
            updateActions()
            cardLayout.show(cardPanel, "content")
        }

        fun actionState(): DebugActionState {
            return DebugActionState(
                installEnabled = installButton.isEnabled,
                upgradeEnabled = upgradeButton.isEnabled,
                uninstallEnabled = uninstallButton.isEnabled,
                openLinkEnabled = false,
            )
        }

        fun debugState(): DebugDetailState? {
            val state = currentState ?: return null
            return DebugDetailState(
                title = state.title,
                status = state.status,
                selectedSubTab = tabbedPane.getTitleAt(tabbedPane.selectedIndex),
                overviewLines = state.overviewLines,
                detailsLines = state.detailLines,
            )
        }

        private fun fillLines(panel: JPanel, lines: List<String>) {
            panel.removeAll()
            lines.forEachIndexed { index, line ->
                if (index > 0) {
                    panel.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
                panel.add(createDetailLine(line))
            }
            panel.revalidate()
            panel.repaint()
        }

        private fun createDetailLine(text: String): JComponent {
            return JTextArea(text).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty()
                font = JBFont.label()
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        }

        private fun updateActions() {
            val state = currentState
            installButton.isEnabled = state?.installEnabled == true
            upgradeButton.isEnabled = state?.upgradeEnabled == true
            uninstallButton.isEnabled = state?.uninstallEnabled == true
        }
    }

    internal fun debugHasResolvedIcon(key: String): Boolean {
        val item = allAgentItems.firstOrNull { it.key == key } ?: return false
        return runCatching { resolveAgentIcon(item, JBUI.scale(20)) }.isSuccess
    }

    internal fun debugPrimaryActionText(key: String): String? {
        val item = allAgentItems.firstOrNull { it.key == key } ?: return null
        return tabPanels.values.firstOrNull()?.let { panel ->
            val method = AgentBrowserTab::class.java.getDeclaredMethod("primaryActionText", AgentItem::class.java)
            method.isAccessible = true
            method.invoke(panel, item) as String
        }
    }

    internal fun debugPrimaryStatusText(key: String): String? {
        return allAgentItems.firstOrNull { it.key == key }?.listStatusText()
    }

    internal fun debugSetSearchQuery(kind: BrowserTabKind, query: String) {
        tabPanels.getValue(kind).debugSetSearchQuery(query)
    }

    internal fun debugSelectItem(kind: BrowserTabKind, key: String) {
        tabPanels.getValue(kind).debugSelectItem(key)
    }

    internal fun debugSetDetailTab(kind: BrowserTabKind, index: Int) {
        val panelField = AgentBrowserTab::class.java.getDeclaredField("detailPanel").apply { isAccessible = true }
        val detailPanel = panelField.get(tabPanels.getValue(kind))
        val tabbedPaneField = AgentDetailPanel::class.java.getDeclaredField("tabbedPane").apply { isAccessible = true }
        val tabbedPane = tabbedPaneField.get(detailPanel) as JBTabbedPane
        tabbedPane.selectedIndex = index
    }

    internal fun debugTabState(kind: BrowserTabKind): DebugTabState {
        return tabPanels.getValue(kind).debugState()
    }

    private data class GroupDefinition(
        val title: String,
        val items: List<AgentItem>,
    )

    private data class DetailState(
        val title: String,
        val status: String,
        val description: String,
        val overviewLines: List<String>,
        val detailLines: List<String>,
        val installEnabled: Boolean,
        val upgradeEnabled: Boolean,
        val uninstallEnabled: Boolean,
        val openLinkEnabled: Boolean,
        val openLink: String?,
        val registryAgent: AcpAgentRegistryService.RegistryAgent?,
        val uninstallTarget: AcpPluginSettings.InstalledAgentSetting?,
    )

    private sealed interface AgentItem {
        val key: String
        val title: String
        val status: String
        val detail: String
        val searchText: String
        val searchPriority: Int

        fun descriptionText(): String
        fun listStatusText(): String?

        data class Registry(
            val agent: AcpAgentRegistryService.RegistryAgent,
            val installed: AcpPluginSettings.InstalledAgentSetting?,
        ) : AgentItem {
            override val key: String = "registry:${agent.id}"
            override val title: String = agent.name
            override val status: String =
                when {
                    installed == null -> MyBundle.message("settings.notInstalled")
                    installed.installedVersion.isBlank() -> MyBundle.message("settings.installed")
                    installed.installedVersion == agent.version -> MyBundle.message("settings.installedVersion", installed.installedVersion)
                    else -> MyBundle.message("settings.installedVersionLatest", installed.installedVersion, agent.version)
                }
            override val detail: String = buildString {
                append(agent.description)
                agent.distribution.primaryInstallMethod()?.name?.lowercase()?.takeIf { it.isNotBlank() }?.let { method ->
                    if (isNotBlank()) append(" • ")
                    append(method)
                }
            }
            override val searchText: String = listOf(
                agent.name,
                agent.description,
                status,
                detail,
                agent.authors.joinToString(" "),
                agent.license.orEmpty(),
                agent.repository.orEmpty(),
                agent.website.orEmpty(),
                installed?.command.orEmpty(),
                installed?.args?.joinToString(" ").orEmpty(),
            ).joinToString(" ").lowercase()
            override val searchPriority: Int
                get() = when {
                    upgradeAvailable -> 0
                    isInstalled -> 1
                    else -> 2
                }

            val isInstalled: Boolean
                get() = installed != null

            val upgradeAvailable: Boolean
                get() = installed != null && installed.installedVersion != agent.version

            val link: String?
                get() = agent.website ?: agent.repository

            override fun descriptionText(): String {
                return agent.description.ifBlank { MyBundle.message("agents.installedDefaultDescription") }
            }

            override fun listStatusText(): String? {
                return if (isInstalled) status else null
            }
        }

        data class Legacy(
            val installed: AcpPluginSettings.InstalledAgentSetting,
        ) : AgentItem {
            override val key: String = "legacy:${installed.displayName}"
            override val title: String = installed.displayName
            override val status: String = MyBundle.message("settings.legacyInstalled")
            override val detail: String = buildString {
                if (installed.description.isNotBlank()) {
                    append(installed.description)
                }
                if (installed.command.isNotBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(installed.command)
                }
            }
            override val searchText: String = listOf(
                installed.displayName,
                installed.description,
                installed.command,
                installed.args.joinToString(" "),
                installed.sourceLabel,
                status,
                detail,
            ).joinToString(" ").lowercase()
            override val searchPriority: Int = 3

            override fun descriptionText(): String {
                return installed.description.ifBlank {
                    if (installed.command.isNotBlank()) installed.command else MyBundle.message("agents.description.legacy")
                }
            }

            override fun listStatusText(): String = status
        }
    }
}
