package net.marcloud.mcp.dwm.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link BackendRegistry}: an in-memory map of registered backends with a
 * live "active" pointer, enabling the hot-swap the DWM design locks in. Mirrors the
 * {@code Backplane.find -> null} idiom: {@link #find} returns null when a backend is
 * absent so the caller degrades (installs {@link NullBackend}) rather than throwing.
 *
 * <p>Bootstrap contract: the registry always has a usable {@link #active()} — it
 * seeds a {@link NullBackend} at construction, so even before any real backend
 * (imgui/Skia/Compose) registers, drawing is a safe no-op and layout still runs.
 *
 * <p>Single-threaded by contract: registration + activation happen at UI bootstrap
 * on one thread; {@link #active()} is read on the render thread. The active
 * reference is {@code volatile} so a mid-session {@link #activate} switch is visible
 * to the render thread without tearing.
 */
public final class DefaultBackendRegistry implements BackendRegistry {

    private final Map<String, RenderBackend> byId = new LinkedHashMap<>();
    private volatile RenderBackend active;

    public DefaultBackendRegistry() {
        // Fail-safe floor: a NullBackend is always present and active until a real
        // backend registers and is activated.
        RenderBackend nul = new NullBackend();
        byId.put(nul.id(), nul);
        this.active = nul;
    }

    @Override
    public synchronized void register(RenderBackend backend) {
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
    public synchronized RenderBackend find(String id) {
        return id == null ? null : byId.get(id);
    }

    @Override
    public synchronized List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    @Override
    public synchronized boolean activate(String id) {
        RenderBackend b = byId.get(id);
        if (b == null) {
            return false;
        }
        active = b;
        return true;
    }

    @Override
    public RenderBackend active() {
        return active; // volatile read; never null (seeded with NullBackend)
    }
}
