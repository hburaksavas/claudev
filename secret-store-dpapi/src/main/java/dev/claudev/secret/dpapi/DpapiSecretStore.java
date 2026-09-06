package dev.claudev.secret.dpapi;

import com.sun.jna.Memory;
import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.secret.SecretHandle;
import dev.claudev.provider.secret.SecretStore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Default {@link SecretStore} adapter (D13): protects a value with user-scoped DPAPI
 * ({@code CryptProtectData}) and returns the ciphertext, base64-encoded, as the opaque handle.
 * Only {@link #resolve} ever produces plaintext, and callers must not persist that return value —
 * only the handle belongs in SQLite/logs/operation plans.
 */
public final class DpapiSecretStore implements SecretStore {

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "secret-store-dpapi", "0.1.0",
                List.of(new Capability("secret-store", "1.0")),
                "{}");
    }

    @Override
    public ProviderResult<SecretHandle> store(String plaintextValue) {
        byte[] plainBytes = plaintextValue.getBytes(StandardCharsets.UTF_8);

        Crypt32Ext.DataBlob in = toBlob(plainBytes);
        Crypt32Ext.DataBlob out = new Crypt32Ext.DataBlob();

        boolean ok = Crypt32Ext.Lib.INSTANCE.CryptProtectData(
                in, null, null, null, null, Crypt32Ext.CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok) {
            return ProviderResult.err(dpapiError("CryptProtectData"));
        }

        byte[] cipherBytes = out.pbData.getByteArray(0, out.cbData);
        Crypt32Ext.Kernel32Lib.INSTANCE.LocalFree(out.pbData);

        return ProviderResult.ok(new SecretHandle(Base64.getEncoder().encodeToString(cipherBytes)));
    }

    @Override
    public ProviderResult<String> resolve(SecretHandle handle) {
        byte[] cipherBytes = Base64.getDecoder().decode(handle.opaqueValue());

        Crypt32Ext.DataBlob in = toBlob(cipherBytes);
        Crypt32Ext.DataBlob out = new Crypt32Ext.DataBlob();

        boolean ok = Crypt32Ext.Lib.INSTANCE.CryptUnprotectData(
                in, null, null, null, null, Crypt32Ext.CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok) {
            return ProviderResult.err(dpapiError("CryptUnprotectData"));
        }

        byte[] plainBytes = out.pbData.getByteArray(0, out.cbData);
        Crypt32Ext.Kernel32Lib.INSTANCE.LocalFree(out.pbData);

        return ProviderResult.ok(new String(plainBytes, StandardCharsets.UTF_8));
    }

    @Override
    public ProviderResult<Ack> delete(SecretHandle handle) {
        // DPAPI has no server-side record to remove — the handle is the whole secret. Deletion is
        // the caller dropping its own reference to the handle.
        return ProviderResult.ok(Ack.INSTANCE);
    }

    private static Crypt32Ext.DataBlob toBlob(byte[] bytes) {
        Memory memory = new Memory(Math.max(bytes.length, 1));
        memory.write(0, bytes, 0, bytes.length);

        Crypt32Ext.DataBlob blob = new Crypt32Ext.DataBlob();
        blob.cbData = bytes.length;
        blob.pbData = memory;
        return blob;
    }

    private static ProviderError dpapiError(String apiCall) {
        int lastError = Crypt32Ext.Kernel32Lib.INSTANCE.GetLastError();
        return new ProviderError.Underlying("DPAPI", apiCall + " failed, GetLastError=" + lastError);
    }
}
