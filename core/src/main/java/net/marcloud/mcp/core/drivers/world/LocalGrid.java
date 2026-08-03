package net.marcloud.mcp.core.drivers.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;

/**
 * PHASE W.3 — a columnar (not solid-cube) local block grid. FORK default: columnar
 * + feet-layer-first. For each (dx,dz) within the radius it samples the layer the
 * agent stands in (feet/head), the surface height/block, and — for richer profiles —
 * a run-length-encoded vertical profile (air-compressible). This preserves the
 * geometry an agent navigates by at O((2r+1)^2) entries instead of O((2r+1)^3).
 *
 * <p>Reference-free: only ints/Strings. MUST be sampled on the game thread
 * ({@code w.getBlockState} reads live chunk state).
 */
public record LocalGrid(int radius, String mode, int originX, int originY, int originZ,
                        List<Column> columns, Map<String, Integer> blockCounts) {

    /**
     * How far below the feet layer to probe for a floor, in blocks.
     *
     * <p>24 because the question is "does this hurt and how much", and vanilla damage is
     * {@code ceil(distance - 3)} -- at this depth the answer is already "certainly fatal without
     * mitigation" for a 20-HP player, so a deeper probe buys no decision. Bounded on purpose: this
     * runs at every one of the (2r+1)^2 columns, so an unbounded scan to bedrock is what would make
     * the term too expensive to include by default.
     */
    private static final int DROP_PROBE_MAX = 24;

    /** {@code walk} value when vanilla's verdict could not be obtained. */
    public static final int WALK_UNKNOWN = Integer.MIN_VALUE;

    /** Vanilla's "clear" verdict, i.e. the boring case worth omitting from the wire. */
    public static final int WALK_CLEAR = 1;

    /**
     * One vertical column at (dx,dz) relative to origin.
     *
     * @param dropDepth blocks from the feet layer down to the first floor, or null when a bounded
     *                  probe found none. Separate from {@code surfaceDy} because the sampling
     *                  window is only {@code vBelow} deep (3 on EXPLORE) while vanilla fall damage
     *                  starts above 3 -- so without this the deepest visible drop is exactly the
     *                  deepest harmless one, and every damaging fall looks like a bottomless void.
     *                  Boxed so "probed and found no floor" stays distinguishable from 0, which
     *                  would read as flat ground: the opposite of the truth.
     * @param walk      vanilla's own passability verdict for the feet layer, from
     *                  {@code WalkNodeProcessor.func_176170_a}: -1 water, -2 lava, -3 fence/wall,
     *                  -4 closed trapdoor, 0 solid/blocked, 1 clear, 2 clear-but-wet. Borrowed
     *                  rather than invented so there is no second taxonomy of what can be stood on
     *                  to keep in step with the game.
     */
    public record Column(int dx, int dz, Integer surfaceDy, String surface,
                         String feet, String head, List<Run> profile,
                         Integer dropDepth, int walk) {
    }

    /** A run-length span of one block id in a column's vertical profile. */
    public record Run(String block, int fromDy, int len) {
    }

    /**
     * Sample a columnar grid around {@code origin} (the player's feet BlockPos).
     * Must run on the game thread.
     */
    public static LocalGrid sampleColumnar(WorldClient w, BlockPos origin, int radius,
                                           ObserveProfile prof, net.minecraft.entity.Entity of) {
        int r = Math.max(1, Math.min(radius, 16));
        int vLo = -prof.vBelow;
        int vHi = prof.vAbove;
        List<Column> cols = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                String feet = idName(w, origin.add(dx, 0, dz));
                String head = idName(w, origin.add(dx, 1, dz));

                // Surface: scan from the top of the window downward; first non-air is surface.
                Integer surfaceDy = null;
                String surface = "air";
                for (int dy = vHi; dy >= vLo; dy--) {
                    String n = idName(w, origin.add(dx, dy, dz));
                    if (n != null && !"air".equals(n)) {
                        surfaceDy = dy;
                        surface = n;
                        break;
                    }
                }

                List<Run> profile = List.of();
                if (prof.emitProfile) {
                    profile = runs(w, origin, dx, dz, vLo, vHi);
                }
                Integer dropDepth = dropDepth(w, origin, dx, dz, surfaceDy, vLo);
                int walk = walkVerdict(w, origin, dx, dz, of);
                cols.add(new Column(dx, dz, surfaceDy, surface, feet, head, profile,
                        dropDepth, walk));

                if (surface != null && !"air".equals(surface)) {
                    counts.merge(surface, 1, Integer::sum);
                }
            }
        }
        return new LocalGrid(r, prof.emitProfile ? "column" : "surface",
                origin.getX(), origin.getY(), origin.getZ(), cols, counts);
    }

    /**
     * How far the fall is at (dx,dz), probing below the sampling window.
     *
     * <p>Bounded at {@link #DROP_PROBE_MAX} rather than scanning to bedrock: the caller's question
     * is "does this hurt, and how much", and past a couple of dozen blocks the answer stops
     * changing -- everything is fatal. That keeps the cost O(1) per column with a small constant
     * instead of O(world height), which is what would make this too expensive to include by
     * default at (2r+1)^2 columns.
     *
     * @return 0 when the feet layer itself is standing on something inside the window, the drop in
     *         blocks when a floor is found below it, or null when the probe bottomed out -- which
     *         means "at least DROP_PROBE_MAX", i.e. certainly lethal.
     */
    private static Integer dropDepth(WorldClient w, BlockPos origin, int dx, int dz,
                                     Integer surfaceDy, int vLo) {
        // A surface at or above the feet layer means there is nothing to fall into here.
        if (surfaceDy != null && surfaceDy >= 0) {
            return 0;
        }
        for (int dy = -1; dy >= -DROP_PROBE_MAX; dy--) {
            String n = idName(w, origin.add(dx, dy, dz));
            if (n != null && !"air".equals(n)) {
                // Standing on the block AT dy means falling |dy| - 1 blocks to land on top of it.
                return -dy - 1;
            }
        }
        return null;
    }

    /**
     * Vanilla's passability verdict for the feet layer, or {@link #WALK_UNKNOWN} if unavailable.
     *
     * <p>Delegated to {@code WalkNodeProcessor.func_176170_a} (public static) so the agent is handed
     * the same answer the game's own mobs navigate by, including the awkward cases a block-name
     * taxonomy gets wrong -- a fence is 1.5 blocks tall and impassable despite being one block, and
     * vanilla returns -3 for exactly that. Measured callable against live terrain: a player on flat
     * ground reads 0 below and 1 at feet and head.
     *
     * <p><b>The entity is required, not optional.</b> {@code func_176170_a} opens with
     * {@code new BlockPos(entityIn)} and later reads {@code entityIn.worldObj} for its rail check,
     * so a null reference throws immediately. Passing null was the first version of this and it
     * silently cost every column its verdict -- the field simply vanished from the JSON, which is
     * the quiet-failure shape this module keeps producing.
     *
     * <p>Fault-isolated: this reaches into a vanilla static with a bypass documented at
     * {@code WalkNodeProcessor.java:235-237} (its rail check reads the live world rather than the
     * {@code IBlockAccess} handed in), so a throw here must cost one column's verdict rather than
     * the whole observation.
     */
    private static int walkVerdict(WorldClient w, BlockPos origin, int dx, int dz,
                                   net.minecraft.entity.Entity of) {
        if (of == null) {
            return WALK_UNKNOWN;
        }
        try {
            return net.minecraft.world.pathfinder.WalkNodeProcessor.func_176170_a(
                    w, of, origin.getX() + dx, origin.getY(), origin.getZ() + dz,
                    1, 2, 1, false, false, false);
        } catch (Throwable t) {
            return WALK_UNKNOWN;
        }
    }

    /** Run-length-encode the vertical window [vLo,vHi] of one column (air-compressible). */
    private static List<Run> runs(WorldClient w, BlockPos origin, int dx, int dz, int vLo, int vHi) {
        List<Run> out = new ArrayList<>();
        String cur = null;
        int from = vLo;
        for (int dy = vLo; dy <= vHi; dy++) {
            String n = idName(w, origin.add(dx, dy, dz));
            if (cur == null) {
                cur = n;
                from = dy;
            } else if (!cur.equals(n)) {
                out.add(new Run(cur, from, dy - from));
                cur = n;
                from = dy;
            }
        }
        if (cur != null) {
            out.add(new Run(cur, from, vHi - from + 1));
        }
        return out;
    }

    /** Block registry name (namespace stripped), or "unknown" on error — reuses WorldScanner logic. */
    private static String idName(WorldClient w, BlockPos pos) {
        try {
            Block b = w.getBlockState(pos).getBlock();
            var loc = Block.blockRegistry.getNameForObject(b);
            if (loc == null) {
                return "unknown";
            }
            String s = loc.toString();
            int colon = s.indexOf(':');
            return colon >= 0 ? s.substring(colon + 1) : s;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
