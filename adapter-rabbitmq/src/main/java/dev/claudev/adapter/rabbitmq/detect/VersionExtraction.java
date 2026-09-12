package dev.claudev.adapter.rabbitmq.detect;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort version parsing from a directory name (e.g. {@code erl-27.3.4.17}), never spawns a process. */
final class VersionExtraction {

    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    private VersionExtraction() {
    }

    static Optional<String> fromDirectoryName(Path directory) {
        Matcher matcher = VERSION.matcher(directory.getFileName().toString());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    static Optional<Integer> majorVersion(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String[] segments = matcher.group(1).split("\\.");
        try {
            return Optional.of(Integer.parseInt(segments[0]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
