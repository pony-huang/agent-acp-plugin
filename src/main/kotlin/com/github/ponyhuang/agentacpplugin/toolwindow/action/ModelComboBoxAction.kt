package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Model ComboBoxAction
 * @author: pony
 */
class ModelComboBoxAction(val project: Project? = null) : ComboBoxAction(), DumbAware {
}