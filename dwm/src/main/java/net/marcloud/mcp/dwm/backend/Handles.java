package net.marcloud.mcp.dwm.backend;

/**
 * Opaque backend resource handles + draw specs referenced by {@link DrawContext}.
 * These are neutral value types — they carry NO backend/imgui/GL type, so the
 * layers above the SPI stay backend-agnostic. A backend maps a handle's opaque id
 * to its own resource internally.
 */
public final class Handles {
    private Handles() {
    }
}
