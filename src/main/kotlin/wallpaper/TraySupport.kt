package wallpaper

import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** AWT system tray: simple, dependency-free controls shared across desktop platforms. */
class TraySupport(
    controller: Controller,
    private val showWindow: () -> Unit,
    private val quit: () -> Unit
) : AutoCloseable {
    private var icon: TrayIcon? = null
    val installed: Boolean get() = icon != null

    init {
        runCatching {
            if (SystemTray.isSupported()) {
                val menu = PopupMenu()
                fun entry(label: String, action: () -> Unit) {
                    menu.add(MenuItem(label).apply { addActionListener { action() } })
                }
                entry("Open Wallpaper Manager", showWindow)
                menu.addSeparator()
                entry("Next wallpaper", controller::next)
                entry("Random wallpaper", controller::random)
                entry("Stop playlist", controller::stop)
                menu.addSeparator()
                entry("Exit", quit)

                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                val g: Graphics2D = image.createGraphics()
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = Color(0x5067CC)
                    g.fillRoundRect(0, 0, 32, 32, 8, 8)
                    g.color = Color.WHITE
                    g.fillOval(6, 7, 9, 9)
                    g.fillPolygon(intArrayOf(3, 14, 21, 29), intArrayOf(28, 16, 23, 29), 4)
                } finally {
                    g.dispose()
                }
                val trayIcon = TrayIcon(image, "Wallpaper Manager", menu).apply {
                    isImageAutoSize = true
                    addActionListener { showWindow() }
                }
                SystemTray.getSystemTray().add(trayIcon)
                icon = trayIcon
            }
        }
    }

    fun announceHidden() {
        icon?.displayMessage(
            "Wallpaper Manager",
            "The app is still running; use the tray icon to open it.",
            TrayIcon.MessageType.INFO
        )
    }

    override fun close() {
        icon?.let {
            runCatching { SystemTray.getSystemTray().remove(it) }
        }
        icon = null
    }
}
