package dev.claudev.engine;

import dev.claudev.domain.OperationEvent;

/**
 * Where {@link DagScheduler} reports events. Decouples the scheduling algorithm from however
 * events actually get persisted/published — {@link InMemoryOperationEngine} is the real
 * implementation (persist, then fan out to subscribers); tests can use a simple in-memory list.
 */
@FunctionalInterface
public interface EventSink {

    void emit(OperationEvent event);
}
