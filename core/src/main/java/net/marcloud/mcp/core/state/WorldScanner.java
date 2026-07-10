package net.marcloud.mcp.core.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;

/**
 * Captures a symbolic {@link Surroundings} snapshot. MUST be invoked on the game
 * thread (reads live world/entity state) — callers marshal via GameBridge.
 *
 * <p>Follows Voyager's 32-block observation template: dedup'd nearby block
 * types, distance-sorted nearby entities, inventory, column, biome/time/pos.
 */
public final class WorldScanner {

    private WorldScanner() {
    }

    /** Scan radius for nearby blocks (Voyager uses 32; keep modest for cost). */
    private static final int DEFAULT_RADIUS = 16;

    public static Surroundings capture(GameAccess game, int radius) {
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();
        if (p == null || w == null) {
            return Surroundings.absent();
        }

        int r = Math.max(1, Math.min(radius, 32));
        BlockPos base = new BlockPos(p.posX, p.posY, p.posZ);

        String below = blockName(w, base.down());
        String legs = blockName(w, base);
        String head = blockName(w, base.up());

        // Nearby block-type counts (dedup by name, sampled cube). Skip air.
        Map<String, Integer> blocks = new TreeMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    String name = blockName(w, pos);
                    if (name != null && !"air".equals(name)) {
                        blocks.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        // Nearby entities, sorted by distance, excluding self.
        List<Surroundings.NearbyEntity> entities = new ArrayList<>();
        for (Object o : new ArrayList<>(w.loadedEntityList)) {
            if (!(o instanceof Entity e) || e == p) {
                continue;
            }
            double dist = p.getDistanceToEntity(e);
            if (dist <= r * 2) {
                entities.add(new Surroundings.NearbyEntity(safeName(e), dist));
            }
        }
        entities.sort((a, b) -> Double.compare(a.distance(), b.distance()));

        Map<String, Integer> inv = inventory(p);

        String biome;
        try {
            biome = w.getBiomeGenForCoords(base).biomeName;
        } catch (Throwable t) {
            biome = "unknown";
        }

        return new Surroundings(
                true,
                p.posX, p.posY, p.posZ, p.rotationYaw, p.rotationPitch,
                p.getHealth(), foodLevel(p),
                dimensionName(w),
                biome,
                timeOfDay(w),
                inv, below, legs, head, blocks, entities);
    }

    public static Surroundings capture(GameAccess game) {
        return capture(game, DEFAULT_RADIUS);
    }

    private static String blockName(WorldClient w, BlockPos pos) {
        try {
            Block b = w.getBlockState(pos).getBlock();
            var loc = Block.blockRegistry.getNameForObject(b);
            if (loc == null) {
                return "unknown";
            }
            String s = loc.toString();
            // strip the "minecraft:" namespace for brevity
            int colon = s.indexOf(':');
            return colon >= 0 ? s.substring(colon + 1) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, Integer> inventory(EntityPlayerSP p) {
        Map<String, Integer> inv = new LinkedHashMap<>();
        try {
            for (ItemStack st : p.inventory.mainInventory) {
                if (st != null) {
                    inv.merge(st.getDisplayName(), st.stackSize, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        return inv;
    }

    private static int foodLevel(EntityPlayerSP p) {
        try {
            return p.getFoodStats().getFoodLevel();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String safeName(Entity e) {
        try {
            return e.getName();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }

    private static String dimensionName(WorldClient w) {
        try {
            return w.provider.getDimensionName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String timeOfDay(WorldClient w) {
        long t = w.getWorldTime() % 24000L;
        if (t < 1000) return "sunrise";
        if (t < 6000) return "day";
        if (t < 12000) return "noon-afternoon";
        if (t < 13000) return "sunset";
        if (t < 23000) return "night";
        return "sunrise";
    }
}
