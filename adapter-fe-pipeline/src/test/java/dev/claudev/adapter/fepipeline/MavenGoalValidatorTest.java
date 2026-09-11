package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenGoalValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"clean", "install", "package", "test-compile", "org.apache.maven.plugins:maven-clean-plugin:clean", "-DskipTests"})
    void acceptsRealMavenGoalsAndFlags(String goal) {
        assertThatCode(() -> MavenGoalValidator.validate(goal)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "clean & calc.exe",
            "clean; rm -rf /",
            "clean | more",
            "clean > out.txt",
            "clean\" & calc",
            "clean^& calc",
            "clean %PATH%",
            "clean $(whoami)",
            "clean `whoami`",
            "",
            "   "
    })
    void rejectsAnythingWithShellOrCmdMetacharacters(String goal) {
        assertThatThrownBy(() -> MavenGoalValidator.validate(goal))
                .isInstanceOf(StepExecutionException.class);
    }

    @Test
    void rejectsAGoalContainingASpace() {
        assertThatThrownBy(() -> MavenGoalValidator.validate("clean install"))
                .isInstanceOf(StepExecutionException.class);
    }
}
