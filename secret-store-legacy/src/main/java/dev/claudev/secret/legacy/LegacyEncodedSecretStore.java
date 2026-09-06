package dev.claudev.secret.legacy;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.secret.SecretHandle;
import dev.claudev.provider.secret.SecretStore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Base64 encoding only — this is NOT encryption and must never be presented as such. Exists
 * solely as an import path for legacy configs; off by default, and any instance/connection backed
 * by it must show a persistent (non-dismissible-once) "not encrypted" UI badge (D13).
 */
public final class LegacyEncodedSecretStore implements SecretStore {

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "secret-store-legacy", "0.1.0",
                List.of(new Capability("secret-store", "1.0"), new Capability("not-encrypted", "1.0")),
                "{}");
    }

    @Override
    public ProviderResult<SecretHandle> store(String plaintextValue) {
        String encoded = Base64.getEncoder().encodeToString(plaintextValue.getBytes(StandardCharsets.UTF_8));
        return ProviderResult.ok(new SecretHandle(encoded));
    }

    @Override
    public ProviderResult<String> resolve(SecretHandle handle) {
        byte[] decoded = Base64.getDecoder().decode(handle.opaqueValue());
        return ProviderResult.ok(new String(decoded, StandardCharsets.UTF_8));
    }

    @Override
    public ProviderResult<Ack> delete(SecretHandle handle) {
        return ProviderResult.ok(Ack.INSTANCE);
    }
}
