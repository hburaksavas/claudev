package dev.claudev.adapter.fepipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code mvn.cmd <goals>} in {@code projectDir}. Spawns via {@code cmd.exe /c} (required for a
 * {@code .cmd} script — see {@link MavenGoalValidator}), with the project directory set as the
 * spawn's working directory (never embedded in the command line), and every goal validated by
 * {@link MavenGoalValidator} before it reaches cmd.exe at all.
 */
final class MavenBuildExecutor implements StepExecutor {

    @Override
    public void execute(Map<String, Object> params) throws StepExecutionException {
        Path projectDir = Params.requirePath(params, "projectDir").toAbsolutePath().normalize();
        List<String> goals = Params.requireStringList(params, "goals");
        for (String goal : goals) {
            MavenGoalValidator.validate(goal);
        }

        Path cmdExe = ExecutableLocator.locate("cmd.exe", "CLAUDEV_CMD_EXE");
        Path mvn = ExecutableLocator.locate("mvn.cmd", "CLAUDEV_MVN_CMD");

        List<String> argv = new ArrayList<>();
        argv.add(cmdExe.toString());
        argv.add("/c");
        argv.add(mvn.toString());
        argv.addAll(goals);
        argv.add("-B"); // batch mode: no interactive prompts, no ANSI color-cursor tricks against a redirected stream

        CliProcessRunner.Result result = CliProcessRunner.run(
                cmdExe, argv, projectDir,
                CorporateNetworkEnvironment.baseEnvironment(),
                "claudev-maven-build", Duration.ofMinutes(30));

        if (!result.succeeded()) {
            throw new StepExecutionException(
                    "mvn " + String.join(" ", goals) + " failed (exit " + result.exitCode() + "):\n"
                            + truncate(result.stdout()) + "\n" + truncate(result.stderr()));
        }
    }

    private static String truncate(String text) {
        int max = 4000;
        return text.length() > max ? text.substring(text.length() - max) : text;
    }
}
