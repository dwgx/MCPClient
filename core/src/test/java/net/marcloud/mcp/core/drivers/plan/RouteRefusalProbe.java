package net.marcloud.mcp.core.drivers.plan;

import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;

/**
 * A headless door onto {@link RoutePlanning}'s graded refusal, for tests in another package.
 *
 * <p>{@code RoutePlanning.refusalFor} is package-private because it exists to make an existing
 * capability observable rather than to add one, and widening it to public for the sake of a test in
 * another package would make a test seam part of the production API. This class is the narrower
 * answer: it lives beside the method it exposes and adds nothing but the two shapes the act scenario
 * set needs.
 *
 * <p>Both refusals go through the REAL {@code RoutePlanning.noRouteMessage} mapping rather than a
 * hand-built {@code Graded}, so a test that reads a grade here is reading the mapping production uses
 * -- which is the only way the count beside it can be asserted to travel the same journey.
 */
public final class RouteRefusalProbe {

    private RouteRefusalProbe() {
    }

    /** A refusal over terrain the search could not read: UNKNOWN, with the count beside it. */
    public static LocomotionController overUnreadTerrain(int unreadCells) {
        return RoutePlanning.refusalFor(
                RoutePlanning.noRouteMessage(
                        new Stance(0, 64, 0), new Stance(8, 64, 0), "CLIENT world", "search exhausted",
                        unreadCells),
                unreadCells);
    }

    /**
     * A refusal over terrain every cell of which was readable.
     *
     * <p>Built through the mapping rather than asserted, so this is OBSERVED by construction and the
     * test is checking that production reaches that conclusion -- not manufacturing it.
     */
    public static LocomotionController overFullyReadTerrain() {
        return overUnreadTerrain(0);
    }

    /** The grade {@link #overUnreadTerrain} produces, exposed for a pure mapping assertion. */
    public static Belief gradeFor(int unreadCells) {
        return RoutePlanning.noRouteMessage(new Stance(0, 64, 0), new Stance(8, 64, 0),
                "CLIENT world", "search exhausted", unreadCells).belief();
    }
}
