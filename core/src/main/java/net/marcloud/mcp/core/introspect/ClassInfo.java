package net.marcloud.mcp.core.introspect;

/**
 * Lightweight summary of a loaded class: its fully-qualified name, its
 * class loader, module (if any), and flags showing whether the JVM allows
 * redefinition and whether Core's privilege model marks it protected.
 */
public record ClassInfo(
        String name,
        String classLoader,
        String module,
        boolean loaded,
        boolean jvmModifiable,
        boolean protectedClass) {
}
