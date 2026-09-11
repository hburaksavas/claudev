package dev.claudev.adapter.fepipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Copies a built artifact to a staging path, creating parent directories as needed. */
final class StageArtifactExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path source = Params.requirePath(params, "source").toAbsolutePath().normalize();
        Path destination = Params.requirePath(params, "destination").toAbsolutePath().normalize();

        if (!Files.exists(source)) {
            throw new StepExecutionException("StageArtifact source does not exist: " + source);
        }

        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StepExecutionException("Failed to stage artifact from " + source + " to " + destination, e);
        }
    }
}
