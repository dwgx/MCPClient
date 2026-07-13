package net.marcloud.mcp.dwm.compositor;

import java.util.function.Supplier;

/**
 * The retained animation-state store — the crux that lets immediate-mode drawing
 * host MD3's stateful animations (ripple expansion, state-layer/elevation
 * transitions). Components fetch-or-create their {@link WidgetState} by
 * {@link WidgetId} each frame; the compositor advances every live timeline once
 * per frame ({@link #tickAll}) before drawing, and evicts states not touched this
 * frame ({@link #endFrameGc}) so nothing leaks.
 *
 * <p>Holds ZERO backend type, so it survives a backend hot-swap intact.
 */
public interface UiStateStore {

    /** Fetch this widget's state, creating it via {@code factory} on first touch. */
    <S extends WidgetState> S state(WidgetId id, Supplier<S> factory);

    /** Advance every live timeline once, BEFORE draw. */
    void tickAll(float dtSeconds);

    /** Evict states not touched during {@code frameId} (TTL/LRU) — no leak. */
    void endFrameGc(long frameId);

    /** True if any live state is mid-animation (drives continuous vs on-demand pump). */
    boolean anyAnimating();
}
