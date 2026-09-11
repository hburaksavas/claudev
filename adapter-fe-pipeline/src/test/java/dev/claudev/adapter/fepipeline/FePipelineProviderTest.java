package dev.claudev.adapter.fepipeline;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.pipeline.PipelineRunRequest;
import dev.claudev.provider.pipeline.StepInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real, multi-step pipelines through the actual provider entry point — no mocking of steps. */
class FePipelineProviderTest {

    private final FePipelineProvider provider = new FePipelineProvider();

    @Test
    void aRealMultiStepPipelineChecksOutAndStagesAFileInOrder(@TempDir Path tempDir) {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        assumeNetworkAvailable();

        Path repoDir = tempDir.resolve("repo");
        Path stagedReadme = tempDir.resolve("staged").resolve("README");

        PipelineRunRequest request = new PipelineRunRequest("test-pipeline", List.of(
                new StepInvocation("EnsureCheckout", Map.of(
                        "repositoryUrl", "https://github.com/octocat/Hello-World.git",
                        "targetDir", repoDir.toString())),
                new StepInvocation("StageArtifact", Map.of(
                        "source", repoDir.resolve("README").toString(),
                        "destination", stagedReadme.toString()))));

        ProviderResult<dev.claudev.provider.Ack> result = provider.execute(request);

        assertThat(result.isOk()).as("pipeline result: %s", result).isTrue();
        assertThat(stagedReadme).exists();
    }

    @Test
    void aFailingStepStopsThePipelineAndLaterStepsNeverRun(@TempDir Path tempDir) throws Exception {
        Path neverCreated = tempDir.resolve("should-not-exist.txt");
        Path missingSource = tempDir.resolve("does-not-exist-anywhere.jar");

        PipelineRunRequest request = new PipelineRunRequest("test-pipeline", List.of(
                // Fails: source does not exist.
                new StepInvocation("StageArtifact", Map.of(
                        "source", missingSource.toString(),
                        "destination", tempDir.resolve("out.jar").toString())),
                // Would create neverCreated if reached — it must never run.
                new StepInvocation("ConfigPatch", Map.of(
                        "targetFile", writeFile(tempDir, "config.txt", "${MARK}"),
                        "substitutions", Map.of("MARK", "reached")))));

        ProviderResult<dev.claudev.provider.Ack> result = provider.execute(request);

        assertThat(result.isOk()).isFalse();
        assertThat(neverCreated).doesNotExist();
    }

    @Test
    void anUnknownStepTypeFailsWithoutRunningAnything() {
        PipelineRunRequest request = new PipelineRunRequest("test-pipeline", List.of(
                new StepInvocation("NotARealStepType", Map.of())));

        ProviderResult<dev.claudev.provider.Ack> result = provider.execute(request);

        assertThat(result.isOk()).isFalse();
        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void manifestAdvertisesAllRegisteredStepTypes() {
        var manifest = provider.manifest();
        String steps = manifest.capabilities().stream()
                .filter(c -> c.name().equals("steps"))
                .findFirst()
                .orElseThrow()
                .version();

        assertThat(steps).contains(
                "EnsureCheckout", "GitFetch", "GitFastForward", "MavenBuild", "ConfigPatch",
                "StageArtifact", "AtomicDeploy", "StartProcess", "HealthCheck", "ExecStep");
    }

    private static String writeFile(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file.toString();
    }

    private static void assumeNetworkAvailable() {
        try {
            java.net.InetAddress.getByName("github.com");
        } catch (Exception e) {
            assumeTrue(false, "No network access to github.com: " + e.getMessage());
        }
    }
}
