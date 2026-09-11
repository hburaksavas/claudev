package dev.claudev.app;

import dev.claudev.engine.Reconciler;
import dev.claudev.persistence.InstanceRepository;
import dev.claudev.persistence.LaunchRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the framework-free {@link Reconciler} (operation-engine) as a bean, matching the pattern already used for the adapters in {@link AdapterConfig}. */
@Configuration
public class ReconcilerConfig {

    @Bean
    public Reconciler reconciler(
            InstanceRepository instanceRepository,
            LaunchRecordRepository launchRecordRepository,
            @Value("${claudev.managed-binaries-dir:${user.home}/.claudev/runtimes}") String managedBinariesDir) {
        return new Reconciler(instanceRepository, launchRecordRepository, Path.of(managedBinariesDir));
    }
}
