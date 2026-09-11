package dev.claudev.app;

import dev.claudev.ui.ClaudevShell;
import javafx.application.Application;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single-JVM entry point (D6, D15): the Spring context (domain services, operation engine,
 * providers, and — once M0 lands — the mandatory blocking startup reconciliation pass) starts
 * first; only then is the JavaFX {@link Application} launched against the already-running
 * context. There is no second process, so there is no sidecar spawn/health-check/restart policy
 * to reason about.
 *
 * <p>{@code scanBasePackages} is widened to {@code dev.claudev} (beyond the default
 * {@code dev.claudev.app} base package) because {@code @Configuration}/{@code @Component} beans
 * live across module packages (e.g. {@code dev.claudev.persistence.PersistenceConfig}), not just
 * under this class's own package.
 *
 * <p>{@code @EnableScheduling} backs {@link ReconcilerScheduler}'s timer-driven passes; the
 * mandatory blocking startup pass itself doesn't need it — {@link ReconcilerStartupRunner} runs as
 * an {@code ApplicationRunner}, which Spring Boot already executes synchronously before
 * {@code run(args)} below returns.
 */
@SpringBootApplication(scanBasePackages = "dev.claudev")
@EnableScheduling
public class ClaudevApplication {

    private static volatile ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        springContext = new SpringApplicationBuilder(ClaudevApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);

        ClaudevShell.setOnQuitRequested(ClaudevApplication::gracefulShutdown);
        ClaudevShell.setDiagnosticsSource(springContext.getBean(dev.claudev.ui.DiagnosticsSource.class));
        ClaudevShell.setWorkspaceControlPort(springContext.getBean(dev.claudev.ui.WorkspaceControlPort.class));

        // Blocks until ClaudevShell.quit() calls Platform.exit().
        Application.launch(ClaudevShell.class, args);
    }

    private static void gracefulShutdown() {
        // TODO (M3): run the operation engine's graceful Stop All and wait for it to settle
        // before closing the context — the "bounded shutdown" behavior from docs/PROCESS_SAFETY.md.
        // A JVM crash instead of a clean Quit still kills every owned process tree via each
        // component Job Object's KILL_ON_JOB_CLOSE, independent of this method ever running.
        if (springContext != null) {
            springContext.close();
        }
    }
}
