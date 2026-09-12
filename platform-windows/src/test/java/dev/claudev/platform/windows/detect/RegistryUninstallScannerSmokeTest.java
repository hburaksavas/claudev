package dev.claudev.platform.windows.detect;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real registry read against whatever this machine has installed — no assumptions about specific software. */
class RegistryUninstallScannerSmokeTest {

    @Test
    void scansRealRegistryWithoutThrowing() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        List<UninstallEntry> entries = new RegistryUninstallScanner().findByDisplayNameContaining("Windows");

        assertThat(entries).isNotNull();
    }

    @Test
    void returnsEmptyForANeedleNothingMatches() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        List<UninstallEntry> entries = new RegistryUninstallScanner()
                .findByDisplayNameContaining("definitely-not-installed-xyz-123");

        assertThat(entries).isEmpty();
    }
}
