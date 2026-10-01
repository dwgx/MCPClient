package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The player's health changed (server {@code S06PacketUpdateHealth}). Republished
 * onto the {@link net.marcloud.mcp.board.Trace} so chips can react to taking
 * damage or healing (e.g. a low-health warning HUD).
 *
 * <p><b>Tier-2, honestly typed — WIRED.</b> The mcp-core→board bridge publishes this
 * from inbound {@code S06PacketUpdateHealth}: the health summarizer emits an
 * {@code hp=<f>} field and {@code BoardWorldEventBridge} parses it. If the field is
 * absent the bridge emits nothing rather than guessing, so this signal never carries
 * an invented value.
 *
 * <p>That is exactly the work the earlier version of this comment said was still to
 * do ("add a summarizer, then parse {@code hp=}"). It is done; the comment outlived
 * it, which is the drift this repository keeps paying for. See
 * {@code BoardWorldEventBridge}'s class javadoc for the full wiring table — that is
 * the authority, not this paragraph.
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
