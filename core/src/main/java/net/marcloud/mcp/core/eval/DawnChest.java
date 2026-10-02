package net.marcloud.mcp.core.eval;

import java.util.Locale;

import net.minecraft.block.Block;
import net.marcloud.mcp.core.drivers.world.Daylight;

/**
 * Was a chest standing through a NIGHT, and what does the fourth north-star row now actually
 * measure.
 *
 * <p><b>What this class is for.</b> The Owner's north star is <em>a shelter, health never below 18,
 * a box standing at dawn</em>. Two of those three were measurable ({@link Enclosure},
 * {@link SurvivalDamage}) and the third was listed in {@code SimWorld.KNOWN_GAPS} as a gap: a chest
 * does not open here, and a chest that does not open has no inventory. This class is the
 * instrument half of closing that gap, and it deliberately refuses to close the other half.
 *
 * <p><b>"Standing at dawn" names two different claims, and this class measures both.</b>
 *
 * <ul>
 *   <li>{@link #standingAtDawn()} -- a chest is in the cell on the LAST tick vanilla's own curve
 *       calls night. This is the literal reading of the phrase and it is the weaker one: a chest
 *       placed one tick before dawn passes it.</li>
 *   <li>{@link #heldThroughout()} -- a chest was in the cell on <em>every</em> sampled night tick.
 *       This is the region reading, and it is the one that can come back false for a player who
 *       built the box at three in the morning.</li>
 * </ul>
 *
 * They are reported separately and neither is allowed to stand in for the other, for the reason
 * {@link NightEnclosure} gives about a roof at dawn: a criterion satisfied by a single instant is a
 * constant that agrees with any world it is handed. A report should print both, so a reader can see
 * which of the two decided the row -- and the north-star phrase as written does not say which it
 * means. That ambiguity is a finding, recorded in the report, not a defect hidden here.
 *
 * <p><b>Which destruction can happen in these worlds, and the answer decides what the row
 * measures.</b> The brief's four candidates are fire, lava, an explosion and a creeper, and all
 * four were checked against the vendored tree rather than assumed:
 *
 * <ul>
 *   <li><b>Fire.</b> Two independent reasons it cannot. No eval world ever places a fire block
 *       (there is no fire in {@code SimWorld} at all -- the fire that appears in the survival
 *       ledger is the player's own burn CLOCK, {@code SurvivalDamage.Source.FIRE}, not a block),
 *       and in 1.8.9 a chest is not flammable anyway: {@code BlockFire.init()} registers 36
 *       flammable blocks ({@code BlockFire.java:74-108}) and {@code Blocks.chest} is not among
 *       them, so {@code BlockFire.getEncouragement} returns 0 for it
 *       ({@code BlockFire.java:281-285}).</li>
 *   <li><b>Lava.</b> In 1.8.9 lava destroys no blocks at all: {@code BlockLiquid} contains zero
 *       calls to {@code setBlockToAir}, so a chest beside a lava band is a chest beside a lava band
 *       in the real game too, not only in a world with no liquid tick.</li>
 *   <li><b>Creeper and explosions.</b> {@code SimMob.kinds()} offers zombie and spider and refuses
 *       creeper by name, and no eval world calls {@code Block.getExplosionResistance}.</li>
 *   <li><b>The player.</b> The only thing in this substrate that can remove a chest from the grid
 *       is the player's own dig ({@code SimWorld.pumpDig}, {@code SimWorld.instantBreak}) or the
 *       fixture's own {@code remove}.</li>
 * </ul>
 *
 * So the "it held" half of the north star, measured here, is honestly: <em>the agent did not dig
 * out its own box</em>. That is a real and falsifiable claim, and it is much narrower than "storage
 * survives a night". A reader who quotes this row as evidence that chests survive fires is quoting
 * something this world never tested.
 *
 * <p><b>What is NOT measured, and why there is no accessor for it.</b> The third part of the
 * criterion -- "what is in it survived the night" -- has no method on this class, deliberately. A
 * {@code contentsIntact()} returning a constant would be exactly the defect this slice exists to
 * stop: a capability documented as doing something whose producer is absent. Two independent
 * reasons, each checkable:
 *
 * <ul>
 *   <li><b>There is no container.</b> {@code SimWorld} holds a {@code Map<Long, Block>} grid and an
 *       {@code InventoryPlayer}, and no reference to {@code TileEntity} or {@code IInventory}
 *       anywhere in the eval tree. A chest here is a block with nothing behind it, so "what is in
 *       it" has no answer to give.</li>
 *   <li><b>Even with a container, 1.8.9 does not decay a chest's contents.</b> The only decay
 *       clock in the vendored tree is {@code InventoryPlayer.decrementAnimations}
 *       ({@code InventoryPlayer.java:352-362}), which walks the PLAYER's {@code mainInventory}, is
 *       called from {@code EntityPlayer.java:617}, and reaches {@code Item.onUpdate} through
 *       {@code ItemStack.updateAnimation} ({@code ItemStack.java:486-494}). {@code ItemFood}
 *       overrides none of it -- there is no {@code onUpdate} in that file at all -- so this tree
 *       has no food-spoilage mechanic, and a chest is not on the player's inventory in the first
 *       place.</li>
 * </ul>
 *
 * <p><b>What actually empties a chest in 1.8.9, and why that clock is the one that matters.</b> A
 * break: {@code BlockChest.breakBlock} calls {@code InventoryHelper.dropInventoryItems}
 * ({@code BlockChest.java:414-425}, {@code InventoryHelper.java:15-16}), which spawns
 * {@code EntityItem}s ({@code InventoryHelper.java:54}). An {@code EntityItem} then dies at
 * {@code age >= 6000} ({@code EntityItem.java:145-147}) while a night on this tree is
 * {@link NightEnclosure#nightTicks()} = 8386 ticks. So a stack that leaves a chest on the floor at
 * dusk is gone well before dawn -- 6000 < 8386, a margin of 2386 ticks. That is the real shape of
 * "what is in it survived the night", and it is not measurable here either: the substrate has no
 * {@code EntityItem} and {@code SimWorld.droppedOntoFloor} is an ageless list. Both numbers are
 * quoted in the audit report with the lines they were read from; neither is transcribed here,
 * because a transcribed constant nothing exercises is a number with no caller.
 *
 * <p><b>The window is {@link Daylight}'s, sampled on the same rule as the shelter's.</b> A sample is
 * taken only when {@link Daylight#isNight(long)} says the clock is on the far side of dusk, which is
 * the same call {@code SimWorld.isNight()} makes, so there is one rule about when night begins. The
 * boundaries are asked of {@link NightEnclosure#duskTick()} and {@link NightEnclosure#nightTicks()}
 * rather than restated, so this class cannot be the second place that rule is written down.
 *
 * <p><b>Cost.</b> The cell read is {@link Enclosure.CellGrid#at}, a plain lookup on data the world
 * already holds and never {@code ActActuator.blockAt}, so sampling on every tick of a night costs
 * the controller seam nothing -- measured by {@code TheDawnChestCostsTheWalkNoWorldReadTest} rather
 * than argued here.
 */
