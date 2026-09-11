package dev.claudev.adapter.fepipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * The explicit environment block every Git/Maven spawn gets. Per docs/SECURITY.md, a spawn never
 * inherits the caller's ambient environment wholesale — but per docs/FE_PIPELINE_STEPS.md's
 * corporate-network requirement, proxy/CA settings the user's own {@code git.exe}/Maven install is
 * already configured with must still reach the child, or the pipeline fails silently in exactly
 * the locked-down corporate environment this tool targets. This forwards a small, named allow-list
 * of well-known proxy/CA variables from this process's own environment (if set) rather than
 * forwarding everything.
 */
final class CorporateNetworkEnvironment {

    private static final String[] FORWARDED_VARS = {
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "JAVA_TOOL_OPTIONS", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "TEMP", "TMP", "ComSpec", "PATHEXT",
            // mvn.cmd itself requires JAVA_HOME to locate java.exe; M2_HOME/MAVEN_HOME are honored
            // by some install layouts. Since this process's environment is otherwise fully
            // explicit (no inheritance, per docs/SECURITY.md), these must be forwarded by name
            // rather than assumed present.
            "JAVA_HOME", "M2_HOME", "MAVEN_HOME", "MAVEN_OPTS"
    };

    private CorporateNetworkEnvironment() {
    }

    static Map<String, String> baseEnvironment() {
        Map<String, String> env = new HashMap<>();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            env.put("SystemRoot", systemRoot);
        }
        for (String var : FORWARDED_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) {
                env.put(var, value);
            }
        }
        return env;
    }
}
