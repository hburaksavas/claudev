package dev.claudev.adapter.rabbitmq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code rabbitmq-plugins.bat} invocations (WP10c) — same spawn shape as {@link RabbitMqCtl}, just
 * a different script, so kept as its own small class rather than generalizing {@link RabbitMqCtl}
 * for two call sites (see docs/REPO_LAYOUT.md's "each adapter owns its own spawn/capture glue").
 *
 * <p><b>A real quirk found while exploring the real CLI, not assumed</b>: {@code enable} on a
 * nonexistent plugin name exits {@code 0} (prints a WARNING, not an error) — the exit code alone
 * cannot tell you whether a plugin was actually enabled. {@link #enable}/{@link #disable} always
 * re-check {@link #listEnabled} afterward and report success based on the plugin's *actual*
 * presence/absence in that list, never the exit code alone.
 */
final class RabbitMqPlugins {

    private RabbitMqPlugins() {
    }

    static List<String> listEnabled(RabbitMqInstallation installation, String nodename, String cookie) {
        RabbitMqCtl.Result result = runPlugins(installation, nodename, cookie, Duration.ofSeconds(20), "list", "-e", "-m");
        return parsePluginNames(result.output());
    }

    static boolean enable(RabbitMqInstallation installation, String nodename, String cookie, String pluginName) {
        runPlugins(installation, nodename, cookie, Duration.ofSeconds(30), "enable", pluginName);
        return listEnabled(installation, nodename, cookie).contains(pluginName);
    }

    static boolean disable(RabbitMqInstallation installation, String nodename, String cookie, String pluginName) {
        runPlugins(installation, nodename, cookie, Duration.ofSeconds(30), "disable", pluginName);
        return !listEnabled(installation, nodename, cookie).contains(pluginName);
    }

    /** {@code -m} output is one plugin name per line, plus a leading "Listing plugins with pattern ..." banner line to skip. */
    private static List<String> parsePluginNames(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("Listing plugins")) {
                continue;
            }
            names.add(trimmed);
        }
        return names;
    }

    private static RabbitMqCtl.Result runPlugins(
            RabbitMqInstallation installation, String nodename, String cookie, Duration timeout, String... args) {
        return RabbitMqCtl.runScript(installation, "rabbitmq-plugins.bat", nodename, cookie, timeout, args);
    }
}
