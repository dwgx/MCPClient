package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.util.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.pathfinder.WalkNodeProcessor;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * LIVE probe (default SKIPPED): does vanilla's A* actually run for {@code EntityPlayerSP}?
 *
 * <p><b>Why this exists.</b> {@code docs/agency/command-to-action.md} section 5 Fork B asks whether
 * navigation should wrap vanilla's shipped pathfinder or write a local steerer. Vanilla ships a
 * complete A* that a decade of mobs have exercised, but it has never been run for the CLIENT player
 * here, and one hazard is already known by reading: {@code WalkNodeProcessor.func_176170_a}'s rail
 * check reads {@code entityIn.worldObj.getBlockState} directly
 * ({@code WalkNodeProcessor.java:235-237}), bypassing the {@code IBlockAccess} handed in. So
 * behaviour outside the cached window is unverified by construction, and only a live client settles
 * it. This is the cheapest experiment that decides a fork.
 *
 * <p><b>What was settled by reading, so this does not test it.</b>
 * {@code PathFinder.createEntityPathTo} calls {@code nodeProcessor.initProcessor} itself
 * ({@code PathFinder.java:44}), and {@code NodeProcessor.initProcessor} sizes the entity from the
 * entity ({@code NodeProcessor.java:17}) -- so the {@code entitySizeX/Y/Z} fields need no manual
 * setup, which was the main suspected blocker. {@code WalkNodeProcessor}'s own flags
 * ({@code canEnterDoors}, {@code canBreakDoors}, {@code avoidsWater}, {@code canSwim}) have no
 * setters and default to false, which is the conservative posture: treat doors as closed and water
 * as passable-but-not-preferred.
 *
 * <p><b>What it does NOT prove.</b> That a path can be FOLLOWED. Producing nodes and walking them
 * are different problems -- following needs the closed-loop locomotion that MOVE does not have yet
 * (see {@code MoveApplier}, a pure lifecycle counter). This probe answers "is the routing engine
 * reusable", nothing more.
 *
 * <p>Run live with a client in a world:
 * {@code ./mvnw -pl core test -Dtest=NavPathLiveIT -Dmcp.it.live=true}
 */
public class NavPathLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    /** How far to ask for a path, in blocks. 32 is what vanilla ground mobs use. */
    private static final float RANGE = 32.0F;

    /** Horizontal offset of the target from the player, in blocks. */
    private static final int TARGET_OFFSET = 12;

    private static void requireLive() {
        Assume.assumeTrue("requires live game window; run with -Dmcp.it.live=true", LIVE);
    }

    private GameAccess requireInWorld() {
        GameAccess game = new GameAccess();
        boolean ok;
        try {
            ok = game.isInWorld() && game.player() != null && game.world() != null;
        } catch (Throwable noGame) {
            ok = false;
        }
        Assume.assumeTrue("requires the player to be in a world", ok);
        return game;
    }

    /**
     * The whole question: does {@code createEntityPathTo} return a usable path for the client player.
     *
     * <p>Logs node count and wall time because those decide the fork as much as success does -- a
     * path that takes 400ms is not something to call every tick, and the number of nodes tells us
     * whether the follower needs to consume them in batches.
     */
    @Test
    public void vanillaAStarProducesAPathForTheClientPlayer() {
        requireLive();
        GameAccess game = requireInWorld();
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();

        BlockPos from = new BlockPos(p.posX, p.getEntityBoundingBox().minY, p.posZ);
        // Straight out along +X. Deliberately NOT a curated target: the point is whether the
        // engine runs at all on whatever terrain the player happens to be standing on.
        BlockPos to = from.add(TARGET_OFFSET, 0, TARGET_OFFSET);

        // Pad the cache by one chunk beyond the query box: sub=0 means "no padding", and the
        // processor probes neighbours of edge nodes.
        ChunkCache cache = new ChunkCache(w, from.add(-16, -16, -16), to.add(16, 16, 16), 0);
        PathFinder finder = new PathFinder(new WalkNodeProcessor());

        long t0 = System.nanoTime();
        PathEntity path = finder.createEntityPathTo(cache, p, to, RANGE);
        long micros = (System.nanoTime() - t0) / 1000L;

        System.out.println("[navprobe] from=" + from + " to=" + to
                + " path=" + (path == null ? "NULL" : path.getCurrentPathLength() + " nodes")
                + " took=" + micros + "us");

        if (path != null) {
            int n = path.getCurrentPathLength();
            for (int i = 0; i < n; i++) {
                System.out.println("[navprobe]   node " + i + " -> " + path.getPathPointFromIndex(i));
            }
            System.out.println("[navprobe] final=" + path.getFinalPathPoint()
                    + " vectorFromIndex(0)=" + path.getVectorFromIndex(p, 0));
        }

        // A null path is a legitimate ANSWER, not a crash -- it means unreachable within RANGE, and
        // that is information about the fork too. What this asserts is that the engine RAN: it
        // neither threw nor produced a zero-node path, both of which would mean the client player
        // is not a usable Entity for it.
        assertNotNull("vanilla A* returned no path at all for the client player. Either the target "
                + "is unreachable within " + RANGE + " blocks (re-run somewhere open), or "
                + "EntityPlayerSP cannot drive WalkNodeProcessor -- which kills the 'wrap vanilla' "
                + "branch of Fork B. Check stderr for a throw.", path);
        assertTrue("a non-null path must contain at least one node; " + path.getCurrentPathLength()
                + " nodes means the processor resolved a start but no reachable step, which is a "
                + "different failure from unreachable-target", path.getCurrentPathLength() > 0);
    }

    /**
     * Does the known {@code IBlockAccess}-bypass actually bite outside the cached window?
     *
     * <p>{@code func_176170_a} reads the live world for its rail check, so a cache deliberately
     * sized SMALLER than the query should still not crash -- vanilla would be reading real chunks
     * for that one probe. If this throws while the test above passes, the bypass is real and any
     * wrapper must size its cache to cover the whole query rather than trusting the argument.
     */
    @Test
    public void anUndersizedCacheDoesNotCrashTheProcessor() {
        requireLive();
        GameAccess game = requireInWorld();
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();

        BlockPos from = new BlockPos(p.posX, p.getEntityBoundingBox().minY, p.posZ);
        BlockPos to = from.add(TARGET_OFFSET, 0, TARGET_OFFSET);
        // Cache covers only the player's own block: every neighbour probe is outside it.
        ChunkCache tiny = new ChunkCache(w, from, from, 0);
        PathFinder finder = new PathFinder(new WalkNodeProcessor());

        PathEntity path;
        try {
            path = finder.createEntityPathTo(tiny, p, to, RANGE);
        } catch (Throwable t) {
            System.out.println("[navprobe] undersized cache THREW: " + t);
            throw new AssertionError("an undersized ChunkCache made vanilla A* throw ("
                    + t + "). A wrapper must therefore size the cache to cover the entire query "
                    + "box; record this in Fork B before wrapping.", t);
        }
        System.out.println("[navprobe] undersized cache survived, path="
                + (path == null ? "NULL" : path.getCurrentPathLength() + " nodes")
                + " (null here is expected and fine -- the point is it did not throw)");
    }
}
