package dev.claudev.app;

import dev.claudev.adapter.dummy.DummyRuntimeProvider;
import dev.claudev.adapter.fepipeline.FePipelineProvider;
import dev.claudev.adapter.redis.RedisConnectionProvider;
import dev.claudev.provider.connection.ConnectionProvider;
import dev.claudev.provider.pipeline.ProjectPipelineProvider;
import dev.claudev.provider.runtime.RuntimeProvider;
import dev.claudev.provider.secret.SecretStore;
import dev.claudev.secret.dpapi.DpapiSecretStore;
import dev.claudev.secret.legacy.LegacyEncodedSecretStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The compiled-in adapter registry (ADR-001): adapters are plain framework-free classes, and this
 * is the single place that knows how to construct them as beans. Keeping the Spring annotations
 * here rather than on the adapter classes is what lets the adapter modules stay dependency-light.
 */
@Configuration
public class AdapterConfig {

    /**
     * The default/primary-path {@link RuntimeProvider} for the workspace UI's "New dummy instance"
     * flow (WP5). {@code adapter-rabbitmq} is real too (WP6) but is provisioned lazily and dispatched
     * separately — see {@link RabbitMqProviderHolder} and {@link SpringWorkspaceControlPort}.
     */
    @Bean
    public RuntimeProvider dummyRuntimeProvider() {
        return new DummyRuntimeProvider();
    }

    @Bean
    public ConnectionProvider redisConnectionProvider() {
        return new RedisConnectionProvider();
    }

    @Bean
    public ProjectPipelineProvider fePipelineProvider() {
        return new FePipelineProvider();
    }

    /** {@code @Primary} encodes D13: DPAPI is the default store, the legacy adapter never is. */
    @Bean
    @Primary
    public SecretStore dpapiSecretStore() {
        return new DpapiSecretStore();
    }

    /** Off-by-default legacy import path (D13) — registered so it is visible, not so it is used. */
    @Bean
    public LegacyEncodedSecretStore legacyEncodedSecretStore() {
        return new LegacyEncodedSecretStore();
    }
}
