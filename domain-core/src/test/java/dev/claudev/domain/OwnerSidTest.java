package dev.claudev.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerSidTest {

    @Test
    void rejectsBlankSid() {
        assertThatThrownBy(() -> new OwnerSid(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsWellFormedSid() {
        var sid = new OwnerSid("S-1-5-21-1111111111-2222222222-3333333333-1001");
        assertThat(sid.value()).startsWith("S-1-5-21");
    }
}
