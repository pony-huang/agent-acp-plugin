package github.ponyhuang.acpplugin.toolwindow.ui

import javax.swing.JComponent

internal fun JComponent.revalidateAncestorChain(includeSelf: Boolean = true) {
    var current: JComponent? = if (includeSelf) this else parent as? JComponent
    while (current != null) {
        current.revalidate()
        current.repaint()
        current = current.parent as? JComponent
    }
}
