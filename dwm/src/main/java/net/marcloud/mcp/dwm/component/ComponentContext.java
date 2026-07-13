package net.marcloud.mcp.dwm.component;

import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
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

    /** Stable id for the widget currently being built (for state keying). */
    WidgetId id();

    /** Derive a child id under the current widget (lists MUST pass a stable key). */
    WidgetId childId(String key);
}