public final class DawnChest {

    /** One sample per tick. The default, and the reason {@link #DawnChest(int)} exists. */
    public static final int DEFAULT_PERIOD_TICKS = 1;

    private final int periodTicks;
    private int sinceLastSample;

    private int samples;
    private int presentSamples;
    private int missingSamples;
    private int currentMissingRun;
    private int longestMissingRun;
    private boolean presentOnLastSample;

    private long firstNightTick = -1L;
    private long lastNightTick = -1L;
    private long firstMissingTick = -1L;

    public DawnChest() {
        this(DEFAULT_PERIOD_TICKS);
    }

    /**
     * @param periodTicks ticks between samples; 1 means every tick, and anything larger can miss a
     *                    shorter absence, which is why the default is 1
     */
    public DawnChest(int periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("sampling period must be at least one tick, got "
                    + periodTicks);
        }
        this.periodTicks = periodTicks;
    }

    /**
     * Records one tick of a run: what the cell holds, if the clock says it is night.
     *
     * <p>A tick outside the night is not counted at all, which is what lets a caller drive the whole
     * night without deciding for itself where the window starts.
     *
     * @return whether a sample was taken
     */
    public boolean observe(Enclosure.CellGrid grid, int bx, int by, int bz, long worldTime) {
        if (!Daylight.isNight(worldTime)) {
            return false;
        }
        // Same countdown as NightEnclosure, for the same reason: an instrument that skipped its own
        // first sample would shorten every window by a period, which is a quiet way to be wrong.
        if (sinceLastSample > 0) {
            sinceLastSample--;
            return false;
        }
        sinceLastSample = periodTicks - 1;
        samples++;
        if (firstNightTick < 0L) {
            firstNightTick = worldTime;
        }
        lastNightTick = worldTime;
        boolean present = isChest(grid.at(bx, by, bz));
        presentOnLastSample = present;
        if (present) {
            presentSamples++;
            currentMissingRun = 0;
        } else {
            missingSamples++;
            currentMissingRun++;
            if (currentMissingRun > longestMissingRun) {
                longestMissingRun = currentMissingRun;
            }
            if (firstMissingTick < 0L) {
                firstMissingTick = worldTime;
            }
        }
        return true;
    }

    /**
     * Whether the cell holds a chest, asked about the BLOCK rather than pattern-matched on its
     * name, the way {@code SimWorld.opensCraftingScreen} asks about a crafting table.
     *
     * <p>{@code instanceof BlockChest} rather than {@code == Blocks.chest} on purpose, and it is
     * also the more correct of the two: {@code Blocks.trapped_chest} is a {@code BlockChest(1)}
     * ({@code Block.java:1408}) and opens a {@code ContainerChest} exactly like a plain one
     * ({@code BlockChest.java:427-453}), so a trapped chest is a box and is counted as one.
     */
    public static boolean isChest(Block b) {
        return b instanceof net.minecraft.block.BlockChest;
    }

    /** Whether any night tick was sampled at all. False means the claim was not measured. */
    public boolean measured() {
        return samples > 0;
    }

    /**
     * The LITERAL reading: a chest is in the cell on the last sampled night tick.
     *
     * <p>Requires {@link #measured()} first, so a clock that never reached night reports
     * <em>not measured</em> rather than <em>no box</em> -- the same discipline
     * {@link NightEnclosure#sheltered()} and {@code EvalSuite.survived} already apply.
     */
    public boolean standingAtDawn() {
        return measured() && presentOnLastSample;
    }

    /**
     * The REGION reading: a chest was in the cell on every sampled night tick.
     *
     * <p>This is the half that can come back false for a box built at 3am, and it is the half a
     * report should quote, because the point reading is satisfiable by a chest that existed for one
     * tick.
     */
    public boolean heldThroughout() {
        return measured() && missingSamples == 0;
    }

    /** How many night ticks were sampled, which is the resolution of the window. */
    public int samples() {
        return samples;
    }

    public int presentSamples() {
        return presentSamples;
    }

    public int missingSamples() {
        return missingSamples;
    }

    public int periodTicks() {
        return periodTicks;
    }

    /**
     * The longest unbroken run of night ticks with no chest in the cell.
     *
     * <p>In ticks, not samples, so a caller who coarsened the instrument is not silently reporting
     * an undercount -- the same argument {@link NightEnclosure#longestUnreachableTicks()} makes.
     */
    public long longestAbsentTicks() {
        return (long) longestMissingRun * periodTicks;
    }

    /** The clock tick of the first night sample, or -1 if nothing was sampled. */
    public long firstNightTick() {
        return firstNightTick;
    }

    /** The clock tick of the last night sample, or -1 if nothing was sampled. */
    public long lastNightTick() {
        return lastNightTick;
    }

    /** The clock tick of the first tick with no chest, or -1 if the cell never lacked one. */
    public long firstAbsentTick() {
        return firstMissingTick;
    }

    /**
     * The sentence a result row carries: the window, the resolution, both verdicts, and the two
     * parts of the criterion that are deliberately not measured.
     *
     * <p>The trailing sentence is not decoration. A row that printed only {@code standing=true}
     * would read as "a box survived the night" and would be quoting a world in which nothing can
     * destroy a chest except the agent itself, against contents that do not exist. That is the
     * defect this class exists to keep out of a report.
     */
    public String fact() {
        if (!measured()) {
            return "box: NOT MEASURED -- no night tick was sampled, so this row says nothing about"
                    + " a box. A clock with doDaylightCycle off never reaches night, and a window"
                    + " measured over zero ticks must not report one";
        }
        return String.format(Locale.ROOT,
                "box: %s -- %d night sample(s) at %d tick(s) per sample, clock %d..%d; a chest was in"
                        + " the cell on %d of them and absent on %d; at the last sampled tick: %s;"
                        + " throughout: %s%s. This measures whether the AGENT dug out its own box:"
                        + " in these worlds nothing else can remove a chest (no fire, and a chest is"
                        + " not flammable in 1.8.9; lava destroys no blocks; no creeper, no"
                    + " explosion), and there is no container behind it, so its CONTENTS are not"
                    + " measured at all",
                heldThroughout() ? "MEASURED, a chest stood throughout"
                        : "MEASURED, the cell lacked a chest at some point",
                samples, periodTicks, firstNightTick, lastNightTick, presentSamples, missingSamples,
                standingAtDawn() ? "standing" : "gone",
                heldThroughout() ? "standing" : "gone at some point",
                missingSamples == 0 ? "" : ", first absent at clock " + firstMissingTick);
    }

    @Override
    public String toString() {
        return fact();
    }
}
