# ADR-005: Default SecretStore is user-scoped Windows DPAPI

## Status
Decided; implementation exists and is verified real (not mocked) on a live machine.

## Context
The original product context allowed "local encoded/hidden storage" as the initial credential
adapter, with the caveat that encoding must never be presented as encryption.

## Decision
The default, recommended `SecretStore` adapter (`secret-store-dpapi`) wraps user-scoped Windows
DPAPI (`CryptProtectData`/`CryptUnprotectData` via JNA). SQLite only ever stores an opaque
`SecretRef.opaqueHandle`; `resolve()` is the only method that ever produces plaintext, and its
result must not be persisted anywhere.

## Alternatives rejected
- **Make the encoded/hidden-file adapter the default.** Base64 (or any reversible encoding without
  a key) is not encryption; making it the recommended path in an open-source tool that manages
  developers' credentials would be actively misleading about the security properties users are
  getting.

## Consequences
- `secret-store-legacy` (Base64 encoding) still exists, but only as an off-by-default import path
  for legacy configs, and anything using it must carry a persistent (non-dismissible-once)
  "not encrypted" UI badge — a one-time toast is easy to miss and the risk is ongoing.
- `DpapiSecretStoreTest` performs a real round-trip through the actual Win32 DPAPI on the build
  machine — this is a genuine correctness check, not a mock asserting the adapter calls the right
  methods.
- Not yet tested: behavior under Windows profile roaming/reset scenarios where the DPAPI master key
  may become unavailable — tracked in [RISK_REGISTER.md](../RISK_REGISTER.md).
