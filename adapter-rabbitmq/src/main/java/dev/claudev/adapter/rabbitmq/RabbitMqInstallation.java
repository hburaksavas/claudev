package dev.claudev.adapter.rabbitmq;

import java.nio.file.Path;

/** Resolved, on-disk locations of a provisioned Erlang/OTP + RabbitMQ pair — not raw download state. */
record RabbitMqInstallation(Path erlangHome, Path rabbitmqSbin) {
}
