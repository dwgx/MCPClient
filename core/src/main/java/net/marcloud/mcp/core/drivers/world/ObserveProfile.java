package net.marcloud.mcp.core.drivers.world;

/**
 * PHASE W.9 — observation token-budget profiles. Each profile presets the knobs the
 * {@link WorldViewCapture} sampler consumes so a caller can trade breadth for cost
 * without post-hoc truncation (budget is enforced structurally: caps + which
 * sections emit). Explicit tool params (radius / sections) override these defaults.
 *
 * <ul>
 *   <li>{@code SPARSE} — cheapest heartbeat: self + crosshair target + a few nearest
 *       entities, tiny feet-layer grid. Roughly old {@code scan_surroundings} cost.</li>
 *   <li>{@code EXPLORE} — full geometry (RLE columns) + inventory + env; navigation/building.</li>
 *   <li>{@code COMBAT} — wide entity net with hp, feet-layer grid only.</li>
 * </ul>
 */
public enum ObserveProfile {

    SPARSE(4, 1, 1, false, 5, 1.0, false),
    EXPLORE(8, 3, 4, true, 12, 2.0, true),
    COMBAT(6, 2, 2, false, 24, 3.0, true);

    /** Default grid half-radius (blocks). */
    public final int gridRadius;
    /** Vertical window below the feet layer for column sampling. */
    public final int vBelow;
    /** Vertical window above the feet layer for column sampling. */
    public final int vAbove;
    /** Emit the full run-length vertical profile per column (false = surface+feet+head only). */
    public final boolean emitProfile;
    /** Max entities returned (after distance sort). */
    public final int maxEntities;
    /** Entity scan range multiplier (× gridRadius). */
    public final double entityRangeMul;
    /** Include living-entity hp/name. */
    public final boolean entityHp;

    ObserveProfile(int gridRadius, int vBelow, int vAbove, boolean emitProfile,
                   int maxEntities, double entityRangeMul, boolean entityHp) {
        this.gridRadius = gridRadius;
        this.vBelow = vBelow;
        this.vAbove = vAbove;
        this.emitProfile = emitProfile;
        this.maxEntities = maxEntities;
        this.entityRangeMul = entityRangeMul;
        this.entityHp = entityHp;
    }

    /** Parse a profile name, defaulting to EXPLORE on null/unknown. */
    public static ObserveProfile parse(String s) {
        if (s == null) {
            return EXPLORE;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EXPLORE;
        }
    }
}
