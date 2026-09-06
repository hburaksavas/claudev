package dev.claudev.provider;

import java.util.List;

/**
 * Static, data-shaped description of a compiled-in adapter. Registered at compile time (a
 * {@code const}/registry-style list in V1, not a runtime plugin loader) so the UI can render
 * "what runtimes/pipelines exist" generically, and so a post-V1 out-of-process protocol only has
 * to swap the registry's population mechanism, not the manifest shape itself.
 */
public record AdapterManifest(String id, String version, List<Capability> capabilities, String configJsonSchema) {
    public AdapterManifest {
        capabilities = List.copyOf(capabilities);
    }
}
