package net.marcloud.mcp.core.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L7 boundary validation for the single supervised tool gate — the JVM analogue
 * of NT's {@code ProbeForRead} + argument capture. Before a tool runs, its
 * argument map is (1) deep-copied into a fully immutable snapshot so the tool can
 * never see a later-mutated view (TOCTOU), and (2) validated against the tool's
 * declared JSON input schema. Both operations are pure/stateless.
 */
public final class IoProbe {

    private IoProbe() {
    }

    /** Outcome of a schema check: {@code ok} → dispatch; else {@code message} is the domain error. */
    public record Result(boolean ok, String message) {
        public static final Result OK = new Result(true, null);

        public static Result fail(String m) {
            return new Result(false, m);
        }
    }

    // ---- (A) deep copy + freeze (capture) ---------------------------------

    /**
     * Recursively deep-copy {@code v} into an immutable value. Maps → a fresh
     * unmodifiable {@link LinkedHashMap} of frozen values; Lists → unmodifiable
     * list of frozen elements; String/Number/Boolean/null pass through (already
     * immutable). Any other mutable reference is defensively stringified so no
     * live object can leak across the boundary and be swapped after validation.
     */
    public static Object deepFreeze(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(Math.max(4, m.size() * 2));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepFreeze(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (v instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(deepFreeze(o));
            }
            return Collections.unmodifiableList(copy);
        }
        return String.valueOf(v);
    }

    /**
     * Freeze a top-level argument map. Null/empty → the shared immutable empty map
     * (handlers already treat null and empty identically).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> freezeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>) deepFreeze(args);
    }

    // ---- (B) lightweight JSON-schema validation ---------------------------

    /** Validate {@code args} (already frozen) against a raw JSON input-schema map. */
    public static Result validate(Map<String, Object> schema, Map<String, Object> args) {
        return validateObject(schema, args, "");
    }

    private static Result validateObject(Map<String, Object> schema, Map<String, Object> args,
                                         String path) {
        if (schema == null) {
            return Result.OK; // no schema → permissive
        }
        // required: each named key must be present AND non-null (handlers treat a
        // null value as "missing", so we align with that here).
        if (schema.get("required") instanceof List<?> required) {
            for (Object r : required) {
                String key = String.valueOf(r);
                Object val = (args == null) ? null : args.get(key);
                if (val == null) {
                    return Result.fail("missing required argument '" + join(path, key) + "'");
                }
            }
        }
        // typed properties: validate declared ones; UNKNOWN keys are allowed
        // (additionalProperties defaults true — lets AI tools' empty-schema accept
        // any arg map).
        if (!(schema.get("properties") instanceof Map<?, ?> props) || args == null) {
            return Result.OK;
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (props.get(e.getKey()) instanceof Map<?, ?> propSchema) {
                @SuppressWarnings("unchecked")
                Result r = validateValue((Map<String, Object>) propSchema, e.getValue(),
                        join(path, e.getKey()));
                if (!r.ok()) {
                    return r;
                }
            }
        }
        return Result.OK;
    }

    private static Result validateValue(Map<String, Object> schema, Object value, String path) {
        if (!(schema.get("type") instanceof String type)) {
            return Result.OK; // untyped property → accept
        }
        if (value == null) {
            return Result.OK; // presence is 'required's job, not 'type's
        }
        switch (type) {
            case "string" -> {
                if (!(value instanceof String)) {
                    return typeFail(path, "string", value);
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    return typeFail(path, "boolean", value);
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    return typeFail(path, "number", value);
                }
            }
            case "integer" -> {
                if (!isInteger(value)) {
                    return typeFail(path, "integer", value);
                }
            }
            case "array" -> {
                if (!(value instanceof List<?>)) {
                    return typeFail(path, "array", value);
                }
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?>)) {
                    return typeFail(path, "object", value);
                }
            }
            default -> {
                // Unknown declared type: don't reject (forward-compatible).
            }
        }
        // enum: if declared, the value must be one of the listed constants.
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            return Result.fail("argument '" + path + "' must be one of " + allowed
                    + " but was " + value);
        }
        return Result.OK;
    }

    private static boolean isInteger(Object v) {
        if (v instanceof Integer || v instanceof Long) {
            return true;
        }
        // JSON often decodes integers as Double; accept whole-valued numbers.
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d);
        }
        return false;
    }

    private static Result typeFail(String path, String expected, Object value) {
        return Result.fail("argument '" + path + "' must be " + expected
                + " but was " + value.getClass().getSimpleName());
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
