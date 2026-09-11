package dev.claudev.app;

import dev.claudev.engine.Reconciler;
import dev.claudev.engine.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The mandatory blocking startup reconciliation pass (docs/ARCHITECTURE.md, docs/PROCESS_SAFETY.md):
 * Spring Boot runs every {@link ApplicationRunner} synchronously before
 * {@code SpringApplicationBuilder.run(args)} returns in {@link ClaudevApplication#main}, which is
 * what makes this genuinely block the JavaFX launch rather than merely running "early" on a
 * background thread.
 */
@Component
public class ReconcilerStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerStartupRunner.class);

    private final Reconciler reconciler;

    public ReconcilerStartupRunner(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public void run(ApplicationArguments args) {
        ReconciliationReport report = reconciler.reconcile();
        log.info(
                "Startup reconciliation: {} running, {} stopped, {} orphaned, {} untracked",
                report.running().size(), report.stopped().size(),
                report.orphaned().size(), report.untrackedPids().size());
    }
}
