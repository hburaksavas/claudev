package dev.claudev.domain;

public enum RuntimeKind {
    RABBIT_MQ,
    REDIS,
    /** A real, trivial, spawnable process used to exercise the full lifecycle UI/reconciler path before a real adapter (WP6/7) exists. */
    DUMMY
}
