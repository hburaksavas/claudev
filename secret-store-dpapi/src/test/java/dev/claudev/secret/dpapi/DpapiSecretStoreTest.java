package dev.claudev.secret.dpapi;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.secret.SecretHandle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real (not mocked) DPAPI round trip on the machine running the build — this is Spike D's core assertion. */
class DpapiSecretStoreTest {

    @Test
    void roundTripsAPlaintextValue() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DpapiSecretStore store = new DpapiSecretStore();
        String plaintext = "sk-test-secret-value-42";

        ProviderResult<SecretHandle> stored = store.store(plaintext);
        assertThat(stored.isOk()).isTrue();
        SecretHandle handle = ((ProviderResult.Ok<SecretHandle>) stored).value();

        assertThat(handle.opaqueValue()).doesNotContain(plaintext);

        ProviderResult<String> resolved = store.resolve(handle);
        assertThat(resolved.isOk()).isTrue();
        assertThat(((ProviderResult.Ok<String>) resolved).value()).isEqualTo(plaintext);
    }
}
