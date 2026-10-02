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

    private static final String SECTION_SELF = "self";
    private static final String SECTION_GRID = "grid";
    private static final String SECTION_ENTITIES = "entities";
    private static final String SECTION_INVENTORY = "inventory";
    private static final String SECTION_TARGET = "target";
    private static final String SECTION_ENV = "env";

    /**
     * The names {@code sections} accepts -- one list, and the same names the six {@link #want} call
     * sites below gate on.
     *
     * <p>Shared rather than written twice because the two halves of a misspelling are the schema that
     * offers the name and the sampler that looks for it, and they must not be able to drift apart. A
     * caller that names something else is REFUSED at the tool boundary rather than silently ignored:
     * an unrecognised name used to reach {@link #want}, match nothing and produce a view with that
     * section missing -- which mode=diff then reported as "unchanged", the exact quiet wrongness the
     * unsampled tokens were added to remove.
     */
    public static final List<String> SECTIONS = List.of(SECTION_SELF, SECTION_GRID, SECTION_ENTITIES,
            SECTION_INVENTORY, SECTION_TARGET, SECTION_ENV);

    /**
     * The names in {@code sections} that name no section, in the order they were given; empty when
     * every one is known (and for a null or empty list, which means "the default", not "nothing").
     *
     * <p>Public so the tool boundary can turn a typo into an error naming the offender instead of a
     * view that quietly lacks the section -- which is what {@code want} would have produced, since a
     * name nothing matches simply samples nothing.
     */
    public static List<String> unknownSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (String s : sections) {
            if (!SECTIONS.contains(s)) {
                unknown.add(s);
            }
        }
        return unknown;
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

        SelfView self = want(all, sections, SECTION_SELF) ? self(p, mc) : null;
        // The player goes in so each column can carry vanilla's own passability verdict: its
        // func_176170_a sizes the probe from the entity and derefences it on the first line, so
        // there is no null-entity shortcut.
        LocalGrid grid = want(all, sections, SECTION_GRID)
                ? LocalGrid.sampleColumnar(w, feet, radius, prof, p) : null;
        // Null, NOT List.of(), when the section was not requested. An empty list is a claim about
        // the world ("nothing nearby"); null is a statement about the sampling ("nobody looked").
        // The differ compares id sets, so handing it an empty list for an unsampled section made
        // every previously known id report as `left` -- and a caller reads `left` as "that creeper
        // is dead". Same fix as SelfView#effects, one section over.
        //
        // Scanned ONCE and the cap flag taken from that same scan, so the flag cannot disagree with
        // the list it describes.
        List<EntityView> found = want(all, sections, SECTION_ENTITIES)
                ? entitiesInRange(p, w, prof, radius) : null;
        List<EntityView> entities = entitiesSection(found, prof.maxEntities);
        boolean entitiesCapped = capTruncated(found, prof.maxEntities);
        InventoryView inv = want(all, sections, SECTION_INVENTORY) ? inventory(p) : null;
        TargetView target = want(all, sections, SECTION_TARGET) ? target(mc, w, p) : null;
        EnvView env = want(all, sections, SECTION_ENV) ? env(w, feet) : null;

        return new WorldView(true, tickId, prof.name().toLowerCase(java.util.Locale.ROOT),
                self, grid, entities, entitiesCapped, inv, target, env);
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
        // The Jump potion decides how much of a fall the body gets back, so the arithmetic has to
        // read it rather than assume none: -1 is the honest "no Jump effect", which is the value
        // FallDamage folds into "subtract 0".
        int jumpAmp = -1;
        try {
            var jump = p.getActivePotionEffect(net.minecraft.potion.Potion.jump);
            if (jump != null) {
                jumpAmp = jump.getAmplifier();
            }
        } catch (Throwable ignored) {
        }
        float fall = 0f;
        boolean blocking = false;
        try {
            fall = p.fallDistance;
        } catch (Throwable ignored) {
        }
        try {
            blocking = p.isBlocking();
        } catch (Throwable ignored) {
        }
        List<SelfView.Effect> effects = effectsOrNull(p::getActivePotionEffects);
        return new SelfView(
                p.posX, p.posY, p.posZ,
                p.motionX, p.motionY, p.motionZ,
                p.rotationYaw, p.rotationPitch,
                safeHealth(p), food, sat,
                safeInt(() -> p.experienceLevel), safeFloat(() -> p.experience),
                safeInt(p::getTotalArmorValue), boxedInt(p::getAir),
                gamemode, p.isSneaking(), p.isSprinting(), p.onGround,
                effects,
                fall, FallDamage.damageFor(fall, jumpAmp, 1.0F), blocking);
    }

    /**
     * The active potion effects, or {@code null} when they could not be READ.
     *
     * <p>Null rather than an empty list, and that is the whole point of this method existing. This
     * used to swallow a Throwable and return whatever had accumulated -- {@code List.of()} in
     * practice -- so "no effects" and "the read failed" arrived at the differ identically, and
     * {@code WorldViewDiff} then reported EVERY live effect as lost. A model reading that concludes
     * its fire resistance just ran out; next to lava that is a fatal direction to be wrong in.
     * {@link WorldViewDiff#diff} named this gap in its own javadoc and said the fix had to be the
     * capture reporting whether it managed to read, which is this.
     *
     * <p>Same shape as {@link #boxedInt} and for the same reason: absence is the payload's existing
     * word for "no value", and the alternative -- an in-band sentinel like an effect with id -1 --
     * would put a fake effect in a list callers iterate.
     *
     * <p>An all-or-nothing result, not a partial one. A throw partway through the loop discards
     * what was collected, because a HALF list is the dangerous case rather than the safe one: the
     * effects it is missing would each report lost, which is exactly the false ending this exists to
     * prevent. Better to say "could not read" than to hand over a list that is quietly incomplete.
     *
     * <p>Takes a supplier rather than the player so the failure branch is reachable without a live
     * one -- the same reason {@link #nearestWithinCap} and {@link #boxedInt} are package-private.
     * The real trigger is a {@code DataWatcher}/effect map in a state the getter throws on, which a
     * test can hand over verbatim instead of approximating.
     */
    static List<SelfView.Effect> effectsOrNull(java.util.function.Supplier<Iterable<?>> src) {
        try {
            List<SelfView.Effect> out = new ArrayList<>();
            for (Object o : src.get()) {
                if (o instanceof PotionEffect pe) {
                    int id = pe.getPotionID();
                    String name;
                    try {
                        name = Potion.potionTypes[id] != null ? Potion.potionTypes[id].getName() : "potion." + id;
                    } catch (Throwable t) {
                        name = "potion." + id;
                    }
                    out.add(new SelfView.Effect(id, name, pe.getAmplifier(), pe.getDuration()));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Every entity within the profile's range, UNCAPPED.
     *
     * <p>Split from the capping step so the caller can see both counts from one scan and report
     * whether the cap actually dropped anything. The old shape returned the already-truncated list,
     * which meant the fact "something alive was dropped" existed only inside this method and could
     * not reach the wire -- and that fact is the difference between {@code left} meaning "it died"
     * and {@code left} meaning "a nearer mob pushed it out of the list".
     */
    private static List<EntityView> entitiesInRange(EntityPlayerSP p, WorldClient w,
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
        // Uncapped on purpose -- the caller applies nearestWithinCap and compares the two sizes.
        return out;
    }

    /**
     * The entities section exactly as it reaches the view: {@code null} when nothing was scanned,
     * the nearest {@code cap} otherwise.
     *
     * <p>Package-private for the same reason {@link #nearestWithinCap} and {@link #boxedInt} are: the
     * property worth pinning lives on a path that otherwise needs a live client, and a mutation
     * survives forever if no test can reach it. Specifically -- {@code null} must PROPAGATE rather
     * than become an empty list, because an empty list is a claim about the world ("nothing nearby")
     * while null is a statement about the sampling ("nobody looked"), and the differ turns the first
     * into a report that every previously known entity has left.
     *
     * <p>Written as a separate method rather than inline in {@code capture} after a mutation run
     * showed the inline version was unreachable: replacing {@code : null} with {@code : List.of()}
     * -- i.e. restoring the original defect -- left every test green.
     */
    static List<EntityView> entitiesSection(List<EntityView> found, int cap) {
        return found == null ? null : nearestWithinCap(found, cap);
    }

    /**
     * Whether the cap actually dropped something the scan had found.
     *
     * <p>Measured by comparing the two sizes from ONE scan, not inferred from {@code size() == cap}:
     * a scan that legitimately found exactly {@code cap} entities dropped none, so the inference
     * would report an eviction that never happened. That distinction is the whole reason the flag
     * exists -- it tells a caller whether an id in {@code entities.left} might be a live mob pushed
     * out of the list rather than one that died.
     */
    static boolean capTruncated(List<EntityView> found, int cap) {
        return found != null && found.size() > entitiesSection(found, cap).size();
    }

    /**
     * Sort by distance and keep only the nearest {@code cap}.
     *
     * <p>Extracted so the truncation is reachable without a world, the same reason
     * {@link LocalGrid} keeps its fall arithmetic in a separate method. It is worth reaching:
     * this is where an entity that is alive and adjacent silently stops being reported, and
     * {@code world_view mode=diff} then lists it under {@code entities.left} exactly as it lists a
     * departure. The test that claimed to cover that used to hand-build two views with different ids
     * and never applied a cap at all, so it proved only that a differ notices a missing id -- this
     * whole path could have stopped feeding the differ and it would have stayed green.
     *
     * <p>Nearest-wins rather than first-seen: {@code loadedEntityList} is in no useful order, so
     * without the sort the cap would drop an arbitrary subset instead of the far ones.
     */
    static List<EntityView> nearestWithinCap(List<EntityView> found, int cap) {
        List<EntityView> out = new ArrayList<>(found);
        out.sort((a, b) -> Double.compare(a.dist(), b.dist()));
        if (out.size() > cap) {
            out = new ArrayList<>(out.subList(0, cap));
        }
        return out;
    }

    /**
     * The inventory section, or {@code null} when it could not be READ.
     *
     * <p>All-or-nothing, exactly like {@link #effectsOrNull} and for the same reason. This used to
     * accumulate slots inside one try and return whatever had collected when a throw landed, and
     * {@link WorldViewDiff} compares slot INDEX sets -- so every slot after the failure point
     * reported {@code cleared}, which a model reads as "these slots are empty". A half list is the
     * dangerous case rather than the safe one: the caller is told it lost the pickaxe it is holding,
     * and the next thing it does is plan around a kit it never lost. Null says "could not read",
     * which is a state the differ already knows how to carry.
     *
     * <p>Takes a supplier rather than the player so the failure branch is reachable without a live
     * one -- the same reason {@link #effectsOrNull}, {@link #nearestWithinCap} and {@link #boxedInt}
     * are package-private. The real trigger is a {@code mainInventory} in a state the getters throw
     * on (a {@code ReportedException} out of a corrupt stack), which a test can hand over verbatim
     * instead of approximating.
     *
     * <p>The supplier yields the WHOLE array, empty slots included, because the slot index is the
     * differ's key: a source that skipped its nulls would renumber the kit and report every slot
     * after the first gap as changed.
     *
     * @param selectedSlot the held hotbar index, read by the caller -- it comes from a different
     *                     field than the array, and is passed in so this method owns one decision
     */
    static InventoryView inventoryOrNull(java.util.function.Supplier<Iterable<ItemStack>> src,
                                         int selectedSlot) {
        return inventoryOrNull(src, selectedSlot, null);
    }

    /**
     * As above, and additionally reading the four worn armour pieces.
     *
     * <p>The armour block is supplied separately and labelled with vanilla's OWN indices
     * ({@code 36 + armourType}), not with a renumbered 0..3. Renumbering would make the field look
     * like a fifth kind of main-inventory slot and invite a caller to treat 0 as "the first
     * armour slot", which is the helmet in {@code armorInventory} but the BOOTS as a window-0
     * container slot -- see {@link ArmourSlots}. Keeping vanilla's index makes the two answers
     * reconcilable against {@code InventoryPlayer.getStackInSlot} by anyone who checks.
     *
     * @param armourSupplier yields {@code armorInventory} in armourType order, or {@code null}
     *                       when the caller did not read it
     */
    static InventoryView inventoryOrNull(java.util.function.Supplier<Iterable<ItemStack>> src,
                                         int selectedSlot,
                                         java.util.function.Supplier<Iterable<ItemStack>> armourSrc) {
        try {
            List<InventoryView.Slot> slots = new ArrayList<>();
            int index = 0;
            for (ItemStack st : src.get()) {
                if (st == null) {
                    index++;
                    continue;
                }
                slots.add(slot(index, st));
                index++;
            }
            List<InventoryView.Slot> armour = null;
            if (armourSrc != null) {
                armour = new ArrayList<>(ArmourSlots.COUNT);
                for (ItemStack st : armourSrc.get()) {
                    // Every cell is published, occupied or not: four entries is the shape, and a
                    // sparse list would make the absent cell and the unread cell indistinguishable.
                    armour.add(st == null
                            ? new InventoryView.Slot(ArmourSlots.inventoryIndexFor(armour.size()),
                                    null, 0, 0, null)
                            : slot(ArmourSlots.inventoryIndexFor(armour.size()), st));
                }
            }
            return new InventoryView(selectedSlot, slots, armour);
        } catch (Throwable t) {
            return null;
        }
    }

    private static InventoryView.Slot slot(int index, ItemStack st) {
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
        return new InventoryView.Slot(index, name, st.stackSize, st.getItemDamage(), maxDmg);
    }

    private static InventoryView inventory(EntityPlayerSP p) {
        try {
            // Arrays.asList, not List.of: the array holds null for every empty slot and List.of
            // refuses them, which would make "most of the inventory is empty" read as a failed read.
            // The armour block is read from the SAME array by vanilla's own index arithmetic
            // (getStackInSlot:639-650 subtracts mainInventory.length past 36), so it is captured
            // from armorInventory directly rather than by re-deriving it here.
            return inventoryOrNull(() -> java.util.Arrays.asList(p.inventory.mainInventory),
                    p.inventory.currentItem,
                    () -> java.util.Arrays.asList(p.inventory.armorInventory));
        } catch (Throwable t) {
            // Same all-or-nothing answer when even the array or the held-slot index was unreachable:
            // a defaulted selectedSlot beside a full kit would be a second, quieter lie.
            return null;
        }
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
        // Weather and the light at the player's own cell. Each read is isolated: a world that
        // throws on one of them must still produce the others rather than an EnvView full of
        // zeroes, which would read as "clear noon" and be exactly the wrong answer in a storm.
        boolean raining = false;
        boolean thundering = false;
        boolean daytime = false;
        int lightAtPlayer = -1;
        try {
            raining = w.isRaining();
        } catch (Throwable ignored) {
        }
        try {
            thundering = w.isThundering();
        } catch (Throwable ignored) {
        }
        try {
            // Daylight.isDaytime(worldTime), NOT World.isDaytime(). The rule is still
            // `skylightSubtracted < 4` -- World.isDaytime is not an hour test, and this is not one
            // either -- but it is DERIVED from the clock above rather than read off the world.
            //
            // Why that matters, measured: World.isDaytime is `skylightSubtracted < 4` against a
            // field with exactly two writers in the whole vendored tree, and on a client only one
            // of them ever runs -- calculateInitialSkylight() in the WorldClient constructor
            // (WorldClient.java:59). WorldServer.tick's refresh (WorldServer.java:197-202) is
            // server-side. So a client world built at worldTime 0 holds skylightSubtracted 0 and
            // answers "day" at worldTime 18000, where vanilla's own curve says 11. This line was
            // the only production read of "is it night", and it said no, forever.
            daytime = Daylight.isDaytime(time);
        } catch (Throwable ignored) {
        }
        try {
            // The same conjunction the spawn gate compares: getLightSubtracted(pos,
            // skylightSubtracted). The player's own position, floored -- int() and floor() differ
            // for negative coordinates and this repo has shipped that bug once.
            var feet = new BlockPos(Math.floor(base.getX()), Math.floor(base.getY()),
                    Math.floor(base.getZ()));
            int storedSky = w.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, feet);
            int storedBlock = w.getLightFromNeighbors(feet);
            lightAtPlayer = BlockInspector.lightAtPosition(storedSky, storedBlock,
                    BlockInspector.skylightAmountFor(w, thundering));
        } catch (Throwable ignored) {
        }
        return new EnvView(dim, biome, timeBucket(time), time, raining, thundering, daytime,
                lightAtPlayer);
    }

    /**
     * The time-of-day bucket for a world time -- the ONE copy, shared with {@code WorldScanner}.
     *
     * <p>There were two, and they disagreed on every negative instant: this one normalises the
     * modulus, the scanner's took {@code t % 24000} raw. Java's remainder keeps the sign of the
     * dividend, so a world time of {@code -6000} bucketed here as 18000 ("night") and there as
     * -6000 ("sunrise") -- the two observation tools contradicting each other about the same
     * instant, with nothing on the wire saying which to believe. {@code /time set} and a restored
     * save can both put the counter below zero, so this is a real world time rather than a
     * hypothetical one. One copy, called by both, makes disagreement unrepresentable.
     */
    static String timeBucket(long t) {
        long d = timeOfDay(t);
        if (d < 1000) return "sunrise";
        if (d < 6000) return "day";
        if (d < 12000) return "noon-afternoon";
        if (d < 13000) return "sunset";
        if (d < 23000) return "night";
        return "sunrise";
    }

    /**
     * The tick within the 24000-tick day, normalised into {@code [0, 24000)}.
     *
     * <p>The reduction itself, split out so every reader of world time shares it. A third copy
     * turned up in {@code HighValueSummarizers}, which used {@code Math.abs(wt) % 24000}: that is
     * not a modular reduction at all. {@code Math.abs} folds the sign before the modulus, so world
     * time -1 reports {@code tod=1} — the same instant as world time +1, and 23999 ticks away from
     * where it actually is in the cycle. The packet summariser is the tool an operator reads to
     * see what the server is doing, and it was answering a different question from the two
     * observation tools beside it.
     */
    public static long timeOfDay(long t) {
        return ((t % 24000L) + 24000L) % 24000L;
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

    interface IntSup {
        int get();
    }

    private interface FloatSup {
        float get();
    }

    /**
     * Read failure as {@code -1}, for the fields where vanilla cannot produce {@code -1}.
     *
     * <p>Audited one field at a time against vanilla rather than kept on the assumption that
     * negatives are impossible, because the assumption was false for exactly one of them
     * (air, now on {@link #boxedInt}). The rest are in-band-safe, and each for its own reason,
     * so the reason is recorded rather than the conclusion:
     * <ul>
     *   <li>{@code health} — every client-side write goes through
     *       {@code EntityLivingBase.setHealth}, which clamps to {@code [0, maxHealth]}
     *       ({@code :857}); the only other writer, {@code EntityPlayerSP.setPlayerSPHealth}
     *       ({@code :346-372}), calls it. Floor is {@code 0}, i.e. dead.</li>
     *   <li>{@code food}/{@code saturation} — {@code FoodStats} arithmetic is
     *       {@code Math.max(x-1, 0)} and {@code Math.min(x, 20)} ({@code :29-30, :51-56}), so
     *       both floor at {@code 0}.</li>
     *   <li>{@code xpLevel}/{@code xpProgress} — {@code EntityPlayer.addExperienceLevel}
     *       resets a negative level to {@code 0} and zeroes progress with it
     *       ({@code :2040-2045}).</li>
     *   <li>{@code armor} — a sum of {@code ItemArmor.damageReduceAmount}, all non-negative.</li>
     * </ul>
     *
     * <p>Deliberately NOT boxed alongside air just to make the five look alike: a representation
     * that is already unambiguous costs a reader nothing, while four more nullable fields would
     * put a null check in front of every consumer to buy no new information.
     */
    private static int safeInt(IntSup s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Read failure as {@code null}, for air, whose whole negative range is in-band.
     *
     * <p>Absence rather than a fresh sentinel because there is no free integer to pick: air is
     * a short that vanilla walks from 300 down past 0 into the negatives (see
     * {@link SelfView#air}). Absence is also already the payload's word for "no value" --
     * {@code surfaceDy} and {@code drop} both use it, and the grid legend documents absence as
     * load-bearing -- so this reuses the existing convention instead of adding a second one the
     * description would have to teach separately.
     *
     * <p>Package-private, the same reason {@link #nearestWithinCap} is: the failure branch has to
     * be reachable without a live player. The real trigger is a {@code DataWatcher} with no entry
     * for air, which is what a test can hand it verbatim rather than approximate.
     */
    static Integer boxedInt(IntSup s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
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
