package net.marcloud.mcp.core.introspect;

import java.util.List;

/**
 * Result of {@link IntrospectionService#listClasses}: the total number of
 * classes in the snapshot, how many matched the filters, the capped list of
 * classes shown, and the source ("instrumentation" or "reflection-fallback").
 */
public record ClassListing(
        int total,
        int matched,
        List<ClassInfo> classes,
        String source) {
}
