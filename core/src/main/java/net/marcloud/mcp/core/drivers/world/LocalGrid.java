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

    /** One vertical column at (dx,dz) relative to origin. */
    public record Column(int dx, int dz, Integer surfaceDy, String surface,
                         String feet, String head, List<Run> profile) {
    }

    /** A run-length span of one block id in a column's vertical profile. */
    public record Run(String block, int fromDy, int len) {
    }

    /**
     * Sample a columnar grid around {@code origin} (the player's feet BlockPos).
     * Must run on the game thread.
     */
    public static LocalGrid sampleColumnar(WorldClient w, BlockPos origin, int radius,
                                           ObserveProfile prof) {
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
                cols.add(new Column(dx, dz, surfaceDy, surface, feet, head, profile));

                if (surface != null && !"air".equals(surface)) {
                    counts.merge(surface, 1, Integer::sum);
                }
            }
        }
        return new LocalGrid(r, prof.emitProfile ? "column" : "surface",
                origin.getX(), origin.getY(), origin.getZ(), cols, counts);
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
