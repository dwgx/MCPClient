package net.marcloud.mcp.core.cm;

/**
 * A declared field's name, its type (as a human-readable string like
 * {@code int} or {@code java.lang.String[]}), and its modifiers (e.g. "public
 * static final").
 */
public record FieldInfo(String name, String type, String modifiers) {
}
