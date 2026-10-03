package net.marcloud.mcp.core.rulers;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.eval.DawnChestRegion;
import net.marcloud.mcp.core.eval.Enclosure;
import net.marcloud.mcp.core.eval.SimWorld;

/**
 * A hand-driven {@link DawnChestRegion.Area} over a chunk of {@code Blocks}, so the region ledger
 * can be shown the SAME region twice with one block changed between the runs.
 *
 * <p><b>Why it wraps {@code SimWorld} rather than a live client.</b> Same reason as
 * {@link FakeVitals}: this file's job is to drive the PRODUCTION ledger over a region whose
 * contents are known exactly, including the case that matters most and is hardest to arrange live
 * -- a cell that stops being readable. The wiring test is what proves the ledger is the one a
 * running client reaches.
 *
 * <p><b>It can answer null for a reason, which is the whole point.</b> {@link #unreadable} is the
 * three-way-null boundary made drivable: air, an unloaded chunk and a failed read all arrive at
 * {@code grid.at} as null, so a live client cannot tell them apart, and neither can this fixture.
 * That is why {@link #unreadableCells} is a separate set rather than an absence from
 * {@link #blocks}: a cell that is absent from both reads as null too, and conflating the two here
 * would make a test that "proves" the unreadable path actually prove the demolished one.
 *
 * <p><b>It counts its own reads.</b> {@link #reads} is how the cost claim is a number rather than
 * an adjective, the same way {@code SimWorld.seamReads()} is used by the existing cost tests.
 */
final class FakeArea implements DawnChestRegion.Area {

    private final Object world;
    private final Map<Long, Block> blocks = new HashMap<>();
    private final java.util.Set<Long> unreadableCells = new java.util.HashSet<>();

    private long worldTime;
    private int feetX;
    private int feetY;
    private int feetZ;
    private int reads;

    FakeArea(Object world, long worldTime) {
        this.world = world;
        this.worldTime = worldTime;
    }

    @Override
    public Object world() {
        return world;
    }

    @Override
    public long worldTime() {
        return worldTime;
    }

    @Override
    public int feetX() {
        return feetX;
    }

    @Override
    public int feetY() {
        return feetY;
    }

    @Override
    public int feetZ() {
        return feetZ;
    }

    @Override
    public Enclosure.CellGrid grid() {
        return this::at;
    }

    private Block at(int x, int y, int z) {
        reads++;
        long k = key(x, y, z);
        if (unreadableCells.contains(k)) {
            // Null is what a live client gets for an unloaded chunk AND for a failed read. The
            // fixture cannot separate those either, which is exactly the boundary under test.
            return null;
        }
        return blocks.get(k);
    }

    /** How many cell reads the census and the re-verification have spent. */
    int reads() {
        return reads;
    }

    /** Put a block in the region, read by {@code SimWorld}'s own registry. */
    FakeArea put(int x, int y, int z, String blockName) {
        blocks.put(key(x, y, z), SimWorld.block(blockName));
        return this;
    }

    /** Take a block out of the region: the cell now reads null, like air or an unloaded chunk. */
    FakeArea remove(int x, int y, int z) {
        blocks.remove(key(x, y, z));
        return this;
    }

    /**
     * Make a cell unreadable while leaving a block in it, which is the case a live client cannot
     * tell apart from a demolition.
     */
    FakeArea unreadable(int x, int y, int z) {
        unreadableCells.add(key(x, y, z));
        return this;
    }

    FakeArea readable(int x, int y, int z) {
        unreadableCells.remove(key(x, y, z));
        return this;
    }

    /** Move the cube's centre, so the census looks somewhere else. */
    FakeArea feet(int x, int y, int z) {
        this.feetX = x;
        this.feetY = y;
        this.feetZ = z;
        return this;
    }

    FakeArea at(long time) {
        this.worldTime = time;
        return this;
    }

    FakeArea advance(int ticks) {
        this.worldTime += ticks;
        return this;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The face enum this file's tests use when they need to name one, for readability. */
    static ActActuator.Face up() {
        return ActActuator.Face.UP;
    }
}