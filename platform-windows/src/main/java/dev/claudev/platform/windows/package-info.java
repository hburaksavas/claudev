/**
 * JNA Win32 bindings for the process/Job Object supervision model. Every raw HANDLE the
 * application ever touches is meant to live in this module (docs/PROCESS_SAFETY.md) — adapters
 * spawn and supervise child processes by calling into here, never by hand-rolling their own
 * native calls.
 */
package dev.claudev.platform.windows;
