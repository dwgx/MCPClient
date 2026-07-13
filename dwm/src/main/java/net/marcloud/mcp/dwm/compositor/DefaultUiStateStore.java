package net.marcloud.mcp.dwm.compositor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Production {@link UiStateStore}: the retained animation-state store that lets
 * immediate-mode rendering host MD3's stateful animations. Keyed by
 * {@link WidgetId}; a widget's timeline (ripple/state-layer) survives across frames
 * because {@link #state} returns the SAME instance for the same id, while geometry
 * is recomputed every frame.
 *
 * <p>Frame contract (driven by the compositor):
 * <ol>
 *   <li>{@link #tickAll(float)} advances every live timeline ONCE, before draw;</li>
 *   <li>components call {@link #state} during draw (marks the id touched this frame);</li>
 *   <li>{@link #endFrameGc(long)} evicts states not touched for {@code idleFramesToEvict}
 *       consecutive frames, so a widget that disappears does not leak.</li>
 * </ol>
 *
 * <p><b>Eviction is grace-period, not immediate</b>: a state untouched for ONE frame
 * is NOT dropped (a widget scrolled off for a frame, or a frame where it was not
 * reached, must not lose its animation). Only after {@code idleFramesToEvict}
 * consecutive untouched frames is it removed. A state that is still
 * {@link WidgetState#animating()} is never evicted mid-animation.
 *
 * <p>Single-threaded by contract: created, ticked, read, and GC'd on the render
 * thread (the compositor owns the frame pump). Not synchronized.
 */
public final class DefaultUiStateStore implements UiStateStore {

    /** Default grace period: evict a state after this many consecutive untouched frames. */
    public static final int DEFAULT_IDLE_FRAMES_TO_EVICT = 60;

    private final int idleFramesToEvict;

    /** Insertion-ordered so tick/GC iterate deterministically. */
    private final Map<WidgetId, Entry> states = new LinkedHashMap<>();

    /** Frames elapsed, bumped by endFrameGc; used to detect untouched staleness. */
    private long frameCounter;

    public DefaultUiStateStore() {
        this(DEFAULT_IDLE_FRAMES_TO_EVICT);
    }

    public DefaultUiStateStore(int idleFramesToEvict) {
        if (idleFramesToEvict < 1) {
            throw new IllegalArgumentException("idleFramesToEvict must be >= 1");
        }
        this.idleFramesToEvict = idleFramesToEvict;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends WidgetState> S state(WidgetId id, Supplier<S> factory) {
        if (id == null) {
            throw new IllegalArgumentException("WidgetId must not be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        Entry e = states.get(id);
        if (e == null) {
            S created = factory.get();
            if (created == null) {
                throw new IllegalStateException("state factory returned null for " + id);
            }
            e = new Entry(created);
            states.put(id, e);
        }
        // Mark touched this frame (survives the next GC sweep).
        e.lastTouchedFrame = frameCounter;
        // Class-cast is safe by the per-id convention (a given id always uses the
        // same state type); a mismatch is a caller bug and should surface loudly.
        return (S) e.state;
    }

    @Override
    public void tickAll(float dtSeconds) {
        if (dtSeconds <= 0f) {
            return;
        }
        for (Entry e : states.values()) {
            try {
                e.state.tick(dtSeconds);
            } catch (RuntimeException ex) {
                // One bad state must not break the whole frame's animation tick.
                System.err.println("[DWM] widget state tick threw: " + ex);
            }
        }
    }

    @Override
    public void endFrameGc(long frameId) {
        // Evict states untouched for >= idleFramesToEvict consecutive frames, unless
        // still animating (never drop a live animation mid-flight).
        Iterator<Map.Entry<WidgetId, Entry>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            long idle = frameCounter - e.lastTouchedFrame;
            if (idle >= idleFramesToEvict && !safeAnimating(e.state)) {
                it.remove();
            }
        }
        frameCounter++;
    }

    @Override
    public boolean anyAnimating() {
        for (Entry e : states.values()) {
            if (safeAnimating(e.state)) {
                return true;
            }
        }
        return false;
    }

    /** Live state count (for tests/diagnostics). */
    public int size() {
        return states.size();
    }

    private static boolean safeAnimating(WidgetState s) {
        try {
            return s.animating();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static final class Entry {
        final WidgetState state;
        long lastTouchedFrame;

        Entry(WidgetState state) {
            this.state = state;
        }
    }
}
