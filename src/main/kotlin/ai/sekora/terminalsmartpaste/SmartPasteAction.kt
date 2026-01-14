package ai.sekora.terminalsmartpaste

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.File
import javax.imageio.ImageIO

class SmartPasteAction : AnAction() {

    override fun update(e: AnActionEvent) {
        // Always enable the action so it can override default keybinding
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showError("No project found")
            return
        }
        val clipboard = CopyPasteManager.getInstance().contents ?: run {
            showError("Clipboard is empty")
            return
        }

        var textToPaste = ""

        try {
            // 1. Check if clipboard has an Image (Screenshot)
            if (clipboard.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val imageData = clipboard.getTransferData(DataFlavor.imageFlavor)
                val bufferedImage = convertToBufferedImage(imageData)
                val tempFile = saveImageToTemp(bufferedImage)
                textToPaste = tempFile.absolutePath
            }
            // 2. Check if clipboard has Files (Copied from Finder/Project View)
            else if (clipboard.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = clipboard.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                // Join multiple files with spaces, quoting them if necessary
                textToPaste = files.joinToString(" ") { quotePath(it.absolutePath) }
            }
            // 3. Fallback to plain text
            else if (clipboard.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                textToPaste = clipboard.getTransferData(DataFlavor.stringFlavor) as String
            }

            if (textToPaste.isNotEmpty()) {
                sendToTerminal(project, textToPaste)
            } else {
                showError("No supported clipboard content found")
            }

        } catch (ex: Exception) {
            showError("Error processing clipboard: ${ex.message}")
        }
    }

    private fun convertToBufferedImage(imageData: Any): BufferedImage {
        return when (imageData) {
            is BufferedImage -> imageData
            is MultiResolutionImage -> {
                // Get the base resolution variant (first/default image)
                val variants = imageData.getResolutionVariants()
                if (variants.isNotEmpty()) {
                    val baseImage = variants[0]
                    if (baseImage is BufferedImage) {
                        baseImage
                    } else {
                        // Convert to BufferedImage if needed
                        val width = baseImage.getWidth(null)
                        val height = baseImage.getHeight(null)
                        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                        val g = buffered.createGraphics()
                        g.drawImage(baseImage, 0, 0, null)
                        g.dispose()
                        buffered
                    }
                } else {
                    throw IllegalStateException("MultiResolutionImage has no variants")
                }
            }
            else -> {
                // Try to convert generic Image to BufferedImage
                val img = imageData as java.awt.Image
                val width = img.getWidth(null)
                val height = img.getHeight(null)
                val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val g = buffered.createGraphics()
                g.drawImage(img, 0, 0, null)
                g.dispose()
                buffered
            }
        }
    }

    private fun saveImageToTemp(image: BufferedImage): File {
        // Create a unique temp file
        val file = File.createTempFile("claude_paste_", ".png")
        ImageIO.write(image, "png", file)
        return file
    }

    private fun quotePath(path: String): String {
        return if (path.contains(" ")) "\"$path\"" else path
    }

    @Suppress("DEPRECATION")
    private fun sendToTerminal(project: Project, text: String) {
        try {
            // Get the Terminal tool window
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            if (toolWindow == null) {
                showError("Terminal tool window not found. Please open the Terminal tool window first.")
                return
            }

            // Get the currently selected terminal tab
            val selectedContent = toolWindow.contentManager.selectedContent
            if (selectedContent == null) {
                showError("No terminal tab selected. Please open a terminal tab first.")
                return
            }

            // Get the terminal widget using the classic API (deprecated but necessary for 2025.2)
            // The new Reworked Terminal API will be available in 2025.3
            val widget = TerminalView.getWidgetByContent(selectedContent)

            if (widget == null) {
                showError("Could not get widget from TerminalView. You may be using the Reworked terminal. Please switch to Classic terminal in Settings → Tools → Terminal → Uncheck 'Use new terminal'")
                return
            }

            val terminalWidget = widget as? ShellTerminalWidget
            if (terminalWidget == null) {
                showError("Widget is not a ShellTerminalWidget (got ${widget.javaClass.name}). Please switch to Classic terminal in Settings → Tools → Terminal.")
                return
            }

            // Send the text to the terminal input (without executing it)
            // This allows the user to edit or use it with other commands like "open" or "cat"
            val ttyConnector = terminalWidget.ttyConnector
            if (ttyConnector != null) {
                // Write the text directly to the terminal input
                ttyConnector.write(text)
            } else {
                showError("Could not access terminal connector")
            }
        } catch (e: Exception) {
            showError("Error sending to terminal: ${e.message}\nStack trace: ${e.stackTraceToString()}")
        }
    }

    private fun showError(message: String) {
        // Print to stderr so it shows in the IDE's log
        System.err.println("Smart Paste Error: $message")
    }
}
