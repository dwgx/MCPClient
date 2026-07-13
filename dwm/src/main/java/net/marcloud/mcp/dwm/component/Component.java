package net.marcloud.mcp.dwm.component;

/**
 * Base contract for an MDC-parity component (Button, Card, TextField, ...). A
 * component lays out within a given box and renders itself for one frame using
 * ONLY the {@link ComponentContext} (draw + theme + animation store + input).
 *
 * <p>Immediate-mode: {@link #render} is called every frame; any state that must
 * persist (ripple progress, pressed/hover transition) lives in the
 * {@code UiStateStore} keyed by the context's {@link ComponentContext#id()}, NOT
 * in the component instance.
 *
 * <p>Implementations (the MDC-parity set) reproduce Material Design 3 look +
 * behavior — ripple, state layers, elevation, shape/corner tokens, motion curves —
 * per the design brief. They MUST NOT import any imgui or OpenGL type; all drawing
 * goes through {@link ComponentContext#draw()}.
 */
public interface Component {

    /**
     * Render this component within box (x,y,w,h) DIP for the current frame.
     * Returns interaction result (e.g. clicked) for the caller.
     */
    Result render(ComponentContext ctx, float x, float y, float w, float h);

    /** The intrinsic/preferred size in DIP given the theme, for layout. */
    Size measure(ComponentContext ctx);

    /** Preferred size in DIP. */
    record Size(float width, float height) {}

    /** Per-frame interaction outcome. */
    record Result(boolean hovered, boolean pressed, boolean clicked) {
        public static Result idle() {
            return new Result(false, false, false);
        }
    }
}
