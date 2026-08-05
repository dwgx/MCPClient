package net.marcloud.mcp.core.util;

import static net.marcloud.mcp.core.util.BlockProbe.Solidity.AIR;
import static net.marcloud.mcp.core.util.BlockProbe.Solidity.SOLID;
import static net.marcloud.mcp.core.util.BlockProbe.Solidity.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for the one rule that keeps an unreadable position from being reported as terrain.
 *
 * <p>In 1.8.9 {@code World.getBlockState} answers AIR for a position it cannot see -- an
 * {@code isValid} failure returns air (World.java:850-861,240-243) and an unloaded chunk resolves to
 * an {@code EmptyChunk} whose lookup finds null storage and also answers air
 * (ChunkProviderClient.java:86). So the vanilla API cannot distinguish "empty" from "unread", and
 * measured 2026-08-05, all SEVEN files in core that read block state did so without asking whether
 * the chunk was loaded.
 *
 * <p>This repo has fixed that conflation four times already -- the {@code "unknown"} block-name
 * sentinel, {@code effects}, {@code entities.left}, {@code self.air} -- and every one of those fixes
 * was at the REPORTING layer, stopping the lie from reaching the model rather than from entering.
 * {@link BlockProbe} is the reading layer, so these assertions are about the entry point.
 *
 * <p><b>Why the decision is tested and not {@code at(World, ...)}.</b> {@code World} is abstract and
 * its constructor wants a save handler, world info, a provider and a profiler, so no headless test
 * can build one. The ORDERING is what matters here -- chunk-loaded must be asked before the state
 * read, because reading first has already manufactured the indistinguishable air -- and
 * {@link BlockProbe#decide} exposes exactly that as a pure function. What remains unverified headless
 * is the wiring from a real world into those four booleans; that needs a live client and is called
 * out in the plan rather than assumed.
 */
public class BlockProbeSeparatesUnreadFromAirTest {

    @Test
    public void anUnloadedChunkIsUnknownAndNotAir() {
        assertEquals("an unloaded chunk must NOT answer AIR. Vanilla does, and that is the whole "
                + "defect: a planner reading past the loaded edge sees a void where terrain is, and "
                + "either routes through solid ground or spends its inventory bridging across it",
                UNKNOWN, BlockProbe.decide(false, true, true, false));
    }

    @Test
    public void anUnloadedChunkStaysUnknownEvenWhenTheBlockWouldHaveBeenSolid() {
        assertEquals("the loaded flag decides first. If a later fact could override it, the ordering "
                + "would be decorative -- and the ordering is the only thing separating the two "
                + "kinds of air",
                UNKNOWN, BlockProbe.decide(false, true, false, true));
    }

    @Test
    public void aFailedStateReadIsUnknownNotAir() {
        assertEquals("a null state means the read failed; calling that air invents a hole, and "
                + "calling it solid invents a floor. The reachable cause is ReportedException, which "
                + "Chunk.getBlockState throws by wrapping storage corruption (Chunk.java:606-635)",
                UNKNOWN, BlockProbe.decide(true, false, false, true));
    }

    @Test
    public void realAirIsAir() {
        assertEquals("observed emptiness is a fact and must stay usable: if this collapsed into "
                + "UNKNOWN the planner could never route anywhere",
                AIR, BlockProbe.decide(true, true, true, false));
    }

    @Test
    public void aBlockWithACollisionBoxIsSolid() {
        assertEquals("a collision body is what holds a player up",
                SOLID, BlockProbe.decide(true, true, false, true));
    }

    @Test
    public void aBlockWithoutACollisionBoxIsNotAFloor() {
        assertEquals("vines, ladders, torches, tall grass, rails and signs are all non-air with no "
                + "collision box. Treating them as floor is how a route walks into a plant and "
                + "falls -- and it is the same rule LocalGrid.standable already uses, deliberately",
                AIR, BlockProbe.decide(true, true, false, false));
    }

    /**
     * The trap that makes this rule necessary rather than tidy: {@code Block.isFullCube()} returns
     * true unconditionally in 1.8.9 and {@code BlockAir} does not override it. A solidity test built
     * on it reports air as solid and therefore cannot fail -- this repo shipped a floor check with
     * exactly that bug and it reported {@code bad=0} while the player stood over a pit
     * (handoff-2026-08-06 section 3(1)).
     */
    @Test
    public void solidityFollowsTheCollisionBoxAndNothingElse() {
        assertEquals("air with a (hypothetically) true isFullCube must still be AIR: material is "
                + "checked before any cube question, so the vanilla trap cannot reach the answer",
                AIR, BlockProbe.decide(true, true, true, true));
    }

    @Test
    public void unknownIsNeitherFloorNorSpaceSoCallersAreSafeByDefault() {
        assertFalse("UNKNOWN must not hold a player up", UNKNOWN.holdsPlayerUp());
        assertFalse("and must not count as space to move into", UNKNOWN.isEmptySpace());
        assertFalse("and must announce that it is not about the world", UNKNOWN.wasRead());
    }

    @Test
    public void airAndSolidEachAnswerExactlyOneOfTheTwoQuestions() {
        assertTrue("SOLID is a floor", SOLID.holdsPlayerUp());
        assertFalse("SOLID is not space", SOLID.isEmptySpace());
        assertTrue("AIR is space", AIR.isEmptySpace());
        assertFalse("AIR is not a floor", AIR.holdsPlayerUp());
        assertTrue("both were read", SOLID.wasRead() && AIR.wasRead());
    }

    /**
     * Both predicates answering false for UNKNOWN is the design, not an accident: it lets a caller
     * ask only "floor?" and "space?" and still behave safely without knowing a third state exists.
     * A caller that needs to tell "no route" from "no information" asks {@code wasRead()}.
     */
    @Test
    public void everyStateIsCoveredByTheTwoDerivedQuestions() {
        for (BlockProbe.Solidity s : BlockProbe.Solidity.values()) {
            assertFalse("no state may be both a floor and empty space: " + s,
                    s.holdsPlayerUp() && s.isEmptySpace());
            assertEquals("exactly the read states answer one of the two questions: " + s,
                    s.wasRead(), s.holdsPlayerUp() || s.isEmptySpace());
        }
    }
}
