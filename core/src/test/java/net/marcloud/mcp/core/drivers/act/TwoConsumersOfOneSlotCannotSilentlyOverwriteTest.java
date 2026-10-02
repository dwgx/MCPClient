package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.ke.GameClock;
import org.junit.Before;
import org.junit.Test;

/**
 * Two consumers of one slot used to be a silent last-writer-wins: {@code ActRuntime.submit} wrote
 * {@code records.set(intent.slot().ordinal(), rec)} with no priority, no owner and no expiry, so the
 * first caller kept believing it held the channel and nothing anywhere said otherwise.
 *
 * <p>These tests pin the three properties that replace it. The loser is <b>refused with the holder
 * named</b>, not silently dropped. The winner <b>keeps the channel</b> — a refusal must not disturb
 * the incumbent's record, its phase, or its identity, because the whole point is that the incumbent
 * believed it held the channel and that belief must now be true.
 *
 * <p>Owner identity is a NAME ({@link ActRuntime#DIRECT_OWNER} / {@link ActRuntime#PLAN_OWNER}) and
 * priority is the two-level {@link ActRuntime.Priority}, because those are the only two consumers
 * that exist. The test uses two synthetic names so the ordering property is exercised independently
 * of which real consumer happens to hold the slot.
 */
public class TwoConsumersOfOneSlotCannotSilentlyOverwriteTest {

    private static final String OWNER_A = "consumer_a";
    private static final String OWNER_B = "consumer_b";

    private GameClock clock;
    private ActRuntime runtime;
    private ActTickLoop loop;
    private FakeActuator act;

    @Before
    public void setUp() {
        clock = new GameClock();
        clock.reset();
        runtime = new ActRuntime(clock);
        loop = new ActTickLoop(runtime);
        act = new FakeActuator();
    }

    private static ActRuntime.Lease lease(String owner, ActRuntime.Priority p, ActIntent intent) {
        return new ActRuntime.Lease(owner, p, 0L, intent);
    }

    /** The behaviour being replaced: an unconditional ordinal write, kept here as the red proof. */
    private SlotRecord oldStyleOverwrite(ActIntent intent) {
        SlotRecord r = SlotRecord.submitted(intent, clock.lastCompletedTick(),
                clock.lastCompletedTick() + 1, "old unconditional submit");
        runtime.store(intent.slot(), r);
        return r;
    }

    // ===== 1. the loser is refused and the winner keeps the channel =====

    @Test
    public void aSecondConsumerOfOneSlotIsRefusedNotSilentlyOverwritten() {
        clock.advance();
        MoveIntent first = new MoveIntent(1f, 0f, false, false, false, 400);
        ActRuntime.Granted win = (ActRuntime.Granted) runtime.trySubmit(
                first, lease(OWNER_A, ActRuntime.Priority.REPLAY, first));
        assertNotNull("premise: the first consumer holds MOVE", win);

        clock.advance();
        MoveIntent second = new MoveIntent(-1f, 0f, false, false, false, 400);
        ActRuntime.Submission lose = runtime.trySubmit(
                second, lease(OWNER_B, ActRuntime.Priority.REPLAY, second));

        assertTrue("a lower-or-equal consumer of an occupied slot must be REFUSED, and the refusal "
                        + "must be a value the caller can see, not an absent record",
                lose instanceof ActRuntime.Refused);
        ActRuntime.Refused refused = (ActRuntime.Refused) lose;
        assertEquals("the refusal must name the holder, so the loser knows WHO it lost to",
                OWNER_A, refused.heldBy());
        assertEquals(ActSlot.MOVE, refused.slot());
        assertTrue("the refusal must say what to do about it, not merely that it failed: "
                + refused.reason(), refused.reason().length() > 20);

        assertSame("the winner KEEPS the channel: the refused submit must not have touched the "
                        + "incumbent's record",
                first, runtime.record(ActSlot.MOVE).intent());
        assertSame("and the incumbent's lease is untouched too",
                OWNER_A, runtime.lease(ActSlot.MOVE).owner());
    }

