package net.marcloud.mcp.core.link;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * Fans the single {@link net.marcloud.mcp.core.ke.GameClock game clock} out to the
 * board subsystem's {@code TickSignal} — WITHOUT a compile-time dependency on board
 * (PHASE T, T.8). core and board are zero-hard-dependency peers: core must never
 * {@code import net.marcloud.mcp.board.*}. So this bridge reaches board purely by
 * reflection — board present ⇒ each core {@link TickEvent} is republished as a board
 * {@code TickSignal(tickId)} on the board {@code Trace}; board absent ⇒ silent no-op.
 *
 * <p>This is the tick-only, focused instance of the "core→board neutral pump" idea:
 * one core-side subscription to {@code TickEvent} that mirrors it onto board's bus,
 * so board chips (e.g. {@code TickCounterChip}) run off the SAME clock the kernel
 * uses — never a second tick counter (the roadmap's one-clock rule spans both
 * subsystems). Everything is fault-isolated: a reflection miss or a board-side
 * throw can never disturb the game/publish thread.
 *
 * <p>Discovery mirrors {@code McpLink}/{@code HotkeyCoordinator}: resolve the
 * board port off the {@code Backplane} by string key, drive it by reflected method
 * names, cache the reflected handles after the first success.
 */
public final class BoardClockBridge {

    private static final String BACKPLANE = "net.marcloud.mcp.board.Backplane";
    private static final String BOARD_PORT_KEY = "board.port";
    private static final String TICK_SIGNAL = "net.marcloud.mcp.board.signals.TickSignal";

    private final EventBus bus;

    // Cached reflected handles (resolved lazily on first tick, then reused).
    private volatile boolean resolved;
    private volatile boolean available;
    private volatile Object trace;              // board Trace instance
    private volatile Method tracePublish;       // Trace.publish(Signal)
    private volatile Constructor<?> tickCtor;   // TickSignal(long)

    public BoardClockBridge(EventBus bus) {
        this.bus = bus;
    }

    /**
     * Subscribe to core's {@link TickEvent} so each tick is mirrored to board. A
     * no-op if the bus is null. Safe to call once at boot; board may be absent now
     * and the bridge simply never finds a port (stays a no-op).
     */
    public void attach() {
        if (bus == null) {
            return;
        }
        bus.subscribe(TickEvent.class, this::onTick);
    }

    /** Mirror one core tick onto the board Trace as a TickSignal. Never throws. */
    private void onTick(TickEvent event) {
        try {
            if (!resolved) {
                resolve();
            }
            if (!available) {
                return;
            }
            Object signal = tickCtor.newInstance(event.tickId());
            tracePublish.invoke(trace, signal);
        } catch (Throwable t) {
            // Board absent / signature changed / board subscriber threw — never let
            // it reach the game thread. Mark unavailable so we stop retrying loudly.
            available = false;
        }
    }

    /**
     * One-time reflective resolution of the board Trace + TickSignal ctor. On any
     * miss, {@link #available} stays false and the bridge is a permanent no-op for
     * this run (board is simply not on the classpath / not started).
     */
    private synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> backplane = Class.forName(BACKPLANE);
            Method find = backplane.getMethod("find", String.class);
            Object port = find.invoke(null, BOARD_PORT_KEY);
            if (port == null) {
                return; // board not started / not registered — no-op
            }
            Object traceObj = port.getClass().getMethod("trace").invoke(port);
            if (traceObj == null) {
                return;
            }
            Class<?> tickSignal = Class.forName(TICK_SIGNAL);
            // Trace.publish is generic (publish(E extends Signal)); erased to
            // publish(Signal). Resolve by name + single param to avoid depending on
            // the exact Signal type name here.
            Method publish = null;
            for (Method m : traceObj.getClass().getMethods()) {
                if (m.getName().equals("publish") && m.getParameterCount() == 1) {
                    publish = m;
                    break;
                }
            }
            if (publish == null) {
                return;
            }
            this.trace = traceObj;
            this.tracePublish = publish;
            this.tickCtor = tickSignal.getConstructor(long.class);
            this.available = true;
        } catch (Throwable t) {
            // ClassNotFound (board absent) is the expected miss — stay a no-op.
            this.available = false;
        }
    }

    /** True once resolution succeeded and ticks are being mirrored (for tests/diagnostics). */
    public boolean isAvailable() {
        return available;
    }
}
