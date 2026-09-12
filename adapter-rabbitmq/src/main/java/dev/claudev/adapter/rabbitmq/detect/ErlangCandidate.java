package dev.claudev.adapter.rabbitmq.detect;

import java.nio.file.Path;

record ErlangCandidate(Path erlangHome, String version, DetectionSource source) {
}
