package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real network calls against a small, stable public GitHub repo — this is what WP8's own
 * acceptance criterion ("a real pipeline run against a test repo") means in practice, and
 * specifically re-runs argv-safety concerns (a path containing a space) through the actual Git
 * step path, not just WP1's launcher in isolation, per docs/MILESTONES.md's WP8 acceptance line.
 * Skips cleanly (does not fail the build) if there is no network access.
 */
class GitStepsIntegrationTest {

    private static final String TEST_REPO_URL = "https://github.com/octocat/Hello-World.git";

    private final EnsureCheckoutExecutor ensureCheckout = new EnsureCheckoutExecutor();
    private final GitFetchExecutor gitFetch = new GitFetchExecutor();
    private final GitFastForwardExecutor gitFastForward = new GitFastForwardExecutor();

    @Test
    void clonesIntoADirectoryWhosePathContainsASpaceAndIsANoOpOnASecondCall(@TempDir Path tempDir) throws Exception {
        assumeNetworkAvailable();

        // The space in the directory name exercises the same argv-quoting path a real Windows
        // username/project name could produce — WP1's launcher already proved this safe in
        // isolation; this proves it safe through the actual Git step, per WP8's acceptance line.
        Path targetDir = tempDir.resolve("cloned repo with spaces");

        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));

        assertThat(targetDir.resolve(".git")).isDirectory();
        assertThat(targetDir.resolve("README")).exists();

        // Second call is a no-op — delete a marker file only EnsureCheckout's own clone would recreate.
        Files.writeString(targetDir.resolve("untouched-marker.txt"), "still here");
        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));
        assertThat(targetDir.resolve("untouched-marker.txt")).exists();
    }

    @Test
    void fetchSucceedsAgainstTheRealRemote(@TempDir Path tempDir) throws Exception {
        assumeNetworkAvailable();

        Path targetDir = tempDir.resolve("repo");
        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));

        gitFetch.execute(Map.of("repoDir", targetDir.toString(), "remote", "origin"));
        // No exception == success; fetch's own output isn't asserted on since remote content can change.
    }

    @Test
    void fastForwardBlocksOnADirtyWorktree(@TempDir Path tempDir) throws Exception {
        assumeNetworkAvailable();

        Path targetDir = tempDir.resolve("repo");
        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));
        Files.writeString(targetDir.resolve("untracked-dirt.txt"), "uncommitted");

        assertThatThrownBy(() -> gitFastForward.execute(Map.of(
                "repoDir", targetDir.toString(), "remote", "origin", "branch", currentBranch(targetDir))))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("dirty");
    }

    @Test
    void fastForwardBlocksOnADivergedLocalCommitAndNeverAutoMerges(@TempDir Path tempDir) throws Exception {
        assumeNetworkAvailable();

        Path targetDir = tempDir.resolve("repo");
        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));
        String branch = currentBranch(targetDir);

        // A commit stacked on top of the current (latest) HEAD would just make local *ahead* of
        // origin/branch, which --ff-only treats as trivially satisfied (no-op), not a failure.
        // True divergence needs local's new commit to be a *sibling* of later remote commits, not
        // a descendant — so reset to an earlier ancestor first, then branch off from there.
        String rootSha = runGit(targetDir, "rev-list", "--max-parents=0", "HEAD").trim();
        runGit(targetDir, "reset", "--hard", rootSha);
        runGit(targetDir, "commit", "--allow-empty", "-m", "local divergent commit");

        assertThatThrownBy(() -> gitFastForward.execute(Map.of(
                "repoDir", targetDir.toString(), "remote", "origin", "branch", branch)))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("not fast-forwardable");
    }

    @Test
    void fastForwardSucceedsWhenGenuinelyBehindTheRemote(@TempDir Path tempDir) throws Exception {
        assumeNetworkAvailable();

        Path targetDir = tempDir.resolve("repo");
        ensureCheckout.execute(Map.of("repositoryUrl", TEST_REPO_URL, "targetDir", targetDir.toString()));
        String branch = currentBranch(targetDir);
        String latestSha = runGit(targetDir, "rev-parse", "HEAD").trim();

        // Move back to the very first commit in the repo's history, so the remote branch is a
        // strict descendant — a genuine, real fast-forward case, not a contrived one.
        String rootSha = runGit(targetDir, "rev-list", "--max-parents=0", "HEAD").trim();
        runGit(targetDir, "reset", "--hard", rootSha);
        assertThat(runGit(targetDir, "rev-parse", "HEAD").trim()).isEqualTo(rootSha);

        gitFetch.execute(Map.of("repoDir", targetDir.toString(), "remote", "origin"));
        gitFastForward.execute(Map.of("repoDir", targetDir.toString(), "remote", "origin", "branch", branch));

        assertThat(runGit(targetDir, "rev-parse", "HEAD").trim()).isEqualTo(latestSha);
    }

    private static String currentBranch(Path repoDir) throws Exception {
        return runGit(repoDir, "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /** Test-only raw git invocation for setup/assertions the step catalog itself doesn't cover (reset, rev-parse, ad-hoc commits). */
    private static String runGit(Path repoDir, String... args) throws Exception {
        java.nio.file.Path git = ExecutableLocator.locate("git.exe", "CLAUDEV_GIT_EXE");
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(git.toString());
        argv.addAll(List.of(args));
        CliProcessRunner.Result result = CliProcessRunner.run(
                git, argv, repoDir, CorporateNetworkEnvironment.baseEnvironment(),
                "claudev-test-git-" + UUID.randomUUID(), Duration.ofSeconds(30));
        if (!result.succeeded()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + result.stderr());
        }
        return result.stdout();
    }

    private static void assumeNetworkAvailable() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        try {
            java.net.InetAddress.getByName("github.com");
        } catch (Exception e) {
            assumeTrue(false, "No network access to github.com: " + e.getMessage());
        }
    }
}
