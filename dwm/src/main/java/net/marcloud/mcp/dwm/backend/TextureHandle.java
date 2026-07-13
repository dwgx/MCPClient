package net.marcloud.mcp.dwm.backend;

/**
 * Opaque handle to a backend-uploaded texture. Carries no backend type — the
 * backend maps {@link #id()} to its own GPU texture internally.
 */
public record TextureHandle(long id) {
}
