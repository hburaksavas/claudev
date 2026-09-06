package dev.claudev.domain;

/**
 * Windows SID string (e.g. "S-1-5-21-..."). Recorded on {@link Workspace} so a second Windows
 * account touching the same on-disk store is refused rather than silently taking control of
 * another user's running instances (D3 / single-user-v1 ADR).
 */
public record OwnerSid(String value) {
    public OwnerSid {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OwnerSid must not be blank");
        }
    }
}
