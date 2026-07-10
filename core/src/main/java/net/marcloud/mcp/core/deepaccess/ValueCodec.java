package net.marcloud.mcp.core.deepaccess;

import java.util.Arrays;

/**
 * Value codec: converts JSON args (Number/String/Boolean/Map) to typed Java
 * values for field writes and method invocations. Supports primitives, strings,
 * and object-path resolution via RootResolver.
 */
final class ValueCodec {

    private ValueCodec() {
    }

    /**
     * Coerce {@code json} (a parsed MCP arg value) to {@code target} type. Handles
     * primitives (Number -> int/long/double/...), char (1-char String), boolean
     * ("true"/"false"), null for references, and Map {"$path":"..."} or
     * {"$class":"...", "$field":"..."} for live-object resolution.
     *
     * @param target the desired type (from Field.getType() or Method param type)
     * @param json   the raw JSON-decoded value
     * @param roots  resolver for live-object paths (may be null if no resolution needed)
     * @return coerced value, assignable to {@code target}
     * @throws DeepAccessException if coercion fails
     */
    static Object coerce(Class<?> target, Object json, RootResolver roots) {
        if (json == null) {
            if (target.isPrimitive()) {
                throw new DeepAccessException("cannot coerce null to primitive " + target.getName());
            }
            return null;
        }

        // primitives
        if (target == int.class || target == Integer.class) {
            if (json instanceof Number n) {
                return n.intValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to int");
        }
        if (target == long.class || target == Long.class) {
            if (json instanceof Number n) {
                return n.longValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to long");
        }
        if (target == double.class || target == Double.class) {
            if (json instanceof Number n) {
                return n.doubleValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to double");
        }
        if (target == float.class || target == Float.class) {
            if (json instanceof Number n) {
                return n.floatValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to float");
        }
        if (target == short.class || target == Short.class) {
            if (json instanceof Number n) {
                return n.shortValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to short");
        }
        if (target == byte.class || target == Byte.class) {
            if (json instanceof Number n) {
                return n.byteValue();
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to byte");
        }
        if (target == boolean.class || target == Boolean.class) {
            if (json instanceof Boolean b) {
                return b;
            }
            if (json instanceof String s) {
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
            throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to boolean");
        }
        if (target == char.class || target == Character.class) {
            if (json instanceof String s && s.length() == 1) {
                return s.charAt(0);
            }
            throw new DeepAccessException("cannot coerce " + json + " to char (need 1-char String)");
        }

        // String
        if (target == String.class) {
            return json.toString();
        }

        // reference type: check for $path or $class+$field resolution
        if (json instanceof java.util.Map<?, ?> map) {
            if (map.containsKey("$path")) {
                if (roots == null) {
                    throw new DeepAccessException("$path resolution requires RootResolver");
                }
                String path = map.get("$path").toString();
                return roots.resolveReceiver(path);
            }
            if (map.containsKey("$class") && map.containsKey("$field")) {
                // static field resolution: not implemented in minimal C5 (can add if needed)
                throw new DeepAccessException("$class+$field resolution not yet implemented");
            }
        }

        // otherwise, direct passthrough (hope it's assignable)
        if (target.isInstance(json)) {
            return json;
        }

        throw new DeepAccessException("cannot coerce " + json.getClass().getName() + " to " + target.getName());
    }

    /**
     * Map type name (from JSON paramTypes) to Class. Supports primitives ("int"),
     * wrappers ("java.lang.Integer"), and any class on the given loader.
     */
    static Class<?> classForTypeName(String name, ClassLoader loader) {
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "short" -> short.class;
            case "byte" -> byte.class;
            case "boolean" -> boolean.class;
            case "char" -> char.class;
            case "void" -> void.class;
            default -> {
                try {
                    yield Class.forName(name, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new DeepAccessException("unknown type: " + name, e);
                }
            }
        };
    }

    /**
     * Render a value (result of getField/invoke) to a readable String for MCP tool
     * output. Arrays are deep-stringified, nulls are "null", others via toString.
     */
    static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            // Arrays.deepToString handles multi-dimensional arrays correctly
            if (value instanceof Object[]) {
                return Arrays.deepToString((Object[]) value);
            }
            // primitive arrays
            if (value instanceof int[]) return Arrays.toString((int[]) value);
            if (value instanceof long[]) return Arrays.toString((long[]) value);
            if (value instanceof double[]) return Arrays.toString((double[]) value);
            if (value instanceof float[]) return Arrays.toString((float[]) value);
            if (value instanceof short[]) return Arrays.toString((short[]) value);
            if (value instanceof byte[]) return Arrays.toString((byte[]) value);
            if (value instanceof boolean[]) return Arrays.toString((boolean[]) value);
            if (value instanceof char[]) return Arrays.toString((char[]) value);
        }
        return value.toString();
    }
}
