package dev.claudev.provider;

import java.util.function.Function;

/**
 * Java stand-in for a closed {@code Result<T, ProviderError>}. Every {@code RuntimeProvider},
 * {@code ConnectionProvider}, {@code ProjectPipelineProvider}, and {@code SecretStore} method
 * returns this instead of throwing, so failure is part of the method's type, not an exception
 * hierarchy adapters can forget to declare.
 */
public sealed interface ProviderResult<T> {

    record Ok<T>(T value) implements ProviderResult<T> {}

    record Err<T>(ProviderError error) implements ProviderResult<T> {}

    static <T> ProviderResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> ProviderResult<T> err(ProviderError error) {
        return new Err<>(error);
    }

    default <R> ProviderResult<R> map(Function<T, R> fn) {
        return switch (this) {
            case Ok<T> ok -> ProviderResult.ok(fn.apply(ok.value()));
            case Err<T> err -> ProviderResult.err(err.error());
        };
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }
}
