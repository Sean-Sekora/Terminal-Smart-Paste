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

            // Get the terminal widget using the Classic Terminal API
            // Note: Works with both Classic and Reworked terminals in 2025.3
            val widget = TerminalView.getWidgetByContent(selectedContent)
            
            if (widget != null) {
                val terminalWidget = widget as? ShellTerminalWidget
                if (terminalWidget != null) {
                    // Classic Terminal
                    val ttyConnector = terminalWidget.ttyConnector
                    if (ttyConnector != null) {
                        ttyConnector.write(text)
                        return
                    } else {
                        showError("Could not access terminal connector")
                        return
                    }
                }
            }

            // Try Reworked Terminal (2025.3+) if widget is null or not ShellTerminalWidget
            if (sendToReworkedTerminal(project, selectedContent, text)) {
                return
            }

            val widgetInfo = if (widget != null) " (got ${widget.javaClass.name})" else ""
            showError("Could not get terminal widget$widgetInfo. If using Reworked terminal, please switch to Classic terminal in Settings → Tools → Terminal.")
        } catch (e: Exception) {
            showError("Error sending to terminal: ${e.message}\nStack trace: ${e.stackTraceToString()}")
        }
    }

    private fun sendToReworkedTerminal(project: Project, content: com.intellij.ui.content.Content, text: String): Boolean {
        try {
            // Reflection to access Reworked Terminal API (available in 2025.3+)
            // We are looking for: org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager
            // and org.jetbrains.plugins.terminal.block.TerminalView (or similar)

            val tabsManagerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager")
            val getInstanceMethod = tabsManagerClass.getMethod("getInstance", Project::class.java)
            val tabsManager = getInstanceMethod.invoke(null, project)

            val getTabsMethod = tabsManagerClass.getMethod("getTabs")
            val tabs = getTabsMethod.invoke(tabsManager) as List<*>

            for (tab in tabs) {
                if (tab == null) continue
                
                // Try to match the tab with the selected content.
                // We assume the tab might have a 'content' field or 'displayName' that matches.
                // Or simply, since we know the content is selected in the tool window, 
                // we might check if this tab corresponds to it.
                // Reworked terminal tabs usually wrap the content.
                
                // Heuristic: Check if tab string representation or a property matches content name
                // This is 'best effort' without compile-time access to the API.
                
                // Better: Check if the tab has 'sendText' and use it on the *selected* tab?
                // But 'tabs' list might contain all tabs.
                // We need the one corresponding to 'content'.
                
                // Let's assume there is a method to get content from TerminalView?
                // Or check if tab.toString() contains content.displayName (Debug/Hack)
                
                // PROPER WAY if we knew the API: 
                // val terminalView = ...
                // terminalView.sendText(text)
                
                // For now, let's try to call 'sendText' on the tab that seems to match.
                // Or just try the first one if there's only one? No, dangerous.
                
                // Let's rely on the new API potentially having a static helper or the manager having 'findTab'?
                // Ignoring precise matching for a moment, let's look for 'sendText' method.
                
                val sendTextMethod = try {
                    tab.javaClass.getMethod("sendText", String::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }

                if (sendTextMethod != null) {
                    // We found a Reworked Terminal View candidate.
                    // Now match it to content.
                    // If content.displayName matches the tab title?
                    // We assume tab has 'getTitle' or similar? No standard API known via reflection easily.
                    
                    // Fallback: If the tab's class name contains "TerminalView" (Reworked), trust it?
                    // But we need the RIGHT tab.
                    
                    // Alternative: The widget we got from TerminalView.getWidgetByContent(content)
                    // might be contained in the TerminalView?
                    
                    // Let's try to invoke sendText.
                    // If we can't match, this is risky.
                    
                    // However, 'TerminalView.getWidgetByContent(content)' returns the MAIN widget.
                    // If we can find a way to map Content -> TerminalView (Reworked).
                    
                    // Let's try this: The 'tabs' list should be in the same order as content?
                    // Or maybe just try to match names.
                     val tabString = tab.toString()
                     if (tabString.contains(content.displayName) || tabs.size == 1) {
                         sendTextMethod.invoke(tab, text)
                         return true
                     }
                }
            }
            
            // Try accessing 'TerminalView' from DataContext if possible?
            // (Not accessible here easily)

        } catch (e: ClassNotFoundException) {
            // Reworked Terminal API not found (older IDE or class moved)
            return false
        } catch (e: Exception) {
            // Other reflection errors
            // System.err.println("SmartPaste: Failed to use Reworked Terminal API: $e")
            return false
        }
        return false
    }

    private fun showError(message: String) {
        // Print to stderr so it shows in the IDE's log
        System.err.println("Smart Paste Error: $message")
    }
}
