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
 * <p><b>{@code creeping} is a belt-and-braces flag, and the route does most of the work itself.</b>
 * {@code Move.creep()} is a fact the planner reads off the world while generating: a move whose
 * destination is a brink is walked with the sneak key held without anyone asking. That is the half
 * that matters, because a caller cannot know which block of a thirty-block route is the one that
 * will kill it -- and the ledge guard that saves them ({@code Entity.moveEntity:626}) is a
 * client-side stop, not something a server has to agree to. This field is for the other case,
 * "creep all of it", which the planner has no business inferring: {@code Stance.isBrink} answers
 * for the cells it generated moves into, and a plan is only generated into cells someone read.
 *
 * <p><b>Every other field here is a GOAL, and that was the whole of what this record carried.</b>
 * Where the feet go, how much inventory the route may spend, whether to hold the sneak key -- all of
 * it is what the caller wants, and none of it is what anybody DID. The tactic the walk actually
 * chose -- the keys, the lane, whether to jump, and what was given up to take it rather than
 * something else -- lived inside {@code NavController} as private fields, so nothing downstream could
 * read a decision it was being asked to carry out. {@code tactic} is that decision, and it is
 * {@code null} until one has been made: an intent a caller has just written has been ASKED for
 * something, not yet told how it will be done, and a record that invented a tactic at construction
 * time would be claiming a decision nobody made.
 *
 * @param targetX      destination block X (block coordinates, not centres)
 * @param targetY      destination block Y -- the block the FEET should end in
 * @param targetZ      destination block Z
 * @param blockBudget  how many blocks the route may place; 0 forbids building entirely
 * @param creeping     hold the sneak key for EVERY move, not only the ones ending at an edge. Off
 *                     by default, and it is a caller choice because a creep is roughly a third of
 *                     walking speed
 * @param tactic       the tactic the controller chose, or null while none has been chosen. Set with
 *                     {@link #withTactic(MoveTactic)}, which returns a new intent and leaves this
 *                     one alone, so an intent a caller submitted cannot be edited underneath it
 */
public record RouteIntent(int targetX, int targetY, int targetZ, int blockBudget, boolean creeping,
                          MoveTactic tactic)
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
        this(targetX, targetY, targetZ, DEFAULT_BLOCK_BUDGET, false, null);
    }

    /** The four-argument form: a route that does what it always did, and creeps nothing extra. */
    public RouteIntent(int targetX, int targetY, int targetZ, int blockBudget) {
        this(targetX, targetY, targetZ, blockBudget, false, null);
    }

    /**
     * The five-argument form: a route with a caller-chosen gait and no tactic chosen yet.
     *
     * <p>The same meaning it has always had. Nothing about a route changes by naming it: a caller
     * that has not seen a walk happen cannot have a tactic to report, and inventing one here would
     * be the one thing worse than not reporting it at all.
     */
    public RouteIntent(int targetX, int targetY, int targetZ, int blockBudget, boolean creeping) {
        this(targetX, targetY, targetZ, blockBudget, creeping, null);
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

    /**
     * This intent carrying the tactic that was chosen for it.
     *
     * <p>A copy rather than a setter, because an intent is data and data that a controller can
     * mutate after a caller has read it is how a caller's idea of what it asked stops matching what
     * it asked. Everything except the tactic is carried over byte for byte, so the goal a caller
     * named is the goal the walk is still going to.
     *
     * <p>Called once per TICK, by {@code MoveApplier.driveLocomotion}, and the granularity is the
     * record's own contract rather than a matter of taste: {@link MoveTactic}'s axes are the axes
     * the controller published on the same tick, so a stamp refreshed less often than that is a
     * record disagreeing with the fingers. An earlier version of this javadoc said "once per
     * decision point, not once per tick" to avoid a per-tick allocation, and the allocation was
     * bought by shipping a stale decision instead.
     */
    public RouteIntent withTactic(MoveTactic chosen) {
        return new RouteIntent(targetX, targetY, targetZ, blockBudget, creeping, chosen);
    }

    /**
     * Human-readable form for {@code act_status}, so a caller can read back what it asked.
     *
     * <p>The creep is named only when it is on. A caller reading this back to check what it asked
     * for cannot otherwise tell "did not ask" from "asked, and it was quietly dropped" -- and a
     * silently discarded creep is the exact promise the field was added to stop the product from
     * failing to keep.
     *
     * <p>The tactic is named only when there is one, for the same reason and a sharper one: an
     * intent nobody has walked yet has no tactic, and rendering an empty slot for it would make
     * "not chosen" look like "chosen, and it was nothing".
     */
    public String describe() {
        return "route to (" + targetX + "," + targetY + "," + targetZ + ") spending at most "
                + blockBudget + " block(s)" + (creeping ? ", creeping the whole way" : "")
                + (tactic == null ? "" : ", chosen tactic: " + tactic.describe());
    }
}