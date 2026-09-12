package dev.claudev.app;

import dev.claudev.adapter.rabbitmq.RabbitMqRuntimeProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP10b: real (not mocked) — proves {@link RabbitMqProviderHolder} actually takes the imported path
 * and never touches the network/managed-dir fallback when imported paths are configured, by giving
 * it a deliberately broken {@code managedDir} fallback: if it ever fell through to {@code
 * .provision()}, that would fail loudly (no network access to a bogus path), so a successful {@link
 * RabbitMqProviderHolder#get()} is proof the imported branch, not the fallback, actually ran.
 */
class RabbitMqProviderHolderTest {

    private static final Path SPIKE_ERLANG_HOME = Path.of("D:\\dev\\workspace\\claudev-spike\\otp27");
    private static final Path SPIKE_RABBITMQ_SBIN =
            Path.of("D:\\dev\\workspace\\claudev-spike\\rabbitmq\\rabbitmq_server-4.3.5\\sbin");

    private void requireSpikeCache() {
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");
    }

    @Test
    void isImportedConfiguredReflectsBothPropertiesBeingSet() {
        assertThat(new RabbitMqProviderHolder("unused", "", "").isImportedConfigured()).isFalse();
        assertThat(new RabbitMqProviderHolder("unused", "/erlang", "").isImportedConfigured()).isFalse();
        assertThat(new RabbitMqProviderHolder("unused", "", "/rabbitmq/sbin").isImportedConfigured()).isFalse();
        assertThat(new RabbitMqProviderHolder("unused", "/erlang", "/rabbitmq/sbin").isImportedConfigured()).isTrue();
    }

    @Test
    void getUsesTheImportedPathDirectlyWithoutFallingBackToTheBrokenManagedDir() throws Exception {
        requireSpikeCache();
        RabbitMqProviderHolder holder = new RabbitMqProviderHolder(
                "\\\\this\\path\\does\\not\\exist\\and\\would\\fail\\if\\used",
                SPIKE_ERLANG_HOME.toString(), SPIKE_RABBITMQ_SBIN.toString());

        RabbitMqRuntimeProvider provider = holder.get();

        assertThat(provider).isNotNull();
        assertThat(provider.manifest().id()).isEqualTo("adapter-rabbitmq");
    }

    @Test
    void getFailsLoudlyOnAMisconfiguredImportedPath(@TempDir Path tempDir) {
        RabbitMqProviderHolder holder = new RabbitMqProviderHolder(
                tempDir.toString(), tempDir.toString(), tempDir.toString());

        assertThatThrownBy(holder::get).isInstanceOf(IOException.class);
    }

    @Test
    void describedSourcePathReflectsWhicheverModeIsConfigured() {
        RabbitMqProviderHolder imported = new RabbitMqProviderHolder("managed-dir", "erlang-home", "rabbitmq-sbin");
        assertThat(imported.describedSourcePath()).isEqualTo(Path.of("rabbitmq-sbin"));

        RabbitMqProviderHolder managed = new RabbitMqProviderHolder("managed-dir", "", "");
        assertThat(managed.describedSourcePath()).isEqualTo(Path.of("managed-dir"));
    }
}
