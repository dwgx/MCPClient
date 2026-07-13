package net.marcloud.mcp.dwm.backend;

import java.util.List;

/**
 * Registry of available {@link RenderBackend}s, enabling the hot-swap the design
 * locks in. {@link #find} returns {@code null} when a backend is absent — the
 * caller degrades (installs {@code NullBackend}), mirroring
 * {@code Backplane.find(...)} returning null. The active backend can be switched
 * at runtime via {@link #activate(String)}.
 */
public interface BackendRegistry {

    void register(RenderBackend backend);

    /** The backend registered under {@code id}, or {@code null} if absent. */
    RenderBackend find(String id);

    List<String> ids();

    /** Switch the active backend. Returns false if {@code id} is not registered. */
    boolean activate(String id);

    /** The currently active backend (never null once bootstrapped — falls back to NullBackend). */
    RenderBackend active();
}
