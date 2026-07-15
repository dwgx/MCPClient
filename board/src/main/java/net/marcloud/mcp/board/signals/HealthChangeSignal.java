package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The player's health changed (server {@code S06PacketUpdateHealth}). Republished
 * onto the {@link net.marcloud.mcp.board.Trace} so chips can react to taking
 * damage or healing (e.g. a low-health warning HUD).
 *
 * <p><b>Tier-2, honestly typed — NOT YET WIRED.</b> This value type is the honest,
 * typed vocabulary for a health change (a single {@code float} health, never a
 * stringly-typed blob). It is deliberately shipped so the framework has the right
 * shape ready, but the mcp-core→board bridge does NOT currently publish it: the
 * bridge sees only a reference-free packet <em>summary</em>, and there is no
 * PHASE-P summarizer for {@code S06PacketUpdateHealth}, so its summary is just the
 * generic class name — the {@code health} value is not honestly available to the
 * bridge. Emitting a signal here would mean inventing a fake field, which the
 * PHASE-E contract forbids.
 *
 * <p><b>To wire it honestly:</b> add a {@code S06PacketUpdateHealth} summarizer to
 * {@code HighValueSummarizers} that emits e.g. {@code "health hp=<f> food=<i> sat=<f>"},
 * then have {@code BoardWorldEventBridge} parse {@code hp=} out of that summary
 * (exactly as it does for {@code S23} block-change today). Until then this signal
 * exists as a typed contract only.
 *
 * <p>Immutable; not cancellable. Mirrors {@link KeySignal}'s shape.
 */
public final class HealthChangeSignal extends Signal {

    private final float health;

    /**
     * @param health the player's current health (half-hearts; 0..20 in vanilla)
     */
    public HealthChangeSignal(float health) {
        this.health = health;
    }

    /** The player's current health. */
    public float health() {
        return health;
    }

    @Override
    public String toString() {
        return "HealthChangeSignal{health=" + health + "}";
    }
}
