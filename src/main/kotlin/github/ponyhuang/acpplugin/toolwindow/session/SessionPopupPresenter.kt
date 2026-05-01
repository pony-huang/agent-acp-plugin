package github.ponyhuang.acpplugin.toolwindow.session

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

internal class SessionPopupPresenter(
    private val anchorComponent: () -> JComponent,
    private val buildSubtitle: (AcpSessionService.SessionListItem) -> String
) {
    private var popup: JBPopup? = null

    fun show(
        sessions: List<AcpSessionService.SessionListItem>,
        onSessionSelected: (AcpSessionService.SessionListItem) -> Unit
    ) {
        popup?.cancel()

        val listModel = CollectionListModel(sessions)
        val sessionList = JBList(listModel).apply {
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
                    append("  ${buildSubtitle(value)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount >= 1) {
                        selectedValue?.let { session ->
                            popup?.cancel()
                            onSessionSelected(session)
                        }
                    }
                }
            })
        }

        if (sessions.isNotEmpty()) {
            sessionList.selectedIndex = 0
        } else {
            sessionList.emptyText.text = MyBundle.message("popup.noSessions")
        }

        val popupContent = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(460), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, sessionList)
            .setTitle(MyBundle.message("popup.sessions"))
            .setResizable(false)
            .setMovable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()
        popup?.showUnderneathOf(anchorComponent())
    }

    fun cancel() {
        popup?.cancel()
        popup = null
    }
}
