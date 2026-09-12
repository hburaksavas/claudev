package dev.claudev.domain;

/** The base authorization+audit gate's decision — see {@link RedisSafetyPolicy#authorize}. */
public sealed interface MutationAuthorization {

    record Allowed() implements MutationAuthorization {}

    record Denied(String reason) implements MutationAuthorization {}

    static MutationAuthorization allowed() {
        return new Allowed();
    }

    static MutationAuthorization denied(String reason) {
        return new Denied(reason);
    }
}
