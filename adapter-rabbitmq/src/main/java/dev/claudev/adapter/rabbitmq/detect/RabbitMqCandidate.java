package dev.claudev.adapter.rabbitmq.detect;

import java.nio.file.Path;

record RabbitMqCandidate(Path rabbitmqSbin, String version, DetectionSource source) {
}
