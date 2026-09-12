package dev.claudev.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

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

    @Test
    void restrictiveDefaultDeniesAnUnlockedConnectionsSet() {
        var policy = RedisSafetyPolicy.restrictiveDefault();

        assertThat(policy.authorize("SET", false)).isInstanceOf(MutationAuthorization.Denied.class);
    }

    @Test
    void restrictiveDefaultAllowsAnUnlockedConnectionsSet() {
        var policy = RedisSafetyPolicy.restrictiveDefault();

        assertThat(policy.authorize("SET", true)).isInstanceOf(MutationAuthorization.Allowed.class);
    }

    @Test
    void aDenyListedOperationIsRefusedEvenWhenUnlocked() {
        var policy = RedisSafetyPolicy.restrictiveDefault();

        assertThat(policy.authorize("FLUSHALL", true)).isInstanceOf(MutationAuthorization.Denied.class);
    }

    @Test
    void aNonEmptyAllowListRefusesAnythingNotOnIt() {
        var policy = new RedisSafetyPolicy(false, Set.of("SET"), Set.of(), Set.of(), null, true);

        assertThat(policy.authorize("SET", true)).isInstanceOf(MutationAuthorization.Allowed.class);
        assertThat(policy.authorize("DEL", true)).isInstanceOf(MutationAuthorization.Denied.class);
    }

    @Test
    void notReadOnlyAllowsWritesWithoutUnlocking() {
        var policy = new RedisSafetyPolicy(false, Set.of(), Set.of(), Set.of(), null, true);

        assertThat(policy.authorize("SET", false)).isInstanceOf(MutationAuthorization.Allowed.class);
    }
}
