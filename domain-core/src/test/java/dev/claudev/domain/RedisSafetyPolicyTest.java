package dev.claudev.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSafetyPolicyTest {

    @Test
    void restrictiveDefaultIsReadOnlyAndDeniesFlush() {
        var policy = RedisSafetyPolicy.restrictiveDefault();

        assertThat(policy.readOnlyDefault()).isTrue();
        assertThat(policy.commandDenyList()).contains("FLUSHDB", "FLUSHALL");
        assertThat(policy.typedConfirmationRequiredFor()).contains("FLUSHDB", "FLUSHALL");
        assertThat(policy.auditRequired()).isTrue();
    }
}
