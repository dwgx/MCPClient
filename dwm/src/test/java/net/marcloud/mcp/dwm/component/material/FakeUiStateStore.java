package net.marcloud.mcp.dwm.component.material;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.marcloud.mcp.dwm.compositor.UiStateStore;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.compositor.WidgetState;

/**
 * In-memory {@link UiStateStore} for component tests. One state per
 * {@link WidgetId}; type is fixed at first create (matching a real store).
 */
final class FakeUiStateStore implements UiStateStore {

    private final Map<WidgetId, WidgetState> states = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <S extends WidgetState> S state(WidgetId id, Supplier<S> factory) {
        WidgetState existing = states.get(id);
        if (existing != null) {
            return (S) existing;
        }
        S created = factory.get();
        states.put(id, created);
        return created;
    }

    @Override
    public void tickAll(float dtSeconds) {
        for (WidgetState s : states.values()) {
            s.tick(dtSeconds);
        }
    }

    @Override
    public void endFrameGc(long frameId) {
        // Tests keep all states; no eviction.
    }

    @Override
    public boolean anyAnimating() {
        for (WidgetState s : states.values()) {
            if (s.animating()) {
                return true;
            }
        }
        return false;
    }

    int size() {
        return states.size();
    }

    @SuppressWarnings("unchecked")
    <S extends WidgetState> S get(WidgetId id) {
        return (S) states.get(id);
    }
}
