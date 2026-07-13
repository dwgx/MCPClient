package net.marcloud.mcp.dwm.backend;

/**
 * Opaque handle to a backend-loaded font. Carries no backend type — the backend
 * maps {@link #id()} to its own font/atlas internally.
 */
public record FontHandle(long id) {
}
