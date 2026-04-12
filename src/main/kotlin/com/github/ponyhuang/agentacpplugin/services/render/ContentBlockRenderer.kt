package com.github.ponyhuang.agentacpplugin.services.render

import com.agentclientprotocol.model.ContentBlock

class ContentBlockRenderer {
    fun render(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            else -> "[Unsupported content block: ${content::class.simpleName ?: "unknown"}]"
        }
    }
}
