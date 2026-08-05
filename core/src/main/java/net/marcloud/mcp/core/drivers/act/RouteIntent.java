package net.marcloud.mcp.core.drivers.act;

/**
 * MOVE-slot intent: "get me to this block, working out for yourself what that takes."
 *
 * <p>The difference from {@link NavIntent} is what the two promise. NavIntent says "walk toward this
 * point in a straight line", which is all {@code NavController} attempts and all it claims. A
 * RouteIntent says "reach this block", and reaching it may require a route around obstacles or
 * placing blocks into a gap -- the planner decides which, and the caller does not have to know the
 * terrain to ask.
 *
 * <p>It lives in the MOVE slot and dispatches by type, exactly like NavIntent, so nothing about the
 * existing three-channel model changes: this is one more shape of locomotion, not a new concept
 * competing with the slots. Placement travels through the actuator directly the way
 * {@code DigController} already does, so the INTERACT slot is untouched and stays available.
 *
 * @param targetX      destination block X (block coordinates, not centres)
 * @param targetY      destination block Y -- the block the FEET should end in
 * @param targetZ      destination block Z
 * @param blockBudget  how many blocks the route may place; 0 forbids building entirely
 */
public record RouteIntent(int targetX, int targetY, int targetZ, int blockBudget)
        implements ActIntent {

    /**
     * Default ceiling on blocks a route may spend when the caller does not say.
     *
     * <p>Small on purpose. A caller that has not thought about its inventory should not discover it
     * has been emptied into a bridge, and a route needing more than this is usually a route worth
     * looking at before running. Callers that mean it pass their own number.
     */
    public static final int DEFAULT_BLOCK_BUDGET = 8;

    public RouteIntent(int targetX, int targetY, int targetZ) {
        this(targetX, targetY, targetZ, DEFAULT_BLOCK_BUDGET);
    }

    public RouteIntent {
        if (blockBudget < 0) {
            throw new IllegalArgumentException("blockBudget must not be negative: " + blockBudget);
        }
    }

    @Override
    public ActSlot slot() {
        return ActSlot.MOVE;
    }

    /** Human-readable form for {@code act_status}, so a caller can read back what it asked. */
    public String describe() {
        return "route to (" + targetX + "," + targetY + "," + targetZ + ") spending at most "
                + blockBudget + " block(s)";
    }
}
