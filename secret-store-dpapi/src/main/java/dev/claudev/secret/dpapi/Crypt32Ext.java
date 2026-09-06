package dev.claudev.secret.dpapi;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Arrays;
import java.util.List;

/** Minimal, self-declared bindings for the two DPAPI calls this adapter needs (Dpapi.h / WinCrypt.h). */
final class Crypt32Ext {

    private Crypt32Ext() {}

    /** CRYPTPROTECT_UI_FORBIDDEN — never allow a blocking OS prompt from a background service-like call. */
    static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    interface Lib extends StdCallLibrary {
        Lib INSTANCE = Native.load("crypt32", Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CryptProtectData(
                DataBlob pDataIn, WString szDataDescr, DataBlob pOptionalEntropy,
                Pointer pvReserved, Pointer pPromptStruct, int dwFlags, DataBlob pDataOut);

        boolean CryptUnprotectData(
                DataBlob pDataIn, Pointer ppszDataDescr, DataBlob pOptionalEntropy,
                Pointer pvReserved, Pointer pPromptStruct, int dwFlags, DataBlob pDataOut);
    }

    interface Kernel32Lib extends StdCallLibrary {
        Kernel32Lib INSTANCE = Native.load("kernel32", Kernel32Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer LocalFree(Pointer hMem);

        int GetLastError();
    }

    /** _CRYPTOAPI_BLOB / DATA_BLOB — WinCrypt.h. Passed by reference (pointer) per JNA's Structure default. */
    public static class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cbData", "pbData");
        }
    }
}
