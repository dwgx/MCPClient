package net.marcloud.mcp.core.drivers.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActIntent;
import net.marcloud.mcp.core.drivers.act.ActOutcome;
import net.marcloud.mcp.core.drivers.act.ActPhase;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.FakeActuator;
import net.marcloud.mcp.core.drivers.act.LocomotionController;
import net.marcloud.mcp.core.drivers.act.MoveApplier;
import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.SlotRecord;
import org.junit.Test;

/**
 * The wiring that makes routing reachable BY THE MODEL rather than only by a test harness.
 *
 * <p>For most of this work the planner and executor existed and worked -- verified headless and then
 * on a live client -- and the model could not touch either of them. Everything was driven by a
 * hand-run python harness, which means the capability that actually shipped was "the operator can
 * route", not "the agent can route". These assertions are about that gap: a {@link RouteIntent}
 * reaching the MOVE slot, dispatching to a route machine, and failing visibly when routing is
 * unavailable.
 *
 * <p><b>The gate is inherited, not added.</b> Routing rides the existing {@code act_set} tool, which
 * is already R1 + HIGH integrity + SE_WORLD_WRITE + CAP_WORLD_WRITE -- and placing blocks is exactly
 * what SE_WORLD_WRITE is for. No new side-table row means no chance of the drift where a new
 * capability arrives ungated because {@code SeToolRequirement.forTool} silently hands an unlisted
 * name R3/null/null/{}.
 */
public class AModelCanAskForARouteThroughActSetTest {

