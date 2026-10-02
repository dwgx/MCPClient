package net.marcloud.mcp.core.drivers.act;

/**
 * The one question a wedged body needs answered before it steps sideways: <b>may a body legally
 * STAND in this cell?</b>
 *
 * <p><b>Why this is a seam and not a copy of the rule.</b> {@code Stance.isStandable} is the
 * single authority on what a legal position is -- it owns the floor, the headroom and the water
 * rule -- and {@code Stance}'s own javadoc says the search, the cost function and the executor
 * must all route through it precisely so they cannot develop separate opinions. A recovery that
 * re-derived standability from a block NAME would be the second opinion this repo has paid for six
 * times (one name rule, six implementations, three different failure answers), and it would be the
 * worst possible one to fork: it would answer "can I stand here" differently from the planner that
 * decided the route in the first place, so the agent could step onto a cell the plan had already
 * refused and call it progress.
 *
 * <p>So the answer travels in as a lambda built by the plan layer, which is the only layer that
 * imports {@code BlockView}. The dependency runs one way: the action layer asks, the plan layer
 * answers. Nothing here knows what a {@code BlockView} is.
 *
 * <p>A null reference means "no world view was supplied", which is a statement about the CALLER's
 * wiring and never about terrain -- the controller treats it as "nowhere to step to" and reports
 * the jam, rather than inventing a standability verdict from nothing.
 */
@FunctionalInterface
public interface Standable {

    /**
     * Whether a body fits in the cell at these block coordinates with something solid under it.
     *
     * @see net.marcloud.mcp.core.drivers.plan.Stance#isStandable(net.marcloud.mcp.core.drivers.plan.BlockView)
     */
    boolean standableAt(int x, int y, int z);
}