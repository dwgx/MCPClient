package net.marcloud.mcp.dwm.backend;

/**
 * What a backend can actually do. Drives the DEGRADATION chains inside a backend
 * (the backend, not the UI, checks these). UI always calls the richest primitive
 * and gets best-available fidelity.
 */
public record BackendCaps(
        boolean path,
        boolean clip,
        boolean perCornerRadius,
        boolean layerOpacity,
        boolean surfaceTintShadow,
        int maxTextureSize) {

    /** A minimal capability set — what a NullBackend advertises. */
    public static BackendCaps minimal() {
        return new BackendCaps(false, false, false, false, false, 0);
    }
}
