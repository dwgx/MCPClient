package net.marcloud.mcp.core.drivers.act;

/**
 * A multi-tick state machine that steers the player: what {@link MoveApplier} drives.
 *
 * <p>Extracted when a second such machine appeared. {@link NavController} walks toward a point;
 * a route executor walks a computed plan and may place blocks on the way. The applier's job is the
 * same for both -- bind on a fresh intent, tick once per effective tick, publish the two axes,
 * funnel a terminal outcome back into the slot -- so it is written once against this interface
 * rather than copied per machine. The repo has the scar for the copied version: one block-name rule
 * reached six implementations with three different failure answers.
 *
 * <p><b>Why this lives in {@code act} and not beside the planner.</b> {@code plan} already depends on
 * {@code act} (a route executor drives an {@link ActActuator}), so an applier importing the executor
 * would close a package cycle. The interface sits on the {@code act} side and the concrete machine is
 * supplied by whoever wires the runtime -- {@code McpCore}, which can see both packages. That keeps
 * the dependency pointing one way without anybody needing to remember it does.
 */
public interface LocomotionController {

    /**
     * Advance one effective tick.
     *
     * @return a non-terminal outcome while still working, or a terminal one that ends the intent
     */
    ActOutcome tick(ActActuator act);

    /** Forward axis to force this tick (vanilla sign: +ahead / -back). */
    float forward();

    /** Strafe axis to force this tick (vanilla sign: +left / -right). */
    float strafe();

    /**
     * Jump axis to force this tick: vanilla's jump key, held for exactly the ticks it is true.
     *
     * <p>Part of the interface rather than a method on the one machine that happens to climb,
     * because the applier publishes all three axes together and a controller that cannot say
     * "jump" cannot be driven up a step: a route with a one-block rise in it dies on move one, and
     * the failure names geometry rather than the missing axis. A route executor delegates this to
     * the {@link NavController} steering the current move.
     *
     * <p><b>Not defaulted.</b> A default of false is precisely the hardcoded false this accessor
     * exists to remove, and it would come back silently: every future machine would inherit "cannot
     * jump" without saying so, which is the same defect one layer out.
     */
    boolean jump();

    /**
     * Whether the vanilla sneak key must be held while this machine walks.
     *
     * <p>Not the same question as {@link #jump()} and for the same reason it exists at all: the
     * applier publishes all four axes together, so a machine that cannot SAY a thing cannot have it
     * applied. Sneak is the one axis where the gap between "cannot say\" and \"does not ask\" is
     * fatal rather than merely slow -- {@code Entity.moveEntity:626-662} opens with
     * {@code onGround && isSneaking() && instanceof EntityPlayer} and walks the horizontal axes
     * back until the box below is clear, so a sneaking body stops at a brink. A controller that
     * cannot express it walks the body off the edge, and nothing in the outcome says so: the walk
     * reports a hazard, or reports nothing, and either way the report arrives after the fall.
     *
     * <p>It belongs on this interface rather than being read off the intent because only some
     * intents know the answer. {@link MoveIntent} is a key press and says so; a
     * {@link RouteIntent} delegates to a machine walking a computed plan, and the plan is what
     * knows the move being walked ends at a brink ({@code Move.creep()}). The applier therefore
     * ORs the two, and the two are different questions: one is what the caller asked for, the other
     * is a fact about the destination the caller could not have known.
     *
     * <p><b>Not defaulted</b>, for the reason {@link #jump()} is not.
     */
    boolean creeping();

    /**
     * The tactic chosen for the tick just run: the axes it published, the lane it took, and what
     * it gave up to take them rather than something else. Null until one has been chosen.
     *
     * <p>On the interface rather than read off a concrete controller, because the applier holds a
     * {@code LocomotionController} and an {@code instanceof} chain in front of the only consumer
     * of this is how a fourth machine would end up publishing nothing: the chain would not mention
     * it, and the walk would go on carrying a goal with no decision attached, invisibly.
     *
     * <p><b>Not defaulted</b>, for the reason {@link #jump()} is not, and the failure it prevents
     * is the same one. A default of null is not a neutral answer here, it is a silent one: the
     * applier skips stamping on a null, so a machine that published axes and forgot to say so
     * would leave the slot carrying the PREVIOUS tick's tactic -- a record that disagrees with the
     * fingers, which is the one thing worse than publishing no record at all. A machine that
     * genuinely chose nothing must now say that in its own words.
     *
     * <p>Which is exactly what a refusal does: {@code RoutePlanning}'s refusal controller returns
     * a terminal failure on its first tick without reading the world, steering, or publishing an
     * axis, so it has no tactic and returns null. That is an answer, not an absence.
     */
    MoveTactic tactic();

    /** Ticks consumed so far, for the message the applier reports on completion. */
    int ticks();

    /**
     * Ask the machine to stop cleanly at its next tick.
     *
     * <p>Cancellation goes through the machine rather than being short-circuited by the applier, so
     * the terminal message can say what was accomplished before stopping. Short-circuiting is how a
     * cancelled controller loses the only report it was ever going to make -- the same reason
     * {@code LookController}'s cancel path routes through the controller.
     */
    void requestCancel();
}
