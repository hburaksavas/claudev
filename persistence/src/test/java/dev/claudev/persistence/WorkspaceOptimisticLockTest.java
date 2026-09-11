package dev.claudev.persistence;

import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceOptimisticLockTest {

    @Test
    void secondWriterAtAStaleRevisionIsRejectedAndTheFirstWriteWins(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("optimistic-lock.db"));
        WorkspaceRepository repository = new WorkspaceRepository(new JdbcTemplate(dataSource));

        Workspace original = new Workspace(
                WorkspaceId.newId(), "original-name", Instant.now(),
                new OwnerSid("S-1-5-21-1-2-3-1001"), List.of(), List.of());
        repository.insert(original);

        // Both writers read at revision 0.
        Workspace firstWriterEdit = withName(original, "renamed-by-first-writer");
        Workspace secondWriterEdit = withName(original, "renamed-by-second-writer");

        var afterFirstWrite = repository.update(firstWriterEdit, 0);
        assertThat(afterFirstWrite.revision()).isEqualTo(1);

        // Second writer is still using the now-stale revision 0 — must be rejected, not silently applied.
        assertThatThrownBy(() -> repository.update(secondWriterEdit, 0))
                .isInstanceOf(OptimisticLockException.class);

        // The row reflects only the winning (first) writer's change.
        var current = repository.findById(original.id()).orElseThrow();
        assertThat(current.value().name()).isEqualTo("renamed-by-first-writer");
        assertThat(current.revision()).isEqualTo(1);
    }

    @Test
    void updatingANonexistentWorkspaceIsAnOptimisticLockFailureNotASilentNoop(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("optimistic-lock-missing.db"));
        WorkspaceRepository repository = new WorkspaceRepository(new JdbcTemplate(dataSource));

        Workspace neverInserted = new Workspace(
                WorkspaceId.newId(), "ghost", Instant.now(),
                new OwnerSid("S-1-5-21-1-2-3-1001"), List.of(), List.of());

        assertThatThrownBy(() -> repository.update(neverInserted, 0))
                .isInstanceOf(OptimisticLockException.class);
    }

    private static Workspace withName(Workspace workspace, String newName) {
        return new Workspace(
                workspace.id(), newName, workspace.createdAt(), workspace.ownerSid(),
                workspace.instanceIds(), workspace.pipelineIds());
    }
}
