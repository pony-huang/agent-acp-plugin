package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * permissions combobox
 * @author: pony
 */
class PlanComboBoxAction(val project: Project? = null) : ComboBoxAction(), DumbAware {
}