package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The expansion ceiling must bound a HOPELESS search, and must never be what stops a short one.
 *
 * <p>{@code MAX_EXPANSIONS} was raised from 20,000 to 400,000 on the strength of a live message:
 * a route between two stance cells one block apart had been refused with "search hit its
 * 20000-state ceiling". That message is the claim under test here, because on its face it does not
 * hold. A goal one step away is found by expanding the start and its neighbours -- a handful of
 * states. For the search to reach 20,000 the goal cannot have been one step away; the frontier has
 * to have been spreading over everything the player can stand on, which is what an UNREACHABLE
 * goal looks like.
 *
 * <p>So the two cases are different problems and the fix must not be the same for both:
 *
 * <ul>
 *   <li>a reachable goal a few steps away must succeed, and do so cheaply;</li>
 *   <li>an unreachable goal must be refused, and it may legitimately cost a full sweep -- raising
 *       the ceiling cannot make that goal appear, it only makes the refusal twenty times slower.
 *       A caller that raises the budget to escape a "ceiling" message is spending time on a
 *       diagnosis the message already gave away.</li>
 * </ul>
 *
 * <p>This test pins the first and measures the second, so the constant cannot be raised again to
 * paper over an unreachable goal. It fails on a build where a one-block walk is refused.
 */
public final class TheSearchCeilingDoesNotFireOnAShortGoalTest {

    /** Open ground, a single flat slab to stand on: the cheapest possible world. */
    private static FakeWorld flat(int radius) {
        return new FakeWorld(0).floor(-radius, radius, 63, -radius, radius);
    }

    @Test
    public void aOneBlockWalkIsFoundWithoutTouchingTheCeiling() {
        FakeWorld w = flat(4);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(1, 64, 0));

        assertTrue("one step across flat open ground must be routable: " + plan.failure(), plan.found());
        assertEquals("and it must be exactly the one step asked for",
                1, plan.moves().size());
    }

    /**
     * A goal four blocks away in the same open world. Cheap, and it must stay cheap: if a search
     * over open ground can be made to run away, the ceiling is not the only thing wrong.
     */
    @Test
    public void aShortWalkAcrossOpenGroundCostsAFractionOfTheCeiling() {
        FakeWorld w = flat(16);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(4, 64, 0));

        assertTrue("four steps across open ground must be routable: " + plan.failure(), plan.found());
        assertEquals("and in four moves", 4, plan.moves().size());
        assertTrue("a search that expands " + plan.expansions()
                + " states to walk four steps is not searching, it is enumerating the world; the "
                + "ceiling is the thing hiding that, not fixing it",
                plan.expansions() < 500);
    }

    /**
     * An unreachable goal is refused, and the refusal names WHY rather than blaming a ceiling.
     *
     * <p>The point is not the expansion count -- a genuinely unreachable goal may sweep the whole
     * reachable space, which is what the ceiling exists to bound. The point is the message: it must
     * say the goal could not be reached, so a caller does not read "hit its ceiling" as "needs a
     * bigger budget".
     */
    @Test
    public void anUnreachableGoalIsRefusedForItsOwnReason() {
        // A sealed box: the goal is inside a roofed, walled cell the player cannot enter, so no
        // route exists at ANY budget. An earlier version of this test used a two-high wall on an
        // open slab and then did `if (plan.found()) { return; }` -- which means the day the
        // planner grew the ability to route around a wall, this test would have started passing
        // while asserting nothing at all. A green test that stops testing is worse than a red
        // one, and the escape hatch is what produced it.
        FakeWorld w = new FakeWorld(0)
                .floor(-12, 12, 63, -12, 12)
                .floor(6, 6, 64, 6, 6).floor(6, 6, 65, 6, 6).floor(6, 6, 66, 6, 6)
                .floor(4, 5, 64, 6, 6).floor(4, 5, 65, 6, 6).floor(4, 5, 66, 6, 6)
                .floor(7, 8, 64, 6, 6).floor(7, 8, 65, 6, 6).floor(7, 8, 66, 6, 6)
                .floor(6, 6, 64, 4, 5).floor(6, 6, 65, 4, 5).floor(6, 6, 66, 4, 5)
                .floor(6, 6, 64, 7, 8).floor(6, 6, 65, 7, 8).floor(6, 6, 66, 7, 8);
        Planner.Plan plan = new Planner(w).plan(new Stance(0, 64, 0), new Stance(6, 64, 6));

        assertTrue("premise: a fully sealed cell has no route at any budget, and this test must "
                + "NOT quietly pass by returning early if that ever stops being true -- the "
                        + "planner found one: " + plan.moves(), !plan.found());
        String status = plan.failure();
        assertTrue("a refusal must not blame the ceiling when the goal is simply out of reach -- "
                + "that message is what invites somebody to raise MAX_EXPANSIONS instead of "
                + "looking at the world: " + status,
                !status.contains("ceiling") || status.contains("not among"));
    }
}
