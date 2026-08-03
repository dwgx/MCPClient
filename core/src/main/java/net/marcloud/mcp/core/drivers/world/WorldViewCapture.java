package net.marcloud.mcp.core.drivers.world;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.ke.GameClock;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;

/**
 * PHASE W orchestrator: builds one immutable {@link WorldView} from live game state.
 * MUST be invoked on the game thread (callers marshal via {@code GameBridge.onGameThread})
 * — every read here touches live world/player/entity/render state. Everything after
 * capture (JSON projection, diff) is pure and off-thread safe.
 */
public final class WorldViewCapture {

    private WorldViewCapture() {
    }

    public static WorldView capture(GameAccess game, ObserveProfile prof, int radius,
                                    List<String> sections) {
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();
        if (p == null || w == null) {
            return WorldView.absent();
        }
        Minecraft mc = game.mc();
        boolean all = sections == null || sections.isEmpty();
        long tickId = GameClock.INSTANCE.tickId();
        BlockPos feet = new BlockPos(p.posX, p.posY, p.posZ);

        SelfView self = want(all, sections, "self") ? self(p, mc) : null;
        // The player goes in so each column can carry vanilla's own passability verdict: its
        // func_176170_a sizes the probe from the entity and derefences it on the first line, so
        // there is no null-entity shortcut.
        LocalGrid grid = want(all, sections, "grid")
                ? LocalGrid.sampleColumnar(w, feet, radius, prof, p) : null;
        List<EntityView> entities = want(all, sections, "entities")
                ? entities(p, w, prof, radius) : List.of();
        InventoryView inv = want(all, sections, "inventory") ? inventory(p) : null;
        TargetView target = want(all, sections, "target") ? target(mc, w, p) : null;
        EnvView env = want(all, sections, "env") ? env(w, feet) : null;

        return new WorldView(true, tickId, prof.name().toLowerCase(java.util.Locale.ROOT),
                self, grid, entities, inv, target, env);
    }

    private static boolean want(boolean all, List<String> sections, String s) {
        return all || sections.contains(s);
    }

    private static SelfView self(EntityPlayerSP p, Minecraft mc) {
        int food = -1;
        float sat = -1f;
        try {
            food = p.getFoodStats().getFoodLevel();
            sat = p.getFoodStats().getSaturationLevel();
        } catch (Throwable ignored) {
        }
        String gamemode = "unknown";
        try {
            if (mc != null && mc.playerController != null) {
                gamemode = mc.playerController.getCurrentGameType().name();
            }
        } catch (Throwable ignored) {
        }
        List<SelfView.Effect> effects = new ArrayList<>();
        try {
            for (Object o : p.getActivePotionEffects()) {
                if (o instanceof PotionEffect pe) {
                    int id = pe.getPotionID();
                    String name;
                    try {
                        name = Potion.potionTypes[id] != null ? Potion.potionTypes[id].getName() : "potion." + id;
                    } catch (Throwable t) {
                        name = "potion." + id;
                    }
                    effects.add(new SelfView.Effect(id, name, pe.getAmplifier(), pe.getDuration()));
                }
            }
        } catch (Throwable ignored) {
        }
        return new SelfView(
                p.posX, p.posY, p.posZ,
                p.motionX, p.motionY, p.motionZ,
                p.rotationYaw, p.rotationPitch,
                safeHealth(p), food, sat,
                safeInt(() -> p.experienceLevel), safeFloat(() -> p.experience),
                safeInt(p::getTotalArmorValue), safeInt(p::getAir),
                gamemode, p.isSneaking(), p.isSprinting(), p.onGround,
                effects);
    }

    private static List<EntityView> entities(EntityPlayerSP p, WorldClient w,
                                             ObserveProfile prof, int radius) {
        double range = Math.max(1, radius) * prof.entityRangeMul;
        List<EntityView> out = new ArrayList<>();
        for (Object o : new ArrayList<>(w.loadedEntityList)) {   // copy first: avoid CME
            if (!(o instanceof Entity e) || e == p) {
                continue;
            }
            double dist = p.getDistanceToEntity(e);
            if (dist > range) {
                continue;
            }
            String type;
            try {
                type = e.getName();
            } catch (Throwable t) {
                type = e.getClass().getSimpleName();
            }
            Integer hp = null;
            if (prof.entityHp && e instanceof EntityLivingBase el) {
                try {
                    hp = Math.round(el.getHealth());
                } catch (Throwable ignored) {
                }
            }
            out.add(new EntityView(e.getEntityId(), type, e.posX, e.posY, e.posZ, dist, hp,
                    prof.entityHp ? type : null));
        }
        out.sort((a, b) -> Double.compare(a.dist(), b.dist()));
        if (out.size() > prof.maxEntities) {
            out = new ArrayList<>(out.subList(0, prof.maxEntities));
        }
        return out;
    }

    private static InventoryView inventory(EntityPlayerSP p) {
        List<InventoryView.Slot> slots = new ArrayList<>();
        int selected = 0;
        try {
            selected = p.inventory.currentItem;
            ItemStack[] main = p.inventory.mainInventory;
            for (int i = 0; i < main.length; i++) {
                ItemStack st = main[i];
                if (st == null) {
                    continue;
                }
                Item it = st.getItem();
                String name = "unknown";
                try {
                    var rl = Item.itemRegistry.getNameForObject(it);
                    if (rl != null) {
                        String s = rl.toString();
                        int colon = s.indexOf(':');
                        name = colon >= 0 ? s.substring(colon + 1) : s;
                    }
                } catch (Throwable ignored) {
                }
                Integer maxDmg = null;
                try {
                    if (st.isItemStackDamageable()) {
                        maxDmg = st.getMaxDamage();
                    }
                } catch (Throwable ignored) {
                }
                slots.add(new InventoryView.Slot(i, name, st.stackSize, st.getItemDamage(), maxDmg));
            }
        } catch (Throwable ignored) {
        }
        return new InventoryView(selected, slots);
    }

    private static TargetView target(Minecraft mc, WorldClient w, EntityPlayerSP p) {
        try {
            MovingObjectPosition mop = mc == null ? null : mc.objectMouseOver;
            if (mop == null || mop.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
                return TargetView.miss();
            }
            if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                BlockPos bp = mop.getBlockPos();
                String block = bp == null ? "unknown" : blockName(w, bp);
                String side = mop.sideHit == null ? null : mop.sideHit.name();
                double d = mop.hitVec == null ? 0.0
                        : mop.hitVec.distanceTo(new net.minecraft.util.Vec3(p.posX, p.posY + p.getEyeHeight(), p.posZ));
                return new TargetView("block", block,
                        bp == null ? null : bp.getX(), bp == null ? null : bp.getY(),
                        bp == null ? null : bp.getZ(), side, null, null, null, d);
            }
            if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mop.entityHit != null) {
                Entity e = mop.entityHit;
                Integer hp = null;
                if (e instanceof EntityLivingBase el) {
                    try {
                        hp = Math.round(el.getHealth());
                    } catch (Throwable ignored) {
                    }
                }
                String type;
                try {
                    type = e.getName();
                } catch (Throwable t) {
                    type = e.getClass().getSimpleName();
                }
                return new TargetView("entity", null, null, null, null, null,
                        e.getEntityId(), type, hp, p.getDistanceToEntity(e));
            }
        } catch (Throwable ignored) {
        }
        return TargetView.miss();
    }

    private static EnvView env(WorldClient w, BlockPos base) {
        String dim = "unknown";
        String biome = "unknown";
        long time = 0L;
        try {
            dim = w.provider.getDimensionName();
        } catch (Throwable ignored) {
        }
        try {
            biome = w.getBiomeGenForCoords(base).biomeName;
        } catch (Throwable ignored) {
        }
        try {
            time = w.getWorldTime();
        } catch (Throwable ignored) {
        }
        return new EnvView(dim, biome, timeBucket(time), time);
    }

    private static String timeBucket(long t) {
        long d = ((t % 24000L) + 24000L) % 24000L;
        if (d < 1000) return "sunrise";
        if (d < 6000) return "day";
        if (d < 12000) return "noon-afternoon";
        if (d < 13000) return "sunset";
        if (d < 23000) return "night";
        return "sunrise";
    }

    private static String blockName(WorldClient w, BlockPos pos) {
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

    private static float safeHealth(EntityPlayerSP p) {
        try {
            return p.getHealth();
        } catch (Throwable t) {
            return -1f;
        }
    }

    private interface IntSup {
        int get();
    }

    private interface FloatSup {
        float get();
    }

    private static int safeInt(IntSup s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static float safeFloat(FloatSup s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return -1f;
        }
    }
}
