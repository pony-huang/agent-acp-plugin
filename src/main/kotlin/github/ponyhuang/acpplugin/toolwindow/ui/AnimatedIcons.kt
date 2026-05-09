package github.ponyhuang.acpplugin.toolwindow.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.Timer

internal object ProcessStepIcons {
    val icons = arrayOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4,
        AllIcons.Process.Step_5,
        AllIcons.Process.Step_6,
        AllIcons.Process.Step_7,
        AllIcons.Process.Step_8
    )
}

internal open class AnimatedStatusLabel(
    private val frames: Array<Icon>,
    private val intervalMs: Int = 60
) : JBLabel() {
    private val timer = Timer(intervalMs, null)
    private var animationFrame = 0

    var animating: Boolean = false
        private set

    init {
        isOpaque = false
        timer.addActionListener {
            animationFrame = (animationFrame + 1) % frames.size
            icon = frames[animationFrame]
            repaint()
        }
        timer.isRepeats = true
    }

    fun startAnimation() {
        animationFrame = 0
        icon = frames.firstOrNull()
        animating = true
        if (isDisplayable && !timer.isRunning) {
            timer.start()
        }
    }

    fun stopAnimation() {
        animating = false
        timer.stop()
        icon = staticIcon()
        repaint()
    }

    open fun staticIcon(): Icon? = frames.firstOrNull()

    fun updateAnimation(shouldAnimate: Boolean) {
        if (shouldAnimate) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (animating && !timer.isRunning) {
            animationFrame = 0
            icon = frames.firstOrNull()
            timer.start()
        }
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}

internal open class CycleAnimatorIcon(
    private val frames: Array<Icon>,
    private val intervalMs: Int = 60,
    private val onFrameChanged: () -> Unit = {}
) : Icon {
    private val timer = Timer(intervalMs, null)
    private var animationFrame = 0

    init {
        timer.addActionListener {
            animationFrame = (animationFrame + 1) % frames.size
            onFrameChanged()
        }
        timer.isRepeats = true
    }

    fun start() {
        animationFrame = 0
        timer.start()
    }

    fun stop() {
        timer.stop()
    }

    fun dispose() {
        timer.stop()
    }

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        if (animationFrame in frames.indices) {
            frames[animationFrame].paintIcon(c, g, x, y)
        }
    }

    override fun getIconWidth(): Int = frames.first().iconWidth

    override fun getIconHeight(): Int = frames.first().iconHeight
}
