package dev.claudev.secret.legacy;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.secret.SecretHandle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEncodedSecretStoreTest {

    @Test
    void roundTripsButIsOnlyEncodedNotEncrypted() {
        var store = new LegacyEncodedSecretStore();

        ProviderResult<SecretHandle> stored = store.store("plain-value");
        SecretHandle handle = ((ProviderResult.Ok<SecretHandle>) stored).value();

        // Deliberately reversible without any key — this is the property that makes it "legacy", not secure.
        assertThat(new String(java.util.Base64.getDecoder().decode(handle.opaqueValue())))
                .isEqualTo("plain-value");

        ProviderResult<String> resolved = store.resolve(handle);
        assertThat(((ProviderResult.Ok<String>) resolved).value()).isEqualTo("plain-value");

        assertThat(store.manifest().capabilities())
                .anyMatch(capability -> capability.name().equals("not-encrypted"));
    }
}
