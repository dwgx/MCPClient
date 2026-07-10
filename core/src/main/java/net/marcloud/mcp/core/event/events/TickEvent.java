package net.marcloud.mcp.core.event.events;

import net.marcloud.mcp.core.event.GameEvent;

/**
 * Fired once per client tick (attached at the {@code Minecraft.runTick} seam).
 * Lets observers sample state on a regular cadence and lets time-based logic
 * run on the game thread.
 */
public final class TickEvent extends GameEvent {

    private final long tickCount;

    public TickEvent(long tickCount) {
        this.tickCount = tickCount;
    }

    public long tickCount() {
        return tickCount;
    }
}
