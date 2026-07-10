package net.marcloud.mcp.core.introspect;

import java.util.List;

/**
 * Full reflection detail for a class: its kind (class / interface / enum /
 * annotation / abstract-class / unresolved), modifiers, superclass,
 * interfaces, fields, methods, constructors, and the same flags as
 * {@link ClassInfo}. Plus an optional note explaining special cases (e.g. "was
 * not loaded before this call; describe resolved it" or an exception message
 * for unresolved classes).
 */
public record ClassDetail(
        String name,
        String kind,
        String modifiers,
        String superclass,
        List<String> interfaces,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<MethodInfo> constructors,
        boolean loaded,
        boolean jvmModifiable,
        boolean protectedClass,
        String note) {
}
