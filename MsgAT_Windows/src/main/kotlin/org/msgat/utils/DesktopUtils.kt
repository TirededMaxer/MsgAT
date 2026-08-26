package org.msgat.utils

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

object DesktopUtils {

    private var trayIcon: TrayIcon? = null

    fun setTrayIcon(icon: TrayIcon?) {
        trayIcon = icon
    }

    // Displays a desktop system notification.
    fun showNotification(title: String, message: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        if (SystemTray.isSupported() && trayIcon != null) {
            trayIcon?.displayMessage(title, message, type)
        } else {
            println("[Notification] $title: $message")
        }
    }

    // Copies a plain text string to the system clipboard.
    fun copyToClipboard(text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Opens a web URL in the system default web browser.
    fun openWebUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Opens the file in the default system viewer or reveals it in Explorer.
    fun openFile(file: File) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Opens a native file picker dialog to choose one or more files.
    fun openFileDialog(title: String = "Select File", mode: Int = FileDialog.LOAD): File? {
        val frame: Frame? = null
        val fileDialog = FileDialog(frame, title, mode)
        fileDialog.isVisible = true
        val directory = fileDialog.directory
        val file = fileDialog.file
        return if (directory != null && file != null) {
            File(directory, file)
        } else null
    }

    // Checks if the system clipboard contains an image.
    fun getClipboardImage(): java.awt.Image? {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
