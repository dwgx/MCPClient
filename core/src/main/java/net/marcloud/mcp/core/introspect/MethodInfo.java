package net.marcloud.mcp.core.introspect;

import java.util.List;

/**
 * A declared method: its owner class, name, return type (human-readable like
 * {@code void} or {@code java.lang.String}), parameter types (human-readable
 * list), JVM descriptor (like {@code (II)Ljava/lang/String;}), and modifiers
 * (e.g. "public static").
 */
public record MethodInfo(
        String owner,
        String name,
        String returnType,
        List<String> paramTypes,
        String descriptor,
        String modifiers) {
}
