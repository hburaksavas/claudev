package dev.claudev.platform.windows;

import com.sun.jna.platform.win32.Advapi32Util;

/** Real Win32 identity lookup — the SID recorded on a new {@code Workspace} (domain-core's D3 single-user-v1 owner check) comes from here, never a placeholder string. */
public final class WindowsCurrentUser {

    private WindowsCurrentUser() {
    }

    public static String sid() {
        return Advapi32Util.getAccountByName(Advapi32Util.getUserName()).sidString;
    }
}
