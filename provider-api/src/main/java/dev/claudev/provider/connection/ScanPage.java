package dev.claudev.provider.connection;

import java.util.List;

/**
 * {@code complete} is {@code false} whenever the caller should keep paging with {@code cursor} —
 * SCAN is not a snapshot, so the UI must present this as a possibly-incomplete live view, not an
 * implied-consistent key listing.
 */
public record ScanPage(String cursor, List<String> keys, boolean complete) {
    public ScanPage {
        keys = List.copyOf(keys);
    }
}
