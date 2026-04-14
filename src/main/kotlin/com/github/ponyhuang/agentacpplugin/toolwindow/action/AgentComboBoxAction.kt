package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Agent ComboBox Action
 * @author: pony
 */
class AgentComboBoxAction(val project: Project? = null) : ComboBoxAction(), DumbAware {
}