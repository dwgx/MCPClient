package net.marcloud.mcp.core.drivers.plan;

/**
 * One step of a plan: where the feet end up, and what had to be built to get there.
 *
 * @param from      the stance stepped out of
 * @param to        the stance stepped into
 * @param kind      what sort of step this is, which decides how the executor drives it
 * @param placeCell the block that must be PLACED before the step is possible, or null
 * @param cost      search cost, in arbitrary units where one flat walk is {@link Kind#WALK}'s
 */
public record Move(Stance from, Stance to, Move.Kind kind, Stance placeCell, int cost) {

    public enum Kind {
        /** Flat step onto existing ground. */
        WALK,
        /** Step up one block; needs a jump in vanilla, since step height is 0.5. */
        STEP_UP,
        /** Controlled fall onto existing ground, bounded by {@link Stance#SAFE_DROP_MAX}. */
        DROP,
        /**
         * Place a block into the gap and step onto it.
         *
         * <p>This is the entry the whole planner exists for: bridging is not a technique the
         * planner knows, it is a MOVE the search may pick when it is cheaper than walking around.
         * That is the difference between "the AI computes what it needs" and "someone hardcoded
         * telly" -- there is no bridge routine anywhere, only a move that happens to place a block,
         * and gap crossings fall out of the search choosing it.
         */
        BRIDGE,
    }

    /** Flat walk: the cheapest thing a player can do, and the unit every other cost is read against. */
    public static final int COST_WALK = 10;

    /** Stepping up costs a jump: slower, and it interrupts the MOVE channel. */
    public static final int COST_STEP_UP = 14;

    /** Falling is fast but gives up height that may have to be re-climbed. */
    public static final int COST_DROP = 12;

    /**
     * Bridging is expensive on purpose, and the number is a POLICY not a measurement.
     *
     * <p>It must exceed a walk by enough that the search prefers any reasonable detour: a block
     * spent is gone, a placement can be refused by the server, and the player is over a void while
     * it happens. Set it too low and the planner bridges across a room it could have walked around;
     * too high and it refuses a two-block gap that has no way around. 6x a walk means the search
     * will walk up to six blocks out of its way rather than place one -- which is the behaviour a
     * caller expects when it says "get there" without saying "and build".
     *
     * <p>Deliberately NOT tuned against a measurement, because there is no measurement to tune it
     * against yet; it is a stated default, and {@code PlannerTest} pins the BEHAVIOUR it produces
     * (prefers the detour at five blocks, bridges at seven) so a future change to the number has to
     * confront what it changes rather than silently re-shaping every plan.
     */
    public static final int COST_BRIDGE = 60;

    /** Whether this move requires building something first. */
    public boolean requiresPlacement() {
        return placeCell != null;
    }

    static Move walk(Stance from, Stance to) {
        return new Move(from, to, Kind.WALK, null, COST_WALK);
    }

    static Move stepUp(Stance from, Stance to) {
        return new Move(from, to, Kind.STEP_UP, null, COST_STEP_UP);
    }

    static Move drop(Stance from, Stance to, int height) {
        // Deeper falls cost more so a plan prefers a gentle descent when one exists, but the cost
        // stays finite: NeighborGen refuses drops past SAFE_DROP_MAX outright rather than pricing
        // them, because "expensive" and "takes damage" are different facts and a cost cannot say
        // the second one.
        return new Move(from, to, Kind.DROP, null, COST_DROP + height);
    }

    static Move bridge(Stance from, Stance to, Stance placeCell) {
        return new Move(from, to, Kind.BRIDGE, placeCell, COST_BRIDGE);
    }
}