    @Test
    public void theOldOrdinalWriteBehavesExactlyAsBadly() {
        // The defect, running. Kept as an executable control rather than a comment: this is what the
        // refusal above is being measured against, and a control that is never executed is not one.
        clock.advance();
        MoveIntent first = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(first, lease(OWNER_A, ActRuntime.Priority.REPLAY, first));

        clock.advance();
        MoveIntent second = new MoveIntent(-1f, 0f, false, false, false, 400);
        oldStyleOverwrite(second);

        assertSame("the old behaviour is the defect: consumer_b's intent is in the slot and "
                        + "consumer_a is not told anything happened",
                second, runtime.record(ActSlot.MOVE).intent());
        assertEquals("consumer_a still believes it holds the channel",
                OWNER_A, runtime.lease(ActSlot.MOVE).owner());
    }

    // ===== 2. auto-release: the half that makes it a lease and not a mutex =====

    @Test
    public void aLeaseWhoseOwnerNeverReleasesIsReclaimedAfterMaxIdleTicks() {
        clock.advance();
        MoveIntent abandoned = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(abandoned, lease(OWNER_A, ActRuntime.Priority.REPLAY, abandoned));

        // The owner never releases and never refreshes: the tick loop is NOT run, so nothing advances
        // the intent and nothing refreshes the lease. That is the whole failure mode -- a module that
        // submitted and then died, leaving the channel claimed forever.
        for (int i = 0; i <= ActRuntime.DEFAULT_MAX_IDLE_TICKS; i++) {
            clock.advance();
        }
        runtime.tickLeases(clock.lastCompletedTick());

        MoveIntent next = new MoveIntent(-1f, 0f, false, false, false, 400);
        ActRuntime.Submission s = runtime.trySubmit(
                next, lease(OWNER_B, ActRuntime.Priority.REPLAY, next));

        assertTrue("a lease idle for more than maxIdleTicks must be reclaimed and the channel "
                        + "usable again, or this is a mutex whose owner can forget to unlock, which "
                        + "is a hang: " + s,
                s instanceof ActRuntime.Granted);
        assertEquals("and the new holder is the caller, not the abandoned owner",
                OWNER_B, runtime.lease(ActSlot.MOVE).owner());
    }

    @Test
    public void reclaimingAStaleLeaseNeedsNoTickLoopToHaveRun() {
        // The control for the previous test. A caller must not have to wait for the game thread to
        // notice a dead owner: the acquire path runs the same expiry, or a dead module blocks the
        // channel for one more tick per caller who happens to be trying.
        clock.advance();
        MoveIntent abandoned = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(abandoned, lease(OWNER_A, ActRuntime.Priority.REPLAY, abandoned));
        assertSame("premise: the abandoned owner holds it",
                OWNER_A, runtime.lease(ActSlot.MOVE).owner());

        for (int i = 0; i <= ActRuntime.DEFAULT_MAX_IDLE_TICKS; i++) {
            clock.advance();
        }
        // Deliberately NO tickLeases call here.

        MoveIntent next = new MoveIntent(-1f, 0f, false, false, false, 400);
        assertTrue("the acquire path must reclaim a lapsed foreign lease by itself",
                runtime.trySubmit(next, lease(OWNER_B, ActRuntime.Priority.REPLAY, next))
                        instanceof ActRuntime.Granted);
    }

