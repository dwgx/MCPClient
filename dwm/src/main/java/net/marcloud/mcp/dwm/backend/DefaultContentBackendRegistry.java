package net.marcloud.mcp.dwm.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link ContentBackendRegistry}: an in-memory map of registered content
 * backends with a live "active" pointer. Sibling of {@link DefaultBackendRegistry},
 * with one deliberate difference — there is NO {@link NullBackend} floor, because a
 * content overlay is optional. {@link #active()} starts {@code null} (overlay off)
 * and {@link #activate(String) activate(null)} turns it back off, so a frame with no
 * content backend simply draws no overlay.
 *
 * <p>Single-threaded by contract: registration + activation happen at UI bootstrap
 * on one thread; {@link #active()} is read on the render thread. The active
 * reference is {@code volatile} so a mid-session {@link #activate} switch is visible
 * to the render thread without tearing.
 */
public final class DefaultContentBackendRegistry implements ContentBackendRegistry {

    private final Map<String, ContentBackend> byId = new LinkedHashMap<>();
    private volatile ContentBackend active; // null = overlay off (the default)

    @Override
    public synchronized void register(ContentBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        String id = backend.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("backend id must not be blank");
        }
        byId.put(id, backend);
    }

    @Override
    public synchronized ContentBackend find(String id) {
        return id == null ? null : byId.get(id);
    }

    @Override
    public synchronized List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    @Override
    public synchronized boolean activate(String id) {
        if (id == null) {
            // Turn the overlay off — an expected state, distinct from "not found".
            active = null;
            return true;
        }
        ContentBackend b = byId.get(id);
        if (b == null) {
            return false; // unknown id: leave the current active pointer unchanged
        }
        active = b;
        return true;
    }

    @Override
    public ContentBackend active() {
        return active; // volatile read; null when the overlay is off
    }
}
