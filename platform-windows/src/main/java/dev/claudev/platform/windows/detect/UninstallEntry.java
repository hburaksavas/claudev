package dev.claudev.platform.windows.detect;

import java.nio.file.Path;
import java.util.Optional;

/** One entry read from a Windows "Uninstall" registry key. */
public record UninstallEntry(String displayName, String displayVersion, Optional<Path> installLocation) {
}