    @Test
    public void aLeaseThatIsBeingUsedIsNeverReclaimed() {
        // The other half of the auto-release, and the reason it is bounded rather than aggressive: a
        // live intent refreshes its lease on every applier tick, so a four-hundred-tick walk is never
        // at risk. Without this the reclaim would steal channels out from under running work, which
        // is a worse failure than the hang it fixes.
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
        clock.advance();
        MoveIntent walk = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(walk, lease(OWNER_A, ActRuntime.Priority.REPLAY, walk));

        for (int i = 0; i < ActRuntime.DEFAULT_MAX_IDLE_TICKS * 3; i++) {
            loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance()));
        }

        assertEquals("a walk that has been running for " + (ActRuntime.DEFAULT_MAX_IDLE_TICKS * 3)
                        + " ticks must still hold its channel -- the walk is still going",
                ActPhase.ACTIVE, runtime.record(ActSlot.MOVE).phase());
        assertEquals("and its lease must have survived the whole run",
                OWNER_A, runtime.lease(ActSlot.MOVE).owner());

        MoveIntent intruder = new MoveIntent(-1f, 0f, false, false, false, 400);
        assertTrue("a consumer that never advanced cannot be waiting behind a live walk: it must be "
                        + "refused, not handed a channel somebody is using",
                runtime.trySubmit(intruder, lease(OWNER_B, ActRuntime.Priority.REPLAY, intruder))
                        instanceof ActRuntime.Refused);
    }

    @Test
    public void aLeaseIsReleasedTheMomentItsIntentFinishesRatherThanWaitingOutTheClock() {
        runtime.registerApplier(ActSlot.LOOK, new LookApplier(act));
        clock.advance();
        LookIntent aim = LookIntent.set(30f, 0f, 0f);
        runtime.trySubmit(aim, lease(OWNER_A, ActRuntime.Priority.REPLAY, aim));

        for (int i = 0; i < 8 && !runtime.record(ActSlot.LOOK).phase().isTerminal(); i++) {
            loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance()));
        }
        assertTrue("premise: the aim finished", runtime.record(ActSlot.LOOK).phase().isTerminal());

        runtime.tickLeases(clock.lastCompletedTick());
        assertNull("a finished intent releases its channel at once: waiting out a 40-tick timer to "
                        + "hand back something nobody is using would make every plan step pay two "
                        + "seconds for the previous one",
                runtime.lease(ActSlot.LOOK));
    }

    // ===== 3. same owner, and the priority ordering =====

    @Test
    public void theSameOwnerMayReAcquireItsOwnLiveSlot() {
        // The reference allows this (canAcquire admits `current.owner === owner`), and it is what
        // makes a multi-slot plan step possible at all: the step re-asserts each of its claims, and
        // an owner that could not re-acquire its own channel would deadlock on its second slot.
        clock.advance();
        MoveIntent first = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(first, lease(OWNER_A, ActRuntime.Priority.REPLAY, first));

        clock.advance();
        MoveIntent again = new MoveIntent(1f, 0.5f, false, false, false, 400);
        ActRuntime.Submission s = runtime.trySubmit(
                again, lease(OWNER_A, ActRuntime.Priority.REPLAY, again));

        assertTrue("same owner re-acquire must be admitted at equal priority: " + s,
                s instanceof ActRuntime.Granted);
        assertSame("and it takes its own channel", again, runtime.record(ActSlot.MOVE).intent());
    }

    @Test
    public void aStrictlyHigherPriorityConsumerIsAdmittedAndAStrictlyLowerOneIsNot() {
        // The ordering, both directions. REPLAY is what a plan step is and DIRECT is what an act_set
        // is; the honest fact behind it is not a tunable rank but that a command the model is issuing
        // now beats a step it authorised earlier.
        clock.advance();
        MoveIntent byModel = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.submit(byModel);
        assertEquals("premise: a live act_set holds MOVE",
                ActRuntime.DIRECT_OWNER, runtime.lease(ActSlot.MOVE).owner());

        clock.advance();
        MoveIntent fromPlan = new MoveIntent(-1f, 0f, false, false, false, 400);
        assertTrue("a replayed plan step must not take a slot from a live act_set: the step was "
                        + "authorised earlier and the command is what the player means now",
                runtime.trySubmit(fromPlan, ActRuntime.replayLease(fromPlan))
                        instanceof ActRuntime.Refused);
        assertSame("and the model's command is untouched",
                byModel, runtime.record(ActSlot.MOVE).intent());

        runtime.cancel(ActSlot.MOVE);
        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance()));
        clock.advance();
        MoveIntent planned = new MoveIntent(-1f, 0f, false, false, false, 400);
        assertTrue("premise: the plan's step is now admitted to a free slot",
                runtime.trySubmit(planned, ActRuntime.replayLease(planned))
                        instanceof ActRuntime.Granted);

        clock.advance();
        MoveIntent fresh = new MoveIntent(0f, 1f, false, false, false, 400);
        assertTrue("DIRECT must take a slot from REPLAY: the model's live command is the one it "
                        + "meant",
                runtime.trySubmit(fresh, ActRuntime.directLease(fresh))
                        instanceof ActRuntime.Granted);
        assertSame("and it is the DIRECT intent in the slot",
                fresh, runtime.record(ActSlot.MOVE).intent());
    }

    @Test
    public void anEqualPriorityConsumerIsRefusedSoTiesCannotBeLastWriterWins() {
        // The reason this is not a fake priority number. With equal priority admitted -- which is what
        // the reference's `priority >= current.priority` does -- two identical consumers would go back
        // to silent last-writer-wins and the lease would be decoration. Only the OUTRANKING direction
        // admits; the equal direction is the defect.
        clock.advance();
        MoveIntent first = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(first, lease(OWNER_A, ActRuntime.Priority.DIRECT, first));

        clock.advance();
        MoveIntent second = new MoveIntent(-1f, 0f, false, false, false, 400);
        assertTrue("equal priority from a DIFFERENT owner must be refused, or the whole mechanism "
                        + "is a mutex that always times out favourably for whoever wrote last",
                runtime.trySubmit(second, lease(OWNER_B, ActRuntime.Priority.DIRECT, second))
                        instanceof ActRuntime.Refused);
        assertSame(first, runtime.record(ActSlot.MOVE).intent());
    }

    // ===== the lease is per-slot, not global =====

    @Test
    public void aLeaseOnOneSlotDoesNotBlockAnother() {
        // The whole reason leases are per-slot and not a runtime-wide lock. The player can still walk
        // while mining while a look is slewed, exactly as the slot javadoc claims.
        clock.advance();
        MoveIntent move = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.trySubmit(move, lease(OWNER_A, ActRuntime.Priority.REPLAY, move));

        clock.advance();
        InteractIntent dig = InteractIntent.dig(1, 2, 3, 1);
        assertTrue("MOVE and INTERACT are orthogonal channels: holding one must say nothing about "
                        + "the other",
                runtime.trySubmit(dig, lease(OWNER_B, ActRuntime.Priority.REPLAY, dig))
                        instanceof ActRuntime.Granted);
        assertEquals(OWNER_A, runtime.lease(ActSlot.MOVE).owner());
        assertEquals(OWNER_B, runtime.lease(ActSlot.INTERACT).owner());
    }

    @Test
    public void cancellingReleasesTheChannelAndStillLetsTheTeardownTickRun() {
        // This assertion MOVED. It used to require the lease to SURVIVE the cancel flag, on the
        // theory that holding it protected the applier's teardown tick. That theory was false: a
        // DIRECT submit outranks and replaces the record outright, taking the cancelRequested flag
        // with it, so the lease never protected the teardown from the case that mattered -- it only
        // left act_status reporting a holder for a channel nobody was using. The PROPERTY being
        // pinned is unchanged and is the one that was always real: the abort still reaches the game.
        // What is now asserted is that releasing the lease does not cost the teardown.
        FakeActuator cancelAct = new FakeActuator();
        runtime.registerApplier(ActSlot.INTERACT, new InteractApplier(cancelAct));
        clock.advance();
        runtime.submitInteract(InteractIntent.holdUntilDone());
        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance()));
        assertTrue("premise: the hold is live and the key is asserted",
                cancelAct.useKeyDown);

        runtime.cancel(ActSlot.INTERACT);
        assertNull("a cancel releases the channel at once: the owner has said it is done, and a "
                        + "lease still naming it would have a model reading act_status conclude the "
                        + "channel was taken when it was free",
                runtime.lease(ActSlot.INTERACT));

        loop.onTick(new net.marcloud.mcp.core.ke.event.events.TickEvent(clock.advance()));
        assertFalse("and the teardown STILL runs after the lease is gone, because it is driven by "
                        + "the cancelRequested flag on the record and not by the lease: a held "
                        + "release IS the shot for a bow, so a skipped teardown is not cosmetic",
                cancelAct.useKeyDown);
        assertEquals(ActPhase.CANCELLED, runtime.record(ActSlot.INTERACT).phase());
        assertNull("and the channel stays free afterwards", runtime.lease(ActSlot.INTERACT));
    }

    @Test
    public void aSubmittedRecordStillCarriesNoLeaseFields() {
        // The lease is runtime bookkeeping and stays out of the record the appliers and the status
        // tool read. If this ever fails, somebody has started pushing lease state into the data half
        // of the system, and the identity-based freshness checks in the appliers start seeing two
        // different objects for one submit.
        clock.advance();
        MoveIntent move = new MoveIntent(1f, 0f, false, false, false, 0);
        ActRuntime.Granted g = (ActRuntime.Granted) runtime.trySubmit(
                move, ActRuntime.directLease(move));
        assertFalse("the record must not expose the lease owner",
                g.record().toString().contains(ActRuntime.DIRECT_OWNER));
        assertEquals("and a submitted record is still exactly the record submit used to produce",
                ActPhase.IDLE, g.record().phase());
        assertEquals(clock.lastCompletedTick(), g.record().submittedTick());
        assertEquals(clock.lastCompletedTick() + 1, g.record().effectiveTick());
    }

    @Test
    public void theDirectConvenienceSubmitIsNeverRefusedAndThatIsACallerProperty() {
        // Every existing caller in the tree goes through submit(). It must keep working for all of
        // them, and the reason it cannot be refused is the priority ORDER, not a special case in the
        // method -- so this asserts both the behaviour and the ordering that produces it.
        clock.advance();
        runtime.submitMove(new MoveIntent(1f, 0f, false, false, false, 400));
        assertEquals(ActRuntime.DIRECT_OWNER, runtime.lease(ActSlot.MOVE).owner());

        clock.advance();
        runtime.submitMove(new MoveIntent(-1f, 0f, false, false, false, 400));
        assertEquals("a second act_set on the same slot is a same-owner re-acquire and wins",
                ActRuntime.DIRECT_OWNER, runtime.lease(ActSlot.MOVE).owner());

        assertTrue("and DIRECT must outrank REPLAY, which is the only reason the above is safe",
                ActRuntime.Priority.DIRECT.outranks(ActRuntime.Priority.REPLAY));
        assertFalse(ActRuntime.Priority.REPLAY.outranks(ActRuntime.Priority.DIRECT));
    }

    @Test
    public void anActPlanStepIsRefusedByALiveActSetAndSaysSoInsteadOfOverwritingIt() {
        // The live path this whole mechanism exists for, end to end through the real interpreter.
        runtime.registerApplier(ActSlot.MOVE, new MoveApplier(act, runtime));
        clock.advance();
        MoveIntent modelWalk = new MoveIntent(1f, 0f, false, false, false, 400);
        runtime.submit(modelWalk);

        clock.advance();
        ActPlan plan = ActPlan.parse(java.util.List.of(
                Map.of("move", Map.of("walk_straight",
                        Map.of("x", -40.0, "y", 64.0, "z", 0.0)))));
        runtime.submitPlan(plan);

        ActPlanStatus st = runtime.planStatus();
        assertEquals("a plan step blocked by a live act_set is WAITING, not running and not failed",
                ActPlanStatus.Phase.RUNNING, st.phase());
        assertTrue("and it must name the slot it is waiting on: " + st.waitingOn(),
                st.waitingOn().contains("move"));
        assertTrue("the message must name the holder so the block is diagnosable: " + st.message(),
                st.message().contains(ActRuntime.DIRECT_OWNER));
        assertSame("and above all the model's own walk is untouched -- this is the assertion the old "
                        + "code could not have made",
                modelWalk, runtime.record(ActSlot.MOVE).intent());
    }
}