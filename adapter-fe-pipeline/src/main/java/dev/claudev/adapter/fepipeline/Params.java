package dev.claudev.adapter.fepipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Typed extraction from a {@code StepInvocation}'s generic {@code Map<String, Object>} params,
 * with clear failures — the closest thing V1 has to "validated against a schema owned by the step
 * type" (docs/FE_PIPELINE_STEPS.md) without a full JSON-schema layer. Every step executor reads its
 * parameters through here rather than casting ad hoc, so a missing/malformed param always fails
 * the same, readable way.
 */
final class Params {

    private Params() {
    }

    static String requireString(Map<String, Object> params, String key) throws StepExecutionException {
        Object value = params.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new StepExecutionException("Missing or invalid required string param: " + key);
        }
        return s;
    }

    static String optionalString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return (value instanceof String s && !s.isBlank()) ? s : defaultValue;
    }

    static Path requirePath(Map<String, Object> params, String key) throws StepExecutionException {
        return Path.of(requireString(params, key));
    }

    static int optionalInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    static List<String> requireStringList(Map<String, Object> params, String key) throws StepExecutionException {
        Object value = params.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new StepExecutionException("Missing or invalid required list param: " + key);
        }
        for (Object element : list) {
            if (!(element instanceof String)) {
                throw new StepExecutionException("Non-string element in list param: " + key);
            }
        }
        return (List<String>) list;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> optionalStringMap(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) {
                if (!(k instanceof String)) {
                    return Map.of();
                }
            }
            for (Object v : map.values()) {
                if (!(v instanceof String)) {
                    return Map.of();
                }
            }
            return (Map<String, String>) map;
        }
        return Map.of();
    }
}
