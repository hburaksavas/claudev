package dev.claudev.adapter.rabbitmq;

/**
 * The one pinned, checksum-verified RabbitMQ+Erlang/OTP pair this build ships (D7/D8 — see
 * docs/RABBITMQ_RUNTIME.md). Chosen and verified by the WP6 feasibility spike (2026-09-12): the
 * newest available Erlang at spike time (OTP-29.0.6) fails to boot this RabbitMQ release at all
 * ({@code horus/extraction_denied} — an incompatible abstract-code/bytecode format), so this pair
 * is not "whatever was newest," it is the specific combination the spike proved actually works.
 *
 * <p>Both checksums were computed locally against the exact bytes downloaded during that spike run
 * (recorded in RABBITMQ_RUNTIME.md) — Erlang's GitHub release does not publish a checksum for its
 * Windows zip asset (only for the source/doc tarballs), so this is this project's own
 * first-hand-verified value, not a copied upstream one. Re-verify before treating this as a
 * supply-chain-audited pin for a public release.
 */
final class RabbitMqPinnedPair {

    static final String ERLANG_VERSION = "27.3.4.17";
    static final String ERLANG_ZIP_URL =
            "https://github.com/erlang/otp/releases/download/OTP-27.3.4.17/otp_win64_27.3.4.17.zip";
    static final String ERLANG_ZIP_SHA256 =
            "d0c9f030909eed5484a803f8a9e9e3a2d2275d3e531a4e0891cb61fd1f88697f";

    static final String RABBITMQ_VERSION = "4.3.5";
    static final String RABBITMQ_ZIP_URL =
            "https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.3.5/rabbitmq-server-windows-4.3.5.zip";
    static final String RABBITMQ_ZIP_SHA256 =
            "462e626e276f1d670c0c45e96b36298e20c13d3efdd0f3f35e879e9868b4c9ab";

    private RabbitMqPinnedPair() {
    }
}
