package dev.claudev.domain;

import java.nio.file.Path;

/**
 * Where a {@link RuntimeDefinition}'s binaries come from. Only {@code Managed} implies the
 * application owns lifecycle/updates of the binary; per D7/D8, RabbitMQ V1 uses one pinned,
 * checksum-verified {@code Managed} pair, while Redis V1 is {@code Imported}/{@code System} only —
 * no managed local Redis is bundled.
 */
public sealed interface RuntimeSource {

    /** One explicitly tested version pair, verified by checksum before first launch. */
    record Managed(String version, String erlangVersion, String checksumSha256) implements RuntimeSource {}

    /** User pointed the app at an install directory they manage themselves. */
    record Imported(Path installPath) implements RuntimeSource {}

    /** Discovered on the system (e.g. an existing WSL2/Docker Redis) — never lifecycle-owned. */
    record System(Path discoveredPath) implements RuntimeSource {}
}
