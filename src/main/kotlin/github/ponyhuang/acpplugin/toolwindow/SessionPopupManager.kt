package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

class SessionPopupManager(
    private val anchorComponent: JComponent,
    private val onResumeSession: (
        AgentRegistry.InstalledAgent,
        String,
        AcpSessionService.SessionListItem
    ) -> Unit,
) : Disposable {
    companion object {
        private val SESSION_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    private val logger = Logger.getInstance(SessionPopupManager::class.java)
    private var sessionsPopup: JBPopup? = null

    val isShowing: Boolean
        get() = sessionsPopup?.isVisible == true

    fun show(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        sessions: List<AcpSessionService.SessionListItem>
    ) {
        logger.info("[Sessions] Creating popup UI with ${sessions.size} sessions")
        hide()
        val sessionList = createSessionList(agent, cwd, sessions)

        val popupContent = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(460), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        sessionsPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, sessionList)
            .setTitle(MyBundle.message("popup.sessions"))
            .setResizable(false)
            .setMovable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()
        sessionsPopup?.showUnderneathOf(anchorComponent)
    }

    fun hide() {
        sessionsPopup?.cancel()
        sessionsPopup = null
    }

    internal fun createSessionList(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        sessions: List<AcpSessionService.SessionListItem>
    ): JBList<AcpSessionService.SessionListItem> {
        val listModel = CollectionListModel(sessions)
        return JBList(listModel).apply {
            visibleRowCount = 8
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : ColoredListCellRenderer<AcpSessionService.SessionListItem>() {
                override fun customizeCellRenderer(
                    list: JList<out AcpSessionService.SessionListItem>,
                    value: AcpSessionService.SessionListItem?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) {
                        return
                    }
                    append(value.title?.takeIf { it.isNotBlank() } ?: value.sessionId, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${buildSessionSubtitle(value)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount >= 1) {
                        selectedValue?.let { session ->
                            this@SessionPopupManager.hide()
                            onResumeSession(agent, cwd, session)
                        }
                    }
                }
            })

            if (sessions.isNotEmpty()) {
                selectedIndex = 0
            } else {
                emptyText.text = MyBundle.message("popup.noSessions")
            }
        }
    }

    internal fun buildSessionSubtitle(session: AcpSessionService.SessionListItem): String {
        val updatedAt = session.updatedAtMillis?.let {
            SESSION_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(it))
        } ?: MyBundle.message("session.unknownUpdateTime")
        return "$updatedAt • ${shortSessionId(session.sessionId)}"
    }

    internal fun shortSessionId(sessionId: String): String {
        return if (sessionId.length <= 10) sessionId else "${sessionId.take(8)}..."
    }

    override fun dispose() {
        hide()
    }
}
