package dev.claudev.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderResultTest {

    @Test
    void okMapsValue() {
        ProviderResult<Integer> result = ProviderResult.ok(2).map(v -> v * 21);

        assertThat(result.isOk()).isTrue();
        assertThat(result).isEqualTo(ProviderResult.ok(42));
    }

    @Test
    void errPassesThroughMap() {
        ProviderResult<Integer> result = ProviderResult.<Integer>err(new ProviderError.NotFound("x")).map(v -> v * 2);

        assertThat(result.isOk()).isFalse();
        assertThat(result).isEqualTo(ProviderResult.err(new ProviderError.NotFound("x")));
    }
}
