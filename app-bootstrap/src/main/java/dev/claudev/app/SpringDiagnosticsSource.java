package dev.claudev.app;

import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import dev.claudev.engine.OperationEngine;
import dev.claudev.engine.OperationNode;
import dev.claudev.engine.OperationPlan;
import dev.claudev.engine.Reconciler;
import dev.claudev.engine.ReconciliationReport;
import dev.claudev.platform.windows.WindowsJobObject;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.connection.ConnectionProvider;
import dev.claudev.provider.pipeline.ProjectPipelineProvider;
import dev.claudev.provider.runtime.RuntimeProvider;
import dev.claudev.provider.secret.SecretHandle;
import dev.claudev.provider.secret.SecretStore;
import dev.claudev.ui.DiagnosticsSnapshot;
import dev.claudev.ui.DiagnosticsSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a {@link DiagnosticsSnapshot} by actually measuring things: reading SQLite pragmas over a
 * live connection, performing a real DPAPI round trip, and creating (then closing) a real Windows
 * Job Object. Nothing here is a hardcoded "OK".
 */
@Component
public class SpringDiagnosticsSource implements DiagnosticsSource {

    private final DataSource dataSource;
    private final SecretStore secretStore;
    private final RuntimeProvider runtimeProvider;
    private final ConnectionProvider connectionProvider;
    private final ProjectPipelineProvider pipelineProvider;
    private final Reconciler reconciler;
    private final OperationEngine operationEngine;
    private final String dbPath;

    public SpringDiagnosticsSource(
            DataSource dataSource,
            SecretStore secretStore,
            RuntimeProvider runtimeProvider,
            ConnectionProvider connectionProvider,
            ProjectPipelineProvider pipelineProvider,
            Reconciler reconciler,
            OperationEngine operationEngine,
            @Value("${claudev.db-path:${user.home}/.claudev/claudev.db}") String dbPath) {
        this.dataSource = dataSource;
        this.secretStore = secretStore;
        this.runtimeProvider = runtimeProvider;
        this.connectionProvider = connectionProvider;
        this.pipelineProvider = pipelineProvider;
        this.reconciler = reconciler;
        this.operationEngine = operationEngine;
        this.dbPath = dbPath;
    }

    @Override
    public DiagnosticsSnapshot snapshot() {
        String journalMode = readPragma("journal_mode");
        String busyTimeout = readPragma("busy_timeout");

        return new DiagnosticsSnapshot(
                System.getProperty("java.version", "?"),
                System.getProperty("javafx.runtime.version", "?"),
                System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""),
                System.getProperty("user.name", "?"),
                dbPath,
                journalMode,
                busyTimeout,
                checkSecretStore(),
                checkJobObject(),
                checkReconciler(),
                checkOperationEngine(),
                adapterRows());
    }

    private String readPragma(String pragma) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA " + pragma)) {
            return rs.next() ? rs.getString(1) : "(no result)";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private DiagnosticsSnapshot.CheckResult checkSecretStore() {
        String probe = "diagnostics-" + UUID.randomUUID();
        try {
            ProviderResult<SecretHandle> stored = secretStore.store(probe);
            if (!(stored instanceof ProviderResult.Ok<SecretHandle> ok)) {
                return DiagnosticsSnapshot.CheckResult.fail("store failed: " + stored);
            }

            ProviderResult<String> resolved = secretStore.resolve(ok.value());
            if (resolved instanceof ProviderResult.Ok<String> value && probe.equals(value.value())) {
                return DiagnosticsSnapshot.CheckResult.pass("encrypt/decrypt round trip verified");
            }
            return DiagnosticsSnapshot.CheckResult.fail("resolve mismatch: " + resolved);
        } catch (RuntimeException e) {
            return DiagnosticsSnapshot.CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private DiagnosticsSnapshot.CheckResult checkJobObject() {
        try (WindowsJobObject job = WindowsJobObject.createWithKillOnClose("claudev-diagnostics-" + UUID.randomUUID())) {
            return DiagnosticsSnapshot.CheckResult.pass("KILL_ON_JOB_CLOSE job created and closed");
        } catch (RuntimeException e) {
            return DiagnosticsSnapshot.CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private DiagnosticsSnapshot.CheckResult checkReconciler() {
        try {
            ReconciliationReport report = reconciler.reconcile();
            return DiagnosticsSnapshot.CheckResult.pass(String.format(
                    "%d running, %d stopped, %d orphaned, %d untracked (live pass against the real DB)",
                    report.running().size(), report.stopped().size(), report.orphaned().size(),
                    report.untrackedPids().size()));
        } catch (RuntimeException e) {
            return DiagnosticsSnapshot.CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Submits a trivial real operation through the real engine and waits for it to persist a terminal status. */
    private DiagnosticsSnapshot.CheckResult checkOperationEngine() {
        try {
            OperationPlan plan = new OperationPlan(
                    "diagnostics-self-test", List.of(),
                    List.of(new OperationNode("noop", List.of(), ctx -> { })));
            OperationId operationId = operationEngine.submit(plan);

            long deadline = System.currentTimeMillis() + 5000;
            Optional<Operation> result = Optional.empty();
            while (System.currentTimeMillis() < deadline) {
                result = operationEngine.find(operationId);
                if (result.isPresent() && isTerminal(result.get().status())) {
                    break;
                }
                Thread.sleep(20);
            }

            if (result.isEmpty()) {
                return DiagnosticsSnapshot.CheckResult.fail("operation not found after submit");
            }
            if (result.get().status() != OperationStatus.SUCCEEDED) {
                return DiagnosticsSnapshot.CheckResult.fail("unexpected status: " + result.get().status());
            }
            return DiagnosticsSnapshot.CheckResult.pass(
                    "submitted+completed operation " + operationId.value() + " (real DAG run, persisted)");
        } catch (Exception e) {
            return DiagnosticsSnapshot.CheckResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean isTerminal(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED
                || status == OperationStatus.PARTIALLY_FAILED || status == OperationStatus.CANCELLED;
    }

    private List<DiagnosticsSnapshot.AdapterRow> adapterRows() {
        List<DiagnosticsSnapshot.AdapterRow> rows = new ArrayList<>();
        rows.add(row(runtimeProvider.manifest()));
        rows.add(row(connectionProvider.manifest()));
        rows.add(row(pipelineProvider.manifest()));
        rows.add(row(secretStore.manifest()));
        return rows;
    }

    private DiagnosticsSnapshot.AdapterRow row(AdapterManifest manifest) {
        String capabilities = manifest.capabilities().stream()
                .map(capability -> capability.name() + "=" + capability.version())
                .collect(Collectors.joining(", "));

        String status = manifest.capabilities().stream()
                .filter(capability -> capability.name().equals("status"))
                .map(Capability::version)
                .findFirst()
                .orElse("ready");

        return new DiagnosticsSnapshot.AdapterRow(manifest.id(), manifest.version(), capabilities, status);
    }
}
