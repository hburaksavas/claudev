package dev.claudev.provider.secret;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.ProviderResult;

/**
 * Default adapter wraps DPAPI/Credential Manager. {@link #resolve} is the only method that ever
 * sees plaintext, and callers must resolve at execution time only — never persist the result into
 * SQLite, a log, or an immutable operation plan (docs/SECURITY.md).
 */
public interface SecretStore {

    AdapterManifest manifest();

    ProviderResult<SecretHandle> store(String plaintextValue);

    ProviderResult<String> resolve(SecretHandle handle);

    ProviderResult<Ack> delete(SecretHandle handle);
}
