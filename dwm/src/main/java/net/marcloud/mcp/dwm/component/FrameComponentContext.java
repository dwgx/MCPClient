package net.marcloud.mcp.dwm.component;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.compositor.UiStateStore;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.theme.MdcTheme;

/**
 * Production {@link ComponentContext}: the per-frame bundle the frame driver
 * ({@code UiComposer}) hands to the root component so component code touches only DWM's
 * own layers — {@link DrawContext} (draw), {@link MdcTheme} (tokens),
 * {@link UiStateStore} (retained animation), and per-frame input/metrics. A component
 * never reaches past this to a backend or GL type.
 *
 * <p><b>Reused across frames.</b> The theme, store, and root {@link WidgetId} are fixed
 * for the composer's lifetime; the backend's {@link DrawContext}, {@link FrameInput},
 * and {@link FrameMetrics} are rebound each frame via {@link #bind} (the {@code
 * DrawContext} is only valid between the backend's begin/endFrame, so it MUST be rebound,
 * never cached across frames). This mirrors the immediate-mode discipline: geometry is
 * recomputed every frame, identity/animation persist in the store keyed by id.
 */
public final class FrameComponentContext implements ComponentContext {

    private final MdcTheme theme;
    private final UiStateStore store;
    private final WidgetId rootId;

    private DrawContext draw;
    private FrameInput input = FrameInput.none();
    private FrameMetrics metrics;

    public FrameComponentContext(MdcTheme theme, UiStateStore store, WidgetId rootId) {
        if (theme == null || store == null || rootId == null) {
            throw new IllegalArgumentException("theme, store and rootId must not be null");
        }
        this.theme = theme;
        this.store = store;
        this.rootId = rootId;
    }

    /**
     * Rebind the per-frame values for the frame about to be drawn. Called by the driver
     * inside the backend's begin/endFrame window, so {@code draw} is a live context.
     */
    public void bind(DrawContext draw, FrameInput input, FrameMetrics metrics) {
        this.draw = draw;
        this.input = input == null ? FrameInput.none() : input;
        this.metrics = metrics;
    }

    @Override
    public DrawContext draw() {
        if (draw == null) {
            throw new IllegalStateException("draw() called outside a bound frame");
        }
        return draw;
    }

    @Override
    public MdcTheme theme() {
        return theme;
    }

    @Override
    public UiStateStore store() {
        return store;
    }

    @Override
    public FrameInput input() {
        return input;
    }

    @Override
    public FrameMetrics metrics() {
        return metrics;
    }

    @Override
    public WidgetId id() {
        return rootId;
    }

    @Override
    public WidgetId childId(String key) {
        return WidgetId.of(rootId, key);
    }
}
