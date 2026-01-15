# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Terminal-Smart-Paste is an IntelliJ Platform plugin that enhances clipboard-to-terminal pasting. It intelligently detects clipboard content types (images, files, text) and converts them appropriately before pasting into the terminal:
- **Images**: Saved to temp file, path pasted
- **Files**: File paths pasted (quoted if spaces present)
- **Text**: Pasted as-is

Built on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) with Kotlin, targeting IntelliJ IDEA 2025.2.5+ (build 252+).

## How to Use

### Prerequisites

**IMPORTANT: Classic Terminal Required**

This plugin currently only works with the **Classic Terminal** implementation, not the new "Reworked 2025" terminal. To enable Classic terminal:

1. Open **Settings** (`Cmd+,` on macOS)
2. Navigate to **Tools → Terminal**
3. Find **"Use new terminal instead of classic"** or similar setting
4. **Uncheck** this option to switch to Classic terminal
5. Click **OK** or **Apply**
6. **Close and reopen** any existing terminal tabs

**Why Classic Terminal?** The new Reworked Terminal API won't be stable until IntelliJ 2025.3+. JetBrains has committed to supporting the Classic Terminal API for at least two more releases, so this plugin will continue working through 2025.x versions.

### Accessing Smart Paste

**Primary Method: Find Action**
1. Open the Terminal tool window in IntelliJ (Classic terminal must be enabled)
2. Copy content (image, file, or text) to clipboard
3. Press `Cmd+Shift+A` (macOS) or `Ctrl+Shift+A` (Windows/Linux)
4. Type "Smart Paste" and press Enter
5. The content will be pasted at your cursor **without executing**

**Optional: Custom Keyboard Shortcut**

The plugin.xml defines `Shift+Cmd+V` as the default shortcut, but it may not work due to:
- System-level keyboard shortcut conflicts (macOS clipboard shortcuts)
- IntelliJ keymap precedence rules
- Terminal focus requirements

To assign your own shortcut:
1. Go to **Settings → Keymap** (`Cmd+,` then search "Keymap")
2. Search for "Smart Paste"
3. Right-click → Add Keyboard Shortcut
4. Choose a shortcut that doesn't conflict (avoid `Option+V`, `Cmd+Shift+V`)
5. Recommended alternatives: `Ctrl+Shift+V`, `Cmd+Alt+V`, or any unused combination

### Testing the Plugin

**Development Testing:**
```bash
./gradlew runIde  # Launch test IDE with plugin installed
```

**Test Cases:**
1. **Image Paste**: Take a screenshot → Find Action → Smart Paste → Should paste temp file path like `/tmp/claude_paste_12345.png`
2. **File Paste**: Copy a file from Finder/Project View → Find Action → Smart Paste → Should paste file path (quoted if spaces present)
3. **Text Paste**: Copy plain text → Find Action → Smart Paste → Should paste text as-is

**Note:** The action requires the Terminal tool window to be active. If invoked outside terminal context, it will silently fail.

## Build Requirements

**JDK**: Requires JDK 21. **CRITICAL**: Do NOT use Microsoft JDK - it has a known compatibility issue with IntelliJ Platform Gradle Plugin's `instrumentCode` task (missing `Packages` directory). Use one of these instead:
- Eclipse Temurin JDK 21 (recommended): `brew install --cask temurin@21`
- Azul Zulu JDK 21: `brew install --cask zulu@21`
- JetBrains Runtime (JBR) from an IntelliJ installation

Set `JAVA_HOME` before running Gradle commands:
```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
```

## Key Build Commands

### Development
```bash
./gradlew runIde                    # Launch IDE with plugin installed for testing
./gradlew buildPlugin               # Build plugin ZIP for distribution (output: build/distributions/)
./gradlew check                     # Run all tests
./gradlew verifyPlugin              # Validate plugin.xml and structure
```

### Testing & Quality
```bash
./gradlew test                      # Run unit tests
./gradlew runPluginVerifier         # Check binary compatibility with target IDE versions
./gradlew :qodana                   # Run code quality inspections (Qodana)
./gradlew koverHtmlReport           # Generate code coverage report
```

### Publishing
```bash
./gradlew patchPluginXml            # Update plugin.xml with version/changelog
./gradlew publishPlugin             # Publish to JetBrains Marketplace (requires token)
./gradlew signPlugin                # Sign plugin ZIP (requires certificates)
```

### Run Configurations
Pre-configured run configurations exist in `.run/`:
- **Run Plugin.run.xml**: Launch IDE with plugin
- **Run Tests.run.xml**: Execute test suite
- **Run Verifications.run.xml**: Run all verification tasks

## Architecture

### Core Components

**SmartPasteAction** (`src/main/kotlin/ai/sekora/terminalsmartpaste/SmartPasteAction.kt`)
- Main action class extending `AnAction`
- Handles clipboard detection via `CopyPasteManager`
- Detects three clipboard flavors in priority order:
  1. `DataFlavor.imageFlavor` → saves to temp PNG via `ImageIO.write()`
  2. `DataFlavor.javaFileListFlavor` → extracts file paths, quotes if needed
  3. `DataFlavor.stringFlavor` → plain text fallback
