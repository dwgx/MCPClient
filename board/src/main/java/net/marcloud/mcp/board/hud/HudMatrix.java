package net.marcloud.mcp.board.hud;

import java.util.List;

import net.marcloud.mcp.board.Matrix;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.RenderSignal;

/**
 * The HUD manager — a {@link Matrix} of {@link Panel}s that draws every enabled
 * panel once per frame. It composes the frozen {@code Matrix<Panel>} (which is
 * {@code final}, so this is composition, not subclassing) for id/lifecycle/batch
 * management and adds the render pass: subscribe to a {@link RenderSignal} on a
 * {@link Trace}, and when one arrives, iterate the panels in insertion order,
 * skipping disabled ones, and fire each enabled panel's render bridge.
 *
 * <p><b>Headless-safe:</b> nothing here touches OpenGL. Constructing a
 * {@code HudMatrix}, adding panels, and even dispatching a {@code RenderSignal}
 * only resolves layout and calls {@link Panel#onRender} (a no-op by default), so
 * the whole pass runs in a test without a GL context. GL only enters through a
 * concrete panel's {@code onRender}.
 *
 * <p>Delegates the standard manager surface ({@code add/remove/byId/all/…}) to
 * the wrapped matrix so a HUD reads like any other feature manager. Not part of
 * the frozen skeleton — a HUD-subsystem manager built on the frozen types.
 */
public final class HudMatrix implements net.marcloud.mcp.board.Manager<Panel> {

    private final Matrix<Panel> panels = new Matrix<Panel>();

    /** The render-signal listener; kept so {@link #detach} can unsubscribe it. */
    private final Trace.Listener<RenderSignal> renderListener =
            new Trace.Listener<RenderSignal>() {
                @Override
                public void on(RenderSignal signal) {
                    render(signal);
                }
            };

    private Trace attachedTrace;

    /**
     * Subscribe this HUD to {@code trace}'s {@link RenderSignal}s so it draws on
     * every frame. Idempotent per trace — attaching to the same trace twice does
     * not double-subscribe; attaching to a different trace moves the
     * subscription. Returns {@code this} for chaining.
     */
    public HudMatrix attach(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        if (attachedTrace == trace) {
            return this;
        }
        detach();
        trace.subscribe(RenderSignal.class, renderListener);
        attachedTrace = trace;
        return this;
    }

    /** Unsubscribe from the attached {@link Trace} (if any). Idempotent. */
    public void detach() {
        if (attachedTrace != null) {
            attachedTrace.unsubscribe(renderListener);
            attachedTrace = null;
        }
    }

    /** {@code true} if this HUD is currently subscribed to a {@link Trace}. */
    public boolean isAttached() {
        return attachedTrace != null;
    }

    /**
     * Render pass: for each enabled {@link Panel} in insertion order, resolve its
     * position and fire {@link Panel#onRender}. Disabled panels are skipped. Each
     * panel is fault-isolated (via {@link Panel#fireRender}) so one bad panel
     * cannot break the pass. Also directly callable by a game render seam that
     * prefers not to route through a {@link Trace}.
     */
    public void render(RenderSignal signal) {
        if (signal == null) {
            return;
        }
        for (Panel panel : panels.all()) {
            if (panel.isEnabled()) {
                panel.fireRender(signal);
            }
        }
    }

    // ---- delegated manager surface (mirrors Matrix) ------------------------

    /** Add a panel; fires its load lifecycle. See {@link Matrix#add}. */
    public Panel add(Panel panel) {
        return panels.add(panel);
    }

    /** Remove a panel; disables + unloads it. See {@link Matrix#remove}. */
    public boolean remove(Panel panel) {
        return panels.remove(panel);
    }

    /** Remove the panel with {@code id}. See {@link Matrix#removeById}. */
    public Panel removeById(String id) {
        return panels.removeById(id);
    }

    /** The panel with {@code id}, or {@code null}. See {@link Matrix#byId}. */
    public Panel byId(String id) {
        return panels.byId(id);
    }

    /** {@code true} if a panel with {@code id} is present. */
    public boolean contains(String id) {
        return panels.contains(id);
    }

    /** Unmodifiable insertion-order snapshot of all panels. */
    public List<Panel> all() {
        return panels.all();
    }

    /** Number of panels. */
    public int size() {
        return panels.size();
    }

    /** Enable every panel. */
    public void enableAll() {
        panels.enableAll();
    }

    /** Disable every panel. */
    public void disableAll() {
        panels.disableAll();
    }

    /**
     * Detach from any {@link Trace}, then disable + unload and drop every panel.
     */
    public void clear() {
        detach();
        panels.clear();
    }

    /** The underlying feature matrix, for callers that need the raw manager. */
    public Matrix<Panel> matrix() {
        return panels;
    }
}
