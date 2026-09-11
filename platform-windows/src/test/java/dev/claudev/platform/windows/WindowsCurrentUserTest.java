package dev.claudev.platform.windows;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsCurrentUserTest {

    @Test
    void returnsARealWindowsSidString() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        String sid = WindowsCurrentUser.sid();

        assertThat(sid).startsWith("S-1-");
    }
}
