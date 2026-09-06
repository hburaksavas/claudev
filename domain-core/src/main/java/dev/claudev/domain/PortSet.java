package dev.claudev.domain;

import java.util.Set;

/**
 * {@code requested} is what the instance was configured to use; {@code bound} is what it actually
 * bound to, confirmed via a real bind attempt or the adapter's own health probe — never inferred
 * from a listen scan alone (Windows excluded-port ranges can make a port unbindable with nothing
 * shown listening on it).
 */
public record PortSet(Set<Integer> requested, Set<Integer> bound) {
    public PortSet {
        requested = Set.copyOf(requested);
        bound = Set.copyOf(bound);
    }

    public static PortSet none() {
        return new PortSet(Set.of(), Set.of());
    }
}
