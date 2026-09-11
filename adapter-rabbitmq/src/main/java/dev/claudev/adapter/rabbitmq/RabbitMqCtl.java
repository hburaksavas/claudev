package dev.claudev.adapter.rabbitmq;

import dev.claudev.platform.windows.LaunchResult;
import dev.claudev.platform.windows.LaunchSpec;
import dev.claudev.platform.windows.ProcessExitWaiter;
import dev.claudev.platform.windows.WindowsProcessLauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-shot {@code rabbitmqctl.bat} invocations (status/stop/await_startup) — spawned via WP1's
 * {@link WindowsProcessLauncher} and waited out via {@link ProcessExitWaiter} exactly like the
 * CLI-shaped steps in {@code adapter-fe-pipeline}, just not shared code with that module (each
 * adapter owns its own spawn/capture glue — see docs/REPO_LAYOUT.md's dependency direction notes).
 */
final class RabbitMqCtl {

    private RabbitMqCtl() {
    }

    record Result(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }

    static Result run(RabbitMqInstallation installation, String nodename, String cookie, Duration timeout, String... args) {
        Path cmdExe = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        Path ctlBat = installation.rabbitmqSbin().resolve("rabbitmqctl.bat");

        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add(cmdExe.toString());
        argv.add("/c");
        argv.add(ctlBat.toString());
        argv.add("-n");
        argv.add(nodename);
        argv.addAll(List.of(args));

        Map<String, String> env = new java.util.HashMap<>();
        RabbitMqEnvironment.addBaseVars(env);
        env.put("ERLANG_HOME", installation.erlangHome().toString());
        env.put("RABBITMQ_CTL_ERL_ARGS", "-setcookie " + cookie);

        Path stdout;
        Path stderr;
        try {
            stdout = Files.createTempFile("claudev-rabbitmqctl-out-", ".log");
            stderr = Files.createTempFile("claudev-rabbitmqctl-err-", ".log");
        } catch (IOException e) {
            return new Result(-1, "failed to create capture files: " + e.getMessage());
        }

        LaunchSpec spec = new LaunchSpec(
                cmdExe, argv, installation.rabbitmqSbin(), env,
                "claudev-rabbitmqctl-" + nodename, true, Optional.of(stdout), Optional.of(stderr));

        try {
            LaunchResult launch = WindowsProcessLauncher.launch(spec);
            try {
                Optional<Integer> exitCode = ProcessExitWaiter.waitForExit(launch.pid(), launch.creationTime(), timeout);
                if (exitCode.isEmpty()) {
                    launch.job().terminate(1);
                    return new Result(-1, "rabbitmqctl did not exit within " + timeout);
                }
                String combined = readQuietly(stdout) + readQuietly(stderr);
                return new Result(exitCode.get(), combined);
            } finally {
                launch.job().close();
            }
        } catch (RuntimeException e) {
            return new Result(-1, "failed to spawn rabbitmqctl: " + e.getMessage());
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
