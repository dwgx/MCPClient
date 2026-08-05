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
