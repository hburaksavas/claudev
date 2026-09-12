package dev.claudev.adapter.rabbitmq;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real (not mocked): enables/disables a real plugin (Shovel) on a real running RabbitMQ node
 * through {@link RabbitMqRuntimeProvider}'s WP10c plugin methods.
 */
class RabbitMqRuntimeProviderPluginsTest {

    private static final Path SPIKE_ERLANG_HOME = Path.of("D:\\dev\\workspace\\claudev-spike\\otp27");
    private static final Path SPIKE_RABBITMQ_SBIN =
            Path.of("D:\\dev\\workspace\\claudev-spike\\rabbitmq\\rabbitmq_server-4.3.5\\sbin");

    private RabbitMqRuntimeProvider provider;
    private String instanceId;
    private StartInstanceOutcome outcome;

    @BeforeEach
    void startARealNode(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");

        provider = new RabbitMqRuntimeProvider(new RabbitMqInstallation(SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN));
        instanceId = "plugin-test-" + UUID.randomUUID();
        ProviderResult<StartInstanceOutcome> started = provider.start(new StartInstanceCommand(
                instanceId, "unused", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(6041, 26041), Map.of()));
        assertThat(started).isInstanceOf(ProviderResult.Ok.class);
        outcome = ((ProviderResult.Ok<StartInstanceOutcome>) started).value();
    }

    @AfterEach
    void stopTheNode() {
        if (provider != null && outcome != null) {
            provider.stop(new StopInstanceCommand(instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
        }
    }

    @Test
    void enablingAndDisablingShovelActuallyChangesTheRealPluginState() {
        ProviderResult<List<String>> initial = provider.listEnabledPlugins(instanceId);
        assertThat(initial).isInstanceOf(ProviderResult.Ok.class);
        assertThat(((ProviderResult.Ok<List<String>>) initial).value()).doesNotContain("rabbitmq_shovel");

        ProviderResult<dev.claudev.provider.Ack> enabled = provider.enablePlugin(instanceId, "rabbitmq_shovel");
        assertThat(enabled).isInstanceOf(ProviderResult.Ok.class);

        ProviderResult<List<String>> afterEnable = provider.listEnabledPlugins(instanceId);
        assertThat(((ProviderResult.Ok<List<String>>) afterEnable).value()).contains("rabbitmq_shovel");

        ProviderResult<dev.claudev.provider.Ack> disabled = provider.disablePlugin(instanceId, "rabbitmq_shovel");
        assertThat(disabled).isInstanceOf(ProviderResult.Ok.class);

        ProviderResult<List<String>> afterDisable = provider.listEnabledPlugins(instanceId);
        assertThat(((ProviderResult.Ok<List<String>>) afterDisable).value()).doesNotContain("rabbitmq_shovel");
    }

    @Test
    void enablingANonexistentPluginIsReportedAsFailureDespiteTheClisZeroExitCode() {
        ProviderResult<dev.claudev.provider.Ack> result = provider.enablePlugin(instanceId, "not_a_real_plugin_" + UUID.randomUUID());
        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void pluginActionsAgainstAnUntrackedInstanceAreRejected() {
        String unknownId = "never-started-" + UUID.randomUUID();
        assertThat(provider.listEnabledPlugins(unknownId)).isInstanceOf(ProviderResult.Err.class);
        assertThat(provider.enablePlugin(unknownId, "rabbitmq_shovel")).isInstanceOf(ProviderResult.Err.class);
        assertThat(provider.disablePlugin(unknownId, "rabbitmq_shovel")).isInstanceOf(ProviderResult.Err.class);
    }
}
