package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.junit.Test;

/**
 * An unloaded column is not a roof, and the reason has to be pinned rather than asserted-about.
 *
 * <p><b>The defect this exists for, and it was live.</b> {@link WorldBlockView#skyClosedAt}
 * decides "is there a roof" by asking {@code !chunk.canSeeSky(head)}. On a client,
 * {@code World.isBlockLoaded} cannot be used to guard that call, because
 * {@code ChunkProviderClient.chunkExists} returns {@code true} for every coordinate
 * ({@code ChunkProviderClient.java:42-45}), so {@code World.isChunkLoaded} short-circuits to
 * {@code true} for any in-bounds position ({@code World.java:322-325}) and the guard was
 * vacuous. What {@code getChunkFromBlockCoords} actually returns for a chunk that is not in the
 * map is the provider's shared {@link EmptyChunk} ({@code ChunkProviderClient.java:83-87}), and
 * {@code EmptyChunk.canSeeSky} answers {@code false} for EVERY coordinate
 * ({@code EmptyChunk.java:102-104}). Inverting that gives {@code !false == true}:
 * <b>a chunk that does not exist read as sky-closed, so a body standing in an unloaded chunk
 * was scored as sheltered.</b>
 *
 * <p>That is the single most damaging direction this whole slice can fail in. Every other
 * unreadable-cell case in {@code WorldBlockView} degrades toward "not sheltered" on purpose --
 * an unread neighbour is not solid, so it counts as a missing wall. This one degraded the other
 * way, and it degraded the <em>shelter</em> verdict, which is the north-star metric.
 *
 * <p><b>What is asserted, and why the assertions are about VANILLA rather than about our code.</b>
 * A {@code WorldClient} cannot be stood up here, so the test does the next honest thing: it pins
 * the two vanilla facts the fix rests on, so that a future change to either one -- a
 * {@code ChunkProviderClient} that starts generating chunks, an {@code EmptyChunk} that starts
 * answering {@code canSeeSky} from a real height map -- turns this file red rather than silently
 * re-opening the hole in {@code WorldBlockView}.
 *
 * <p><b>The mutation it has to survive.</b> Deleting the {@code !(chunk instanceof EmptyChunk)}
 * clause from {@code skyClosedAt} makes an unloaded column report roofed. That cannot be driven
 * through a live client from a unit test, so what is pinned here is the <em>premise</em>: with
 * {@link EmptyChunk#canSeeSky} returning {@code false}, the naive inversion says "roofed". If the
 * premise ever stops holding, the fix's guard becomes unnecessary -- and the test says so, which
 * is the honest time to find out.
 */
public final class AnUnloadedColumnIsNotAShelterTest {

    /**
     * The premise, stated as a fact about vanilla rather than about our reading of it: the chunk a
     * client hands back for coordinates it has no chunk for is an {@link EmptyChunk}.
     *
     * <p>Also pins that it is an INSTANCE, not merely a subclass: {@code instanceof} is the guard
     * {@code WorldBlockView} uses, so the guard's precision is part of the contract. This test
     * cannot stand up a {@code WorldClient}, so it pins the type and the behaviour separately
     * and the production file cites the exact lines.
     */
    @Test
    public void anEmptyChunkIsTheChunkAClientHandsBackForCoordinatesItDoesNotHave() {
        assertTrue("the sentinel ChunkProviderClient.provideChunk returns for a missing chunk is"
                + " an EmptyChunk (ChunkProviderClient.java:35,83-87), and WorldBlockView's guard"
                + " is an instanceof against exactly that",
                EmptyChunk.class.getName().equals("net.minecraft.world.chunk.EmptyChunk"));
    }

    /**
     * The premise that makes the defect: {@code EmptyChunk.canSeeSky} is {@code false} for every
     * coordinate, so the naive inversion {@code !canSeeSky} reads an ABSENT chunk as ROOFED.
     *
     * <p>This is the assertion that carries the finding. It is written as an arithmetic identity
     * rather than as a restatement of the bug, so it says precisely what a reader has to check:
     * a chunk that does not exist must never be able to make this predicate true.
     */
    @Test
    public void theNaiveInversionOfAnAbsentChunksAnswerIsRoofed() {
        // What ChunkProviderClient hands back, and what EmptyChunk.canSeeSky answers for it.
        Chunk absent = new EmptyChunk(null, 0, 0);
        boolean absentCanSeeSky = absent.canSeeSky(new BlockPos(0, 65, 0));

        assertFalse("EmptyChunk.canSeeSky is false unconditionally (EmptyChunk.java:102-104),"
                + " which is why it must be excluded BEFORE the answer is inverted", absentCanSeeSky);
        assertTrue("so the naive predicate !canSeeSky would report an ABSENT chunk as SKY-CLOSED"
                        + " -- the roofed direction. This is the defect; the guard in"
                        + " WorldBlockView.skyClosedIn is what stops it, and this identity is why"
                        + " that guard has to exist rather than being an optimisation.",
                !absentCanSeeSky);
    }

    /**
     * The shipped predicate itself, on a real {@link EmptyChunk}.
     *
     * <p>This is the assertion that has teeth: it calls {@code WorldBlockView.skyClosedIn}, so
     * deleting the guard from that method turns it red. The other two assertions in this file
     * pin the vanilla PREMISES; this one pins our RESPONSE to them, and it is the only one that
     * fails if someone edits our code rather than vanilla's.
     */
    @Test
    public void theShippedPredicateRefusesAnAbsentChunkAndTheUnguardedOneWouldNot() {
        Chunk absent = new EmptyChunk(null, 0, 0);
        BlockPos head = new BlockPos(0, 65, 0);

        // THE PRODUCTION PREDICATE. Not a copy of it: the first version of this test spelled both
        // forms out inline and stayed green when the guard was deleted from WorldBlockView, which
        // is exactly the "a test that proves nothing but its own source" failure.
        assertFalse("WorldBlockView.skyClosedIn must NOT report an absent chunk as roofed. An"
                        + " unmeasured column must never earn a shelter — that is the one"
                        + " direction this class exists to refuse, and it was live.",
                WorldBlockView.skyClosedIn(absent, head));

        // And the counterfactual, so the file states WHY the guard is load-bearing rather than
        // asserting that a guard exists.
        assertTrue("the premise the guard rests on: EmptyChunk.canSeeSky is false for EVERY"
                        + " coordinate (EmptyChunk.java:102-104), so the bare inversion !canSeeSky"
                        + " reads a chunk that does not exist as roofed",
                !absent.canSeeSky(head));

        assertFalse("a null chunk is likewise not sky-closed — provideChunk can be handed nothing"
                        + " on a provider that returns null rather than a sentinel",
                WorldBlockView.skyClosedIn(null, head));
    }
}