- Sends text to terminal using `ToolWindowManager` + `ShellTerminalWidget.executeCommand()`

### Plugin Configuration

**plugin.xml** (`src/main/resources/META-INF/plugin.xml`)
- Declares dependency on `org.jetbrains.plugins.terminal` bundled plugin
- Registers `SmartPasteAction` as standalone action (not in any action group)
- Defines optional keyboard shortcut `Shift+Cmd+V` (may not work due to conflicts)
- Action accessible via Find Action (`Cmd+Shift+A`)
- Note: Terminal context menu groups (Terminal.ToolWindow, TerminalSessionContextMenuGroup) are unstable/non-existent in 2025.2.5

**gradle.properties**
- `pluginGroup`: `ai.sekora.terminalsmartpaste`
- `platformVersion`: IntelliJ IDEA version (currently 2025.2.5)
- `pluginSinceBuild`: Minimum supported build number (252)
- `platformBundledPlugins`: Required bundled plugins (terminal)

### Build System

Uses **IntelliJ Platform Gradle Plugin 2.x** with version catalog (`libs.versions.toml` implied).

Key build features:
- **Plugin verification**: Tests against `recommended()` IDE builds
- **Searchable options**: Auto-generates UI component index
- **Code instrumentation**: IntelliJ-specific bytecode transformations
- **Auto-patching**: Extracts plugin description from README.md between `<!-- Plugin description -->` markers
- **Changelog integration**: Uses `gradle-changelog-plugin` to sync CHANGELOG.md with releases

## Terminal API Usage & Deprecation Notes

**Current Implementation (Working):**
The plugin uses `ToolWindowManager` to access the Terminal tool window, then calls `ShellTerminalWidget.executeCommand()` to send text. This works reliably but uses deprecated APIs:
- `TerminalView.getWidgetByContent()` - scheduled for removal
- `ShellTerminalWidget` - classic terminal only

**Why Deprecated APIs Are Used:**
The newer IntelliJ 2025.2+ Terminal API (`TerminalToolWindowManager`, `TerminalWidget.sendCommandToExecute()`) is still experimental with incomplete documentation. The official replacement APIs are "under development" and may change. The current implementation prioritizes stability over using bleeding-edge APIs.

**Future Migration Path:**
When JetBrains stabilizes the new Terminal API, migrate to:
- Use `TerminalToolWindowManager.getInstance(project)` instead of `ToolWindowManager`
- Use `TerminalWidget` interface instead of casting to `ShellTerminalWidget`
- Use `TerminalWidget.sendCommandToExecute()` for sending commands
- Reference: [Terminal Implementation Changes from v2025.2](https://platform.jetbrains.com/t/terminal-implementation-changes-from-v2025-2-of-intellij-based-ides/2264)

## Project Structure

```
src/main/kotlin/ai/sekora/terminalsmartpaste/
  └── SmartPasteAction.kt           # Single action implementation

src/main/resources/META-INF/
  └── plugin.xml                     # Plugin descriptor

src/test/kotlin/                     # Test placeholder (template code)
```

**Package Migration Note:** The codebase migrated from `com.github.seansekora.terminalsmartpaste` to `ai.sekora.terminalsmartpaste`. Old template files were deleted.

## Testing

Test framework: IntelliJ Platform Test Framework (built on JUnit)
- Extends `BasePlatformTestCase` for integration tests
- Uses `myFixture` for PSI testing utilities
- Current tests are template placeholders and need updating for actual plugin functionality

## Known Issues & Limitations

- **Terminal requirement**:
  - **Only works with Classic Terminal**, not the new "Reworked 2025" terminal
  - Users must disable "Use new terminal" in Settings → Tools → Terminal
  - Reworked Terminal API support planned for when IntelliJ 2025.3+ API stabilizes
  - JetBrains committed to supporting Classic Terminal through at least two more releases
- **Action accessibility**:
  - Keyboard shortcuts may not work due to system-level conflicts and IntelliJ keymap precedence
  - Context menu integration not available (terminal context menu groups are unstable in 2025.2.5)
  - Primary access method: Find Action (`Cmd+Shift+A` → type "Smart Paste")
  - Users can manually assign custom shortcuts via Settings → Keymap
- **No UI settings** - behavior is hardcoded
- **Temp file cleanup**: Images saved by `saveImageToTemp()` rely on OS temp cleanup (not explicitly deleted)
- **Path quoting**: Only quotes paths with spaces, doesn't handle other shell special characters (backslashes, quotes, etc.)
- **Terminal compatibility**: Works with Terminal tool window focus; silently fails if invoked outside terminal context
- **Shutdown warnings**: May see `JobCancellationException` errors during IDE shutdown from bundled Terminal plugin - these are harmless race conditions and don't affect functionality

## Plugin Description Synchronization

The plugin description in README.md must be maintained between the markers:
```markdown
<!-- Plugin description -->
Your description here
<!-- Plugin description end -->
```

This section is automatically extracted during build and inserted into `plugin.xml`.
