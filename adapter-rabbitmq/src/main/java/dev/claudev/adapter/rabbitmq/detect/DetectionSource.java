package dev.claudev.adapter.rabbitmq.detect;

/** Where a detected candidate install was found — only ever surfaced to the UI as a formatted string, never itself. */
enum DetectionSource {
    PATH("PATH"),
    REGISTRY("Registry"),
    ENV_VAR("Environment variable"),
    WELL_KNOWN_DIRECTORY("Well-known directory"),
    GLOB("Program Files");

    private final String label;

    DetectionSource(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
