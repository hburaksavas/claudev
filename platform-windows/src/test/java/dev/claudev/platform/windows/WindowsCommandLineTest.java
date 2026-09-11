package dev.claudev.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips {@link WindowsCommandLine#build} output through the real Win32
 * {@code CommandLineToArgvW} parser — not a hand-rolled re-implementation of it — which is the
 * concrete verification behind the "no shell-string injection" release blocker in
 * docs/SECURITY.md: whatever we build must parse back into exactly the arguments we started with.
 */
class WindowsCommandLineTest {

    interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer CommandLineToArgvW(WString lpCmdLine, int[] pNumArgs);
    }

    interface Kernel32Local extends StdCallLibrary {
        Kernel32Local INSTANCE = Native.load("kernel32", Kernel32Local.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer LocalFree(Pointer hMem);
    }

    /**
     * argv[0] is fixed to a clean, quote-free token in every case: {@code CommandLineToArgvW}
     * parses argv[0] under a simpler rule (backslashes are never special there, only quotes toggle
     * quoting), which real executable paths never actually exercise in a way that would diverge
     * from the general-argument rule this class implements uniformly. Cases below exercise the
     * adversarial content in argv[1+], which is where the general rule applies and where injection
     * risk actually lives.
     */
    static Stream<List<String>> adversarialArgvCases() {
        return Stream.of(
                List.of("simple"),
                List.of("with space"),
                List.of("with\"quote"),
                List.of("trailing\\"),
                List.of("trailing\\\\"),
                List.of(""),
                List.of("many\\\\\\backslashes"),
                List.of("quote\\\"combo"),
                List.of("a", "b c", "d\"e", "f\\", "", "g\\\\h", "\"already quoted\""),
                List.of("C:\\Program Files\\App\\"),
                List.of("mixed \\ and \" and end\\"),
                List.of("&|<>^"), // not shell metacharacters here — CreateProcessW never invokes a shell
                List.of("\"'`$();"));
    }

    @ParameterizedTest
    @MethodSource("adversarialArgvCases")
    void roundTripsThroughRealWin32Parser(List<String> adversarialTail) {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        List<String> argv = new ArrayList<>();
        argv.add("program.exe");
        argv.addAll(adversarialTail);

        String commandLine = WindowsCommandLine.build(argv);

        int[] argc = new int[1];
        Pointer argvPointer = Shell32.INSTANCE.CommandLineToArgvW(new WString(commandLine), argc);
        assertThat(argvPointer).as("CommandLineToArgvW failed to parse: [%s]", commandLine).isNotNull();

        try {
            List<String> parsed = new ArrayList<>();
            for (int i = 0; i < argc[0]; i++) {
                Pointer entry = argvPointer.getPointer((long) i * Native.POINTER_SIZE);
                parsed.add(entry.getWideString(0));
            }
            assertThat(parsed).as("command line was: [%s]", commandLine).containsExactlyElementsOf(argv);
        } finally {
            Kernel32Local.INSTANCE.LocalFree(argvPointer);
        }
    }

    @Test
    void simpleArgumentsAreNotQuotedUnnecessarily() {
        assertThat(WindowsCommandLine.quote("simple")).isEqualTo("simple");
        assertThat(WindowsCommandLine.quote("no-spaces-or-quotes")).isEqualTo("no-spaces-or-quotes");
    }

    @Test
    void emptyArgumentBecomesAnEmptyQuotedPair() {
        assertThat(WindowsCommandLine.quote("")).isEqualTo("\"\"");
    }
}
