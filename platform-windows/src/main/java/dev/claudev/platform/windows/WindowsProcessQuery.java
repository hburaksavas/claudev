package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Shared, low-level reads against an already-open process HANDLE — creation time, resolved image
 * path, and a file fingerprint. Package-private: {@link ProcessIdentity} and
 * {@link WindowsProcessLauncher} are the public-facing callers.
 */
final class WindowsProcessQuery {

    private WindowsProcessQuery() {
    }

    /** 100-ns ticks between the FILETIME epoch (1601-01-01) and the Unix epoch (1970-01-01). */
    private static final long FILETIME_EPOCH_OFFSET_100NS = 116_444_736_000_000_000L;

    static Optional<Instant> creationTime(HANDLE processHandle) {
        Kernel32Ext.FileTime creation = new Kernel32Ext.FileTime();
        Kernel32Ext.FileTime exit = new Kernel32Ext.FileTime();
        Kernel32Ext.FileTime kernel = new Kernel32Ext.FileTime();
        Kernel32Ext.FileTime user = new Kernel32Ext.FileTime();

        if (!Kernel32Ext.Lib.INSTANCE.GetProcessTimes(processHandle, creation, exit, kernel, user)) {
            return Optional.empty();
        }
        creation.read(); // JNA auto-reads out-structures after the call; explicit for auditability.
        return Optional.of(toInstant(creation.toTicks()));
    }

    /**
     * The OS-resolved image path — not the path the caller originally requested — closing part of
     * the gap a symlink/junction swap could otherwise open between "what we asked to run" and
     * "what actually ran."
     */
    static String imagePath(HANDLE processHandle) {
        // 32768 chars covers the long-path (\\?\-prefixed) case; see docs/PROCESS_SAFETY.md's MAX_PATH pitfall.
        char[] buffer = new char[32768];
        int[] size = {buffer.length};
        if (!Kernel32Ext.Lib.INSTANCE.QueryFullProcessImageNameW(processHandle, 0, buffer, size)) {
            return null;
        }
        return new String(buffer, 0, size[0]);
    }

    /**
     * Reads the whole file to hash it. Fine as a one-shot check at launch/verification time; not
     * something to call on every reconciler tick against a large binary without caching — tracked
     * as a known cost, not solved here.
     */
    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static Instant toInstant(long fileTimeTicks) {
        long unixTicks100ns = fileTimeTicks - FILETIME_EPOCH_OFFSET_100NS;
        return Instant.ofEpochMilli(Math.floorDiv(unixTicks100ns, 10_000L));
    }
}
