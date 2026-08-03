package net.marcloud.mcp.core.drivers.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;

/**
 * Finds where a named block type is, and returns coordinates rather than counts.
 *
 * <p><b>Why this exists.</b> Answering "where is the nearest iron ore" had no cheap path. Measured
 * live, {@code world_view} is 34,101 characters at radius 8 and 138,152 at radius 16 -- about 34.5k
 * tokens -- and the model had to scan all of it to extract one coordinate. Neither existing sampler
 * could do better: {@link LocalGrid}'s {@code blockCounts} reports that a type is present without
 * saying where, and {@link WorldScanner} builds a name-to-count map while discarding every position
 * it visited. So the whole neighbourhood was being paid for to learn one fact, repeatedly.
 *
 * <p>"Describe my surroundings" and "where is the nearest X" are different questions with different
 * answer shapes, and serving the second out of the first is what made it expensive.
 *
 * <p><b>Search order.</b> Expanding shells outward from the origin, so the scan can stop as soon as
 * it has enough hits and the common case ("the nearest one") touches a small volume. A naive triple
 * loop over the full cube would visit (2r+1)^3 positions before it could sort -- 274,625 at r=32 --
 * whereas the answer is usually a few blocks away.
 *
 * <p>MUST run on the game thread: {@code getBlockState} reads live chunk state.
 */
public final class BlockFinder {

    private BlockFinder() {
    }

    /** Hard cap on the search radius, matching {@code scan_surroundings}. */
    public static final int MAX_RADIUS = 32;

    /** Hard cap on returned hits, so a query for "stone" cannot become the whole volume. */
    public static final int MAX_LIMIT = 64;

    /**
     * One located block.
     *
     * @param block block name, namespace stripped, as the grid reports it
     * @param dist  euclidean distance from the search origin, in blocks
     */
    public record Hit(String block, int x, int y, int z, double dist) {
    }

    /**
     * Search outward for any of {@code types}, nearest first.
     *
     * @param types comma-separated block names, with or without the {@code minecraft:} prefix
     * @return at most {@code limit} hits, nearest first; empty when nothing matched
     */
    public static List<Hit> find(WorldClient w, BlockPos origin, String types, int radius,
                                 int limit) {
        Set<String> wanted = parseTypes(types);
        if (w == null || origin == null || wanted.isEmpty()) {
            return List.of();
        }
        int r = Math.max(1, Math.min(radius, MAX_RADIUS));
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Hit> hits = new ArrayList<>();

        // Shell by shell, so a near hit ends the search early. Within a shell the order is
        // arbitrary, which is why the result is still sorted before truncation -- shell distance is
        // a lower bound on euclidean distance, not the distance itself.
        for (int shell = 0; shell <= r; shell++) {
            collectShell(w, origin, wanted, shell, hits);
            // Only stop once the shell boundary itself is farther than everything already found:
            // a hit at the corner of shell 3 is farther than one at the face of shell 4.
            if (hits.size() >= cap && shell > 0) {
                double worstKept = nearestFirst(hits, cap).get(Math.min(cap, hits.size()) - 1).dist();
                if (shell >= worstKept) {
                    break;
                }
            }
        }
        return nearestFirst(hits, cap);
    }

    /** Sort by distance then truncate. Order before limit, or the "nearest" is whichever was seen. */
    public static List<Hit> nearestFirst(List<Hit> hits, int limit) {
        List<Hit> out = new ArrayList<>(hits);
        out.sort(Comparator.comparingDouble(Hit::dist));
        return out.size() <= limit ? List.copyOf(out) : List.copyOf(out.subList(0, limit));
    }

    /**
     * Whether {@code blockName} is one of the comma-separated {@code types}.
     *
     * <p>Namespace-insensitive and case-insensitive because the model feeds back what it read: the
     * grid strips {@code minecraft:} ({@link LocalGrid} and {@link WorldViewCapture} both do), so a
     * name copied out of a {@code world_view} must work here unchanged, and a qualified name is
     * accepted as well rather than silently matching nothing.
     */
    public static boolean matches(String blockName, String types) {
        return blockName != null && parseTypes(types).contains(strip(blockName));
    }

    private static void collectShell(WorldClient w, BlockPos origin, Set<String> wanted, int shell,
                                     List<Hit> out) {
        for (int dx = -shell; dx <= shell; dx++) {
            for (int dy = -shell; dy <= shell; dy++) {
                for (int dz = -shell; dz <= shell; dz++) {
                    // Surface of the cube only; the interior belonged to a previous shell.
                    if (shell > 0 && Math.abs(dx) != shell && Math.abs(dy) != shell
                            && Math.abs(dz) != shell) {
                        continue;
                    }
                    BlockPos at = origin.add(dx, dy, dz);
                    String name = nameAt(w, at);
                    if (name == null || !wanted.contains(name)) {
                        continue;
                    }
                    double d = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    out.add(new Hit(name, at.getX(), at.getY(), at.getZ(), round(d)));
                }
            }
        }
    }

    private static Set<String> parseTypes(String types) {
        Set<String> out = new LinkedHashSet<>();
        if (types == null || types.isBlank()) {
            return out;
        }
        for (String part : types.split(",")) {
            String s = strip(part);
            // Air is never a useful answer and asking for it would match most of the volume.
            if (!s.isEmpty() && !"air".equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Lowercase, trimmed, namespace removed. */
    private static String strip(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }

    private static String nameAt(WorldClient w, BlockPos pos) {
        try {
            Block b = w.getBlockState(pos).getBlock();
            Object name = Block.blockRegistry.getNameForObject(b);
            if (name == null) {
                return null;
            }
            String s = strip(name.toString());
            return "air".equals(s) ? null : s;
        } catch (Throwable t) {
            // One unreadable position must not end the search: an unloaded chunk edge is routine.
            return null;
        }
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
