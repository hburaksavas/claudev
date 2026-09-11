package dev.claudev.adapter.fepipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Moves staged output into the live deploy path. "Atomic" here means each individual directory
 * rename is atomic (guaranteed by Windows when source/target share a volume) — not that the whole
 * two-step swap is a single OS transaction. If nothing is currently deployed, this is one rename.
 * If something is, the old deployment is renamed aside first (so it survives if the second rename
 * fails, as a manual recovery point) and only removed after the new one is successfully in place.
 */
final class AtomicDeployExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path stagedDir = Params.requirePath(params, "stagedDir").toAbsolutePath().normalize();
        Path deployDir = Params.requirePath(params, "deployDir").toAbsolutePath().normalize();

        if (!Files.isDirectory(stagedDir)) {
            throw new StepExecutionException("AtomicDeploy stagedDir does not exist: " + stagedDir);
        }

        try {
            if (!Files.exists(deployDir)) {
                Files.move(stagedDir, deployDir);
                return;
            }

            Path backup = deployDir.resolveSibling(deployDir.getFileName() + ".old-" + Instant.now().toEpochMilli());
            Files.move(deployDir, backup);
            try {
                Files.move(stagedDir, deployDir);
            } catch (IOException moveNewIntoPlaceFailed) {
                // Best-effort recovery: put the old deployment back so the deploy path isn't left empty.
                try {
                    Files.move(backup, deployDir);
                } catch (IOException recoveryFailed) {
                    throw new StepExecutionException(
                            "AtomicDeploy failed and recovery of the previous deployment also failed — "
                                    + "the previous deployment is preserved at: " + backup, recoveryFailed);
                }
                throw new StepExecutionException(
                        "Failed to move staged output into " + deployDir + "; previous deployment restored", moveNewIntoPlaceFailed);
            }
            deleteRecursively(backup);
        } catch (IOException e) {
            throw new StepExecutionException("AtomicDeploy failed: " + stagedDir + " -> " + deployDir, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // Best effort — a leftover backup directory is a minor cleanliness issue, not a step failure.
                }
            });
        } catch (IOException ignored) {
            // Same reasoning — do not fail the (already-succeeded) deploy over cleanup of the old copy.
        }
    }
}
