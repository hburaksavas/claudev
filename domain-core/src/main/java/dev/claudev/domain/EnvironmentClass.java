package dev.claudev.domain;

/**
 * Required, no silent default: an unset classification is treated as {@link #PROD}-equivalent
 * restrictiveness, never as permissive (docs/SECURITY.md, remote-Redis guard).
 */
public enum EnvironmentClass {
    DEV,
    STAGING,
    PROD,
    UNKNOWN
}
