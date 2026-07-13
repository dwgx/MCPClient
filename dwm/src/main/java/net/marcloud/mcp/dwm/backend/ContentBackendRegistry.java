package net.marcloud.mcp.dwm.backend;

import java.util.List;

/**
 * Registry of available {@link ContentBackend}s — the sibling of
 * {@link BackendRegistry}, kept SEPARATE so the frozen {@link RenderBackend}
 * contract and its callers get zero blast radius. {@link #find} returns {@code null}
 * when a backend is absent, mirroring {@code Backplane.find(...)}; the caller then
 * simply renders no content overlay.
 *
 * <p>Unlike {@link BackendRegistry}, there is NO {@code NullBackend} floor: a
 * content backend (the Compose overlay) is OPTIONAL, so {@link #active()} is {@code
 * null} until one is explicitly {@link #activate(String) activated}, and passing
 * {@code null} to {@link #activate(String)} turns the overlay off again. A frame with
 * no active content backend draws no overlay — the primitive {@link RenderBackend}
 * axis is unaffected.
 */
public interface ContentBackendRegistry {

    void register(ContentBackend backend);

    /** The backend registered under {@code id}, or {@code null} if absent. */
    ContentBackend find(String id);

    List<String> ids();

    /**
     * Switch the active content backend. Passing {@code null} deactivates the overlay
     * (a valid, expected state). Returns {@code false} if a non-null {@code id} is not
     * registered (the previously active backend is then left unchanged).
     */
    boolean activate(String id);

    /** The currently active content backend, or {@code null} when the overlay is off. */
    ContentBackend active();
}
