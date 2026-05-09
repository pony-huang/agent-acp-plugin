package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class AgentBrowserTab(
    detailComponent: JComponent,
    private val onRefreshRegistry: () -> Unit,
    private val onSelectionChanged: (AgentItem?) -> Unit,
    private val onInstallAgent: (
        AcpAgentRegistryService.RegistryAgent,
        AcpPluginSettings.InstalledAgentSetting?,
        Boolean
    ) -> Unit,
    private val onUninstallAgent: (AcpPluginSettings.InstalledAgentSetting) -> Unit,
    private val onOpenLink: (String) -> Unit,
    iconResolver: (AgentItem, Int) -> Icon,
) {
    companion object {
        private val DEFAULT_BROWSER_HEIGHT = JBUI.scale(620)
        private val MIN_BROWSER_HEIGHT = JBUI.scale(460)
    }

    val component: JComponent

    private val searchField = SearchTextField()
    private val viewCardLayout = CardLayout()
    private val viewCardPanel = JPanel(viewCardLayout)
    private val defaultGroupsPanel = JPanel()
    private val searchListModel = CollectionListModel<AgentItem>()
    private val rowRenderer = AgentItemCellRenderer(
        iconResolver = iconResolver,
        primaryActionText = ::primaryActionText,
        primaryActionEnabled = ::primaryActionEnabled,
    )
    private val searchList = createAgentList(searchListModel, "agentSearchResultsList")
    private val searchPanel = ScrollPaneFactory.createScrollPane(searchList, true)
    private val refreshButton = JButton(MyBundle.message("settings.refresh"))
    private val groupSections = mutableListOf<GroupSection>()

    private var items: List<AgentItem> = emptyList()
    private var visibleSearchItems: List<AgentItem> = emptyList()
    private var selectedKey: String? = null
    private var updatingSelection: Boolean = false
    private var statusText: String = ""

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
            setHonorComponentsMinimumSize(false)
            dividerWidth = JBUI.scale(3)
            firstComponent = leftPanel
            secondComponent = detailComponent
        }
    }

    fun getPreferredFocusedComponent(): JComponent = searchField

    fun setData(items: List<AgentItem>, statusText: String) {
        this.items = items
        this.statusText = statusText
        rebuildVisibleState(selectedKey)
    }

    @TestOnly
    internal fun primaryActionText(item: AgentItem): String {
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

    @TestOnly
    internal fun performPrimaryAction(item: AgentItem) {
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

    private fun rebuildVisibleState(preferredSelectionKey: String?) {
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
        return listOf(
            GroupDefinition(
                MyBundle.message("settings.groupUpdatesAvailable"),
                items.filterIsInstance<AgentItem.Registry>().filter { it.upgradeAvailable },
            ),
            GroupDefinition(
                MyBundle.message("settings.groupInstalled"),
                items.filterIsInstance<AgentItem.Registry>().filter { it.installed != null && !it.upgradeAvailable },
            ),
            GroupDefinition(
                MyBundle.message("settings.groupAvailable"),
                items.filterIsInstance<AgentItem.Registry>().filter { it.installed == null },
            ),
            GroupDefinition(
                MyBundle.message("settings.groupLegacyImports"),
                items.filterIsInstance<AgentItem.Legacy>(),
            ),
        ).filter { it.items.isNotEmpty() }
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
                    { it.title.lowercase() },
                )
            )
            .toList()
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
            val key = preferredSelectionKey ?: selectedKey
            val selected = visibleSearchItems.firstOrNull { it.key == key } ?: visibleSearchItems.firstOrNull()
            updateRowSelection(selected?.key)
            if (selected == null) {
                selectedKey = null
                onSelectionChanged(null)
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
            onSelectionChanged(null)
        } else {
            applySelection(selected)
        }
    }

    private fun applySelection(item: AgentItem) {
        selectedKey = item.key
        updateRowSelection(item.key)
        onSelectionChanged(item)
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

    private fun primaryActionEnabled(item: AgentItem): Boolean {
        return when (item) {
            is AgentItem.Registry -> !item.isInstalled || item.installed != null || item.link != null
            is AgentItem.Legacy -> true
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
}
