package dev.claudev.app;

import dev.claudev.engine.InMemoryOperationEngine;
import dev.claudev.engine.OperationEngine;
import dev.claudev.persistence.OperationEventRepository;
import dev.claudev.persistence.OperationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free {@link InMemoryOperationEngine} (operation-engine) as a bean, matching {@link ReconcilerConfig}/{@link AdapterConfig}. */
@Configuration
public class OperationEngineConfig {

    @Bean
    public OperationEngine operationEngine(
            OperationRepository operationRepository,
            OperationEventRepository operationEventRepository,
            @Value("${claudev.operation-engine.max-concurrency:4}") int maxConcurrency) {
        return new InMemoryOperationEngine(operationRepository, operationEventRepository, maxConcurrency);
    }
}
