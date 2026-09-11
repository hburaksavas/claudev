package dev.claudev.adapter.fepipeline;

import java.util.regex.Pattern;

/**
 * {@code mvn.cmd} is a batch script, which {@code CreateProcessW} cannot execute directly — it
 * must run through {@code cmd.exe /c}. Unlike a direct {@code CreateProcessW} spawn (where WP1's
 * {@code WindowsCommandLine} quoting is adversarially verified safe against
 * {@code CommandLineToArgvW}), {@code cmd.exe}'s own {@code /c} parsing has separate, genuinely
 * hard-to-fully-neutralize metacharacter/variable-expansion quirks (notably {@code %}, which
 * cannot be reliably escaped in all contexts). Rather than build and adversarially test a second,
 * cmd.exe-specific quoting layer to match WP1's rigor for this one step type, this class closes
 * the actual risk at the input boundary: every Maven goal/phase must match a strict allow-list
 * pattern before it is allowed anywhere near the command line. Standard Maven goals/phases never
 * need anything outside this pattern.
 */
final class MavenGoalValidator {

    private static final Pattern SAFE_GOAL = Pattern.compile("^[A-Za-z0-9_.:-]+$");

    private MavenGoalValidator() {
    }

    static void validate(String goal) throws StepExecutionException {
        if (!SAFE_GOAL.matcher(goal).matches()) {
            throw new StepExecutionException(
                    "Rejected Maven goal/phase (must match " + SAFE_GOAL.pattern() + "): " + goal);
        }
    }
}
