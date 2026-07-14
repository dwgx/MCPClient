package net.marcloud.mcp.dwm.component;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.TextMetrics;
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

    /** Backend-independent text measurement, rebound each frame from the active backend. */
    @FunctionalInterface
    public interface TextMeasurer {
        TextMetrics measure(FontHandle font, CharSequence text, float sizePx);
    }

    private final MdcTheme theme;
    private final UiStateStore store;
    private final WidgetId rootId;
    private final java.util.ArrayDeque<WidgetId> idStack = new java.util.ArrayDeque<>();

    private DrawContext draw;
    private TextMeasurer measurer;
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
    public void bind(DrawContext draw, TextMeasurer measurer, FrameInput input, FrameMetrics metrics) {
        this.draw = draw;
        this.measurer = measurer;
        this.input = input == null ? FrameInput.none() : input;
        this.metrics = metrics;
        // Reset the id stack to the root each frame — a safety net against a component
        // that pushed without popping (unbalanced) on a prior frame.
        idStack.clear();
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
        return idStack.isEmpty() ? rootId : idStack.peek();
    }

    @Override
    public WidgetId childId(String key) {
        return WidgetId.of(id(), key);
    }

    @Override
    public void pushId(String key) {
        idStack.push(WidgetId.of(id(), key));
    }

    @Override
    public void popId() {
        if (!idStack.isEmpty()) {
            idStack.pop();
        }
    }

    @Override
    public TextMetrics measureText(FontHandle font, CharSequence text, float sizePx) {
        if (measurer == null) {
            // No backend measurer bound (shouldn't happen mid-frame): fixed-advance
            // fallback so layout still produces a sane size rather than a null.
            int n = text == null ? 0 : text.length();
            return new TextMetrics(n * sizePx * 0.5f, sizePx * 0.8f, sizePx * 0.2f);
        }
        return measurer.measure(font, text, sizePx);
    }
}
