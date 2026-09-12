package dev.claudev.platform.windows.detect;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scans the Windows "Add/Remove Programs" registry entries (both native and WOW6432Node views,
 * plus the per-user hive) for installed software matching a display-name substring. Used to find
 * already-installed Erlang/RabbitMQ (and, later, Redis) without asking the user to type a path.
 *
 * <p>Never throws on a missing hive/key — an absent or inaccessible registry root just means "no
 * matches from this root," not a scan failure.
 */
public final class RegistryUninstallScanner {

    private static final Logger LOG = Logger.getLogger(RegistryUninstallScanner.class.getName());
    private static final String UNINSTALL_KEY = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private static final String UNINSTALL_KEY_WOW64 = "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    public List<UninstallEntry> findByDisplayNameContaining(String needle) {
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        List<UninstallEntry> results = new ArrayList<>();
        results.addAll(scanRoot(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_KEY, lowerNeedle));
        results.addAll(scanRoot(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_KEY_WOW64, lowerNeedle));
        results.addAll(scanRoot(WinReg.HKEY_CURRENT_USER, UNINSTALL_KEY, lowerNeedle));
        return results;
    }

    private List<UninstallEntry> scanRoot(WinReg.HKEY root, String key, String lowerNeedle) {
        List<UninstallEntry> results = new ArrayList<>();
        try {
            if (!Advapi32Util.registryKeyExists(root, key)) {
                return results;
            }
            for (String subKeyName : Advapi32Util.registryGetKeys(root, key)) {
                String subKey = key + "\\" + subKeyName;
                try {
                    readEntry(root, subKey, lowerNeedle).ifPresent(results::add);
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Skipping unreadable uninstall entry " + subKey, e);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Registry scan failed for " + root + "\\" + key, e);
        }
        return results;
    }

    private Optional<UninstallEntry> readEntry(WinReg.HKEY root, String subKey, String lowerNeedle) {
        if (!Advapi32Util.registryValueExists(root, subKey, "DisplayName")) {
            return Optional.empty();
        }
        String displayName = Advapi32Util.registryGetStringValue(root, subKey, "DisplayName");
        if (displayName == null || !displayName.toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
            return Optional.empty();
        }
        String displayVersion = Advapi32Util.registryValueExists(root, subKey, "DisplayVersion")
                ? Advapi32Util.registryGetStringValue(root, subKey, "DisplayVersion")
                : "";
        Optional<Path> installLocation = Optional.empty();
        if (Advapi32Util.registryValueExists(root, subKey, "InstallLocation")) {
            String raw = Advapi32Util.registryGetStringValue(root, subKey, "InstallLocation");
            if (raw != null && !raw.isBlank()) {
                installLocation = Optional.of(Path.of(raw));
            }
        }
        return Optional.of(new UninstallEntry(displayName, displayVersion, installLocation));
    }
}
