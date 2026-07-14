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
 * Everything a {@link Component} needs to render ONE frame, bundled so component
 * code touches only DWM's own layers — the {@link DrawContext} (draw), the
 * {@link MdcTheme} (tokens), the {@link UiStateStore} (retained animation), and
 * per-frame input/metrics. A component NEVER reaches past this to imgui or GL.
 *
 * <p>This is the surface an implementer (or Grok, per the handoff contract) codes
 * against: draw via {@code draw()}, pull colors/shape from {@code theme()}, drive
 * ripple/state-layer via {@code store()} keyed by a {@link WidgetId}.
 */
public interface ComponentContext {

    DrawContext draw();

    MdcTheme theme();

    UiStateStore store();

    FrameInput input();

    FrameMetrics metrics();

    /** Stable id for the widget currently being built (the top of the id stack). */
    WidgetId id();

    /** Derive a child id under the CURRENT id-stack top (lists MUST pass a stable key). */
    WidgetId childId(String key);

    /**
     * Push a child id scope (the imgui PushID idiom): subsequent {@link #id()} /
     * {@link #childId} resolve under {@code key} until {@link #popId()}. A layout
     * container wraps each child in {@code pushId(stableKey)} / {@code popId()} so N
     * sibling widgets (e.g. a Column of buttons) get DISTINCT ids and their ripple /
     * state-layer timelines never collide. Balanced push/pop is the caller's contract;
     * the composer resets the stack each frame as a safety net.
     */
    void pushId(String key);

    /** Pop the innermost id scope pushed by {@link #pushId(String)}. */
    void popId();

    /**
     * Measure text with the ACTIVE backend's real font metrics, so layout is consistent
     * across backends (the GL placeholder, imgui's atlas, and Skiko's true typeface all
     * report through here rather than each component guessing an advance). Backends that
     * lack a real atlas return a fixed-advance approximation, but ALL components measure
     * through this one path — so a component's measured size does not silently differ by
     * backend. Never null.
     */
    TextMetrics measureText(FontHandle font, CharSequence text, float sizePx);
}
