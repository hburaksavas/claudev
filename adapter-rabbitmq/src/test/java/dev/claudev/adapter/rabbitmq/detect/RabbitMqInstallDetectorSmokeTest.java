package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.domain.detect.DetectedCandidate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real end-to-end scan against the actual dev machine — no assumptions about what's installed. */
class RabbitMqInstallDetectorSmokeTest {

    private static final Logger LOG = Logger.getLogger(RabbitMqInstallDetectorSmokeTest.class.getName());

    @Test
    void scansTheRealMachineWithoutThrowing() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        List<DetectedCandidate> candidates = new RabbitMqInstallDetector().detectValidCandidates();

        assertThat(candidates).isNotNull();
        candidates.forEach(c -> LOG.info("Detected: " + c.label()));
    }
}
