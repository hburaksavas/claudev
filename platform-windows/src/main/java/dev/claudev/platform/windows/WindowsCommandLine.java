package dev.claudev.platform.windows;

import java.util.List;

/**
 * Builds the single command-line string {@code CreateProcessW} expects from a list of arguments,
 * using the exact quoting algorithm that round-trips through {@code CommandLineToArgvW} (the
 * parser every well-behaved Windows executable, including the JVM and {@code cmd.exe}'s own
 * argument handling, is built on). This is the concrete mechanism behind the "no shell-string
 * injection" release blocker in docs/SECURITY.md: one list element can never be split into two
 * arguments, or have its content reinterpreted as a new argument, regardless of what characters it
 * contains.
 *
 * <p>Deliberately independent of any shell. {@code &}, {@code |}, {@code <}, {@code >}, {@code ^}
 * are not special here — those are {@code cmd.exe} metacharacters, and {@code CreateProcessW}
 * never invokes a shell, so this class does not need to (and must not) defend against them. What it
 * must get exactly right is backslash/quote escaping, since that is the only way one argument's
 * content could otherwise smuggle in the start of another.
 */
public final class WindowsCommandLine {

    private WindowsCommandLine() {
    }

    /** Joins the quoted form of every argument with single spaces, per {@code CreateProcessW}'s expectation. */
    public static String build(List<String> argv) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must contain at least the program name");
        }

        StringBuilder commandLine = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                commandLine.append(' ');
            }
            commandLine.append(quote(argv.get(i)));
        }
        return commandLine.toString();
    }

    /**
     * The standard {@code ArgvQuote} algorithm (as documented by Microsoft for exactly this
     * purpose): an argument needs quoting/escaping if it is empty or contains a space, tab, or
     * double quote. Backslashes are only special immediately before a quote — {@code N}
     * backslashes followed by a quote become {@code 2N} backslashes plus an escaped quote;
     * backslashes not followed by a quote are copied literally. This is what correctly handles the
     * classic failure case of a trailing backslash before the closing quote (e.g. a Windows
     * directory path like {@code C:\Program Files\}).
     */
    static String quote(String argument) {
        if (!argument.isEmpty() && argument.chars().noneMatch(WindowsCommandLine::needsEscaping)) {
            return argument;
        }

        StringBuilder result = new StringBuilder();
        result.append('"');

        int backslashRun = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (c == '\\') {
                backslashRun++;
                continue;
            }
            if (c == '"') {
                appendBackslashes(result, backslashRun * 2 + 1);
                result.append('"');
            } else {
                appendBackslashes(result, backslashRun);
                result.append(c);
            }
            backslashRun = 0;
        }
        // Trailing backslashes must be doubled since they are immediately followed by our closing quote.
        appendBackslashes(result, backslashRun * 2);

        result.append('"');
        return result.toString();
    }

    private static boolean needsEscaping(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '"';
    }

    private static void appendBackslashes(StringBuilder result, int count) {
        for (int i = 0; i < count; i++) {
            result.append('\\');
        }
    }
}