    private static FakeActuator grounded() {
        FakeActuator act = new FakeActuator();
        for (int x = -4; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                act.putBlock(x, 63, z);
            }
        }
        act.setPosition(0.5D, 64.0D, 0.5D);
        act.onGround = true;
        return act;
    }

    /** Records what the factory was asked for, so the dispatch can be observed rather than assumed. */
    private static final class SpyFactory
            implements java.util.function.Function<RouteIntent, LocomotionController> {
        RouteIntent seen;
        int calls;
        final StubController controller = new StubController();

        @Override
        public LocomotionController apply(RouteIntent ri) {
            seen = ri;
            calls++;
            return controller;
        }
    }

    private static final class StubController implements LocomotionController {
        int ticks;
        boolean cancelled;
        ActOutcome next = ActOutcome.running("stub steering");

        @Override public ActOutcome tick(ActActuator act) {
            ticks++;
            return next;
        }
        @Override public float forward() {
            return 0.5f;
        }
        @Override public float strafe() {
            return -0.25f;
        }
        @Override public int ticks() {
            return ticks;
        }
        @Override public void requestCancel() {
            cancelled = true;
        }
    }

    @Test
    public void aRouteIntentOccupiesTheMoveSlotLikeEveryOtherLocomotion() {
        RouteIntent ri = new RouteIntent(5, 64, 0);
        assertEquals("routing is locomotion, so it belongs in MOVE. A new slot would mean a caller "
                + "could walk and route at once, and the two would fight over the same axes",
                ActSlot.MOVE, ri.slot());
        assertTrue("and it must be part of the sealed intent family, or the runtime cannot dispatch it",
                ri instanceof ActIntent);
    }

    @Test
    public void theDefaultBudgetIsSmallSoNobodySpendsAnInventoryByAccident() {
        RouteIntent ri = new RouteIntent(5, 64, 0);
        assertEquals("a caller that did not think about its inventory must not discover it was emptied "
                + "into a bridge", RouteIntent.DEFAULT_BLOCK_BUDGET, ri.blockBudget());
        assertTrue("and the default must actually be modest", RouteIntent.DEFAULT_BLOCK_BUDGET <= 16);
    }

    @Test
    public void aZeroBudgetIsLegalAndMeansDoNotBuild() {
        RouteIntent ri = new RouteIntent(5, 64, 0, 0);
        assertEquals("zero is a meaningful request -- reach it by walking only -- and refusing it "
                + "would force a caller who wants that to avoid the tool entirely", 0, ri.blockBudget());
    }

    @Test
    public void aNegativeBudgetIsRefusedAtConstruction() {
        try {
            new RouteIntent(5, 64, 0, -1);
            throw new AssertionError("a negative budget must not be constructible: it would be "
                    + "compared against a spend count and silently forbid every placement while "
                    + "looking like a configured limit");
        } catch (IllegalArgumentException expected) {
            assertTrue("and the message must name the value: " + expected.getMessage(),
                    expected.getMessage().contains("-1"));
        }
    }

    @Test
    public void theApplierBuildsTheRouteMachineOnceAndTicksItAfterwards() {
        FakeActuator act = grounded();
        net.marcloud.mcp.core.drivers.act.ActRuntime runtime =
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE;
        SpyFactory factory = new SpyFactory();
        MoveApplier applier = new MoveApplier(act, runtime, factory);

        RouteIntent ri = new RouteIntent(5, 64, 0, 4);
        SlotRecord rec = SlotRecord.submitted(ri, 1L, 1L, "submitted");

        SlotRecord after = applier.apply(rec);
        assertEquals("the intent must be dispatched to the route factory", 1, factory.calls);
        assertSame("and the factory must receive the intent the caller submitted, budget included",
                ri, factory.seen);
        assertEquals("the machine is ticked on the same pass", 1, factory.controller.ticks);
        assertEquals("and the slot stays active while it works", ActPhase.ACTIVE, after.phase());

        // Same intent identity on the next tick: the machine must NOT be rebuilt, or it restarts from
        // scratch every tick and never makes progress -- the identity rule the look and hold channels
        // already obey.
        applier.apply(after);
        assertEquals("a second tick on the same intent must not construct a second machine",
                1, factory.calls);
        assertEquals("but it must tick the existing one", 2, factory.controller.ticks);
    }

    @Test
    public void aTerminalRouteOutcomeEndsTheSlotAndCarriesTheReason() {
        FakeActuator act = grounded();
        SpyFactory factory = new SpyFactory();
        MoveApplier applier = new MoveApplier(act,
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE, factory);

        factory.controller.next = ActOutcome.failed("out of blocks at (3,64,0)");
        SlotRecord rec = SlotRecord.submitted(new RouteIntent(5, 64, 0), 1L, 1L, "submitted");

        SlotRecord after = applier.apply(rec);
        assertEquals("a failed route must end the slot, not leave it spinning", ActPhase.FAILED,
                after.phase());
        assertTrue("and the reason must survive into the slot where act_status reads it, or the "
                + "caller sees a stopped route with no explanation: " + after.message(),
                after.message().contains("out of blocks"));
    }

    @Test
    public void withoutARouteFactoryTheIntentFailsLoudlyInsteadOfDoingNothing() {
        FakeActuator act = grounded();
        MoveApplier applier = new MoveApplier(act,
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE, null);

        SlotRecord after = applier.apply(
                SlotRecord.submitted(new RouteIntent(5, 64, 0), 1L, 1L, "submitted"));

        assertEquals("an applier that cannot route must say so", ActPhase.FAILED, after.phase());
        assertTrue("accepting the intent and silently not moving is the harder failure to diagnose -- "
                + "it looks exactly like a route that could not find its way: " + after.message(),
                after.message().contains("route factory"));
    }

    @Test
    public void navStillWorksAndIsStillDispatchedSeparately() {
        FakeActuator act = grounded();
        SpyFactory factory = new SpyFactory();
        MoveApplier applier = new MoveApplier(act,
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE, factory);

        SlotRecord after = applier.apply(SlotRecord.submitted(new NavIntent(3.5D, 64.0D, 0.5D, 40), 1L, 1L, "submitted"));

        assertEquals("adding routing must not change what a NavIntent does: it is a separate promise "
                + "(walk straight, give up if blocked) and callers depend on it",
                0, factory.calls);
        assertNotNull("and nav must still be driven", after.phase());
        assertFalse("a plain nav on open ground must not fail immediately",
                after.phase() == ActPhase.FAILED);
    }

    /**
     * The gap that made routing invisible on a live client, and cost a whole session to find.
     *
     * <p>{@code ActRuntime.moveActive()} gates the entire input override. It used to test the intent
     * TYPE against a list of two, and RouteIntent was not on it -- so a route was accepted, planned,
     * dispatched and ticked for fifty ticks while the override stayed off and the player never moved.
     * Nothing errored: every component did its job and the axes had no consumer. That is the worst
     * shape of failure this repo keeps finding, and a whitelist guarantees it recurs for the next
     * locomotion intent someone adds.
     *
     * <p>So the question is asked of the SLOT: if an intent occupies MOVE and is ACTIVE, it moves.
     */
    @Test
    public void everyIntentInTheMoveSlotDrivesTheInputNotJustAListedFew() {
        net.marcloud.mcp.core.drivers.act.ActRuntime runtime =
                net.marcloud.mcp.core.drivers.act.ActRuntime.INSTANCE;
        runtime.cancel(ActSlot.MOVE);

        runtime.submit(new RouteIntent(5, 64, 0));
        assertFalse("a freshly submitted intent is not active until its effective tick",
                runtime.moveActive());

        // Force the record ACTIVE the way the tick loop would, then ask again.
        SlotRecord active = runtime.record(ActSlot.MOVE).markActive(1L, "driving");
        runtime.store(ActSlot.MOVE, active);
        assertTrue("an ACTIVE RouteIntent in the MOVE slot must drive the input. When this answered "
                + "false the route ran to completion internally and the player stood still",
                runtime.moveActive());

        runtime.cancel(ActSlot.MOVE);
    }

    @Test
    public void planningFailureArrivesAsATerminalOutcomeNotAnExceptionOrNull() {
        // RoutePlanning must never hand back null: the failure has to travel the same path as any
        // other terminal outcome so act_status can read it. Driven with no game at all, which is the
        // most degenerate input it can get.
        LocomotionController c = RoutePlanning.executorFor(null, 1, 2, 3, 4);
        assertNotNull("a refusal is still a machine, so the reason reaches the slot", c);

        ActOutcome out = c.tick(grounded());
        assertTrue("and its first tick is terminal", out.terminal());
        assertFalse("and unsuccessful", out.ok());
        assertTrue("naming that no route was planned, rather than a generic failure: " + out.message(),
                out.message().contains("route not planned"));
        assertEquals("a refusal steers nowhere", 0f, c.forward(), 0.0001f);
        assertEquals(0f, c.strafe(), 0.0001f);
    }
}
