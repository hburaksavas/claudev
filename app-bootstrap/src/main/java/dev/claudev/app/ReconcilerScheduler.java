package dev.claudev.app;

import dev.claudev.engine.Reconciler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Timer-driven reconciliation passes after the mandatory startup one (docs/PROCESS_SAFETY.md). */
@Component
public class ReconcilerScheduler {

    private final Reconciler reconciler;

    public ReconcilerScheduler(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reconcile() {
        reconciler.reconcile();
    }
}
