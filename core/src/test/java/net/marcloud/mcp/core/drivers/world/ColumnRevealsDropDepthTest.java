package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * A column must distinguish a harmless drop from a lethal one.
 *
 * <p><b>The defect, and why it lands exactly on the worst number.</b>
 * {@link LocalGrid#sampleColumnar} finds a surface by scanning top-down inside
 * {@code [-vBelow, +vAbove]} and leaves {@code surfaceDy} null when the window holds only air.
 * {@code EXPLORE} sets {@code vBelow = 3} ({@link ObserveProfile}). Vanilla fall damage is
 * {@code ceil(distance - 3 - jumpBoost)}
 * ({@code client/src/main/java/net/minecraft/entity/EntityLivingBase.java:1156}). So three blocks
 * is simultaneously the deepest drop the grid can see AND the largest drop that costs nothing:
 * every fall the agent should care about is reported identically to a bottomless void.
 *
 * <p>That is not a resolution complaint. It is the single confirmed gap in
 * {@code docs/agency/command-to-action.md}, and it means an agent cannot tell "step down here" from
 * "die here" using the observation it is given.
 *
 * <p><b>Why the depth is a separate term from {@code surfaceDy}.</b> Widening {@code vBelow} would
 * cost a full column scan per extra layer at every one of the (2r+1)^2 columns, and would still
 * answer the wrong question -- the caller does not want the terrain profile of a shaft, only how
 * far the fall is and whether it hurts. A bounded downward probe that reports one integer keeps the
 * grid columnar, which is the property {@code LocalGrid} was built for.
 *
 * <p>Asserted through the record's accessors reflectively so the failure names the missing term
 * rather than being a compile error, which is what a direct call would produce before the change.
 */
public class ColumnRevealsDropDepthTest {

    private static Method accessor(String name) {
        for (Method m : LocalGrid.Column.class.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    @Test
    public void aColumnCarriesHowFarTheDropIs() {
        assertNotNull("LocalGrid.Column must carry a drop depth. Without it every fall deeper than "
                + "vBelow (3 on EXPLORE) reports surfaceDy=null and surface=\"air\", so a harmless "
                + "3-block step down and a 40-block shaft are the same observation -- and 3 is "
                + "exactly vanilla's fall-damage threshold, ceil(distance-3).",
            accessor("dropDepth"));
    }

    @Test
    public void aColumnCarriesAWalkabilityVerdict() {
        assertNotNull("LocalGrid.Column must carry a walkability class. Bare block names make the "
                + "agent maintain its own taxonomy of what can be walked on, walked through, or "
                + "kills you -- and vanilla already answers that: WalkNodeProcessor.func_176170_a "
                + "is public static and returns -1 water, -2 lava, -3 fence/wall, -4 closed "
                + "trapdoor, 0 solid, 1 clear, 2 wet. Measured callable against live terrain.",
            accessor("walk"));
    }

    @Test
    public void theDropDepthIsUnknownRatherThanZeroWhenNothingWasFound() {
        // Zero would be a lie in the most dangerous direction: it reads as "flat ground here".
        // The distinction has to survive into the type, so a caller cannot mistake one for the
        // other by reading an int.
        Method m = accessor("dropDepth");
        assertNotNull("dropDepth accessor must exist", m);
        assertEquals("dropDepth must be a boxed Integer so \"I probed and found no floor\" is "
                + "representable as null. A primitive int would force some sentinel, and 0 -- the "
                + "obvious one -- means flat ground, which is the opposite of a bottomless shaft.",
            Integer.class, m.getReturnType());
    }

    @Test
    public void theFallDepthIsCountedFromLandingOnTopOfTheFloor() {
        // A block at dy=-1 is the ground under your feet: zero fall. Getting this off by one would
        // make every ordinary step down look like a 1-block drop, and the whole point is the
        // boundary at 3 where damage starts.
        assertEquals("floor directly beneath means no fall",
            Integer.valueOf(0), LocalGrid.dropDepthOf(dy -> dy == -1 ? "stone" : "air", -1));
        assertEquals("floor two below means a 1-block drop",
            Integer.valueOf(1), LocalGrid.dropDepthOf(dy -> dy == -2 ? "stone" : "air", -1));
        assertEquals("the harmless maximum: vanilla starts hurting above 3",
            Integer.valueOf(3), LocalGrid.dropDepthOf(dy -> dy == -4 ? "stone" : "air", -1));
        assertEquals("and one deeper is the first damaging fall",
            Integer.valueOf(4), LocalGrid.dropDepthOf(dy -> dy == -5 ? "stone" : "air", -1));
    }

    @Test
    public void anOverheadBlockDoesNotMakeACliffLookFlat() {
        // The defect this replaced: dropDepth used to short-circuit to 0 whenever surfaceDy >= 0,
        // but surfaceDy comes from a scan that starts at the TOP of the window and takes the first
        // non-air block going down -- so it finds ceilings as readily as floors. A player at a cliff
        // edge under an overhang, or merely under a tree, hit the leaves and was told the cliff was
        // flat ground. Here the only solid block is far below; anything above must not matter.
        Integer d = LocalGrid.dropDepthOf(dy -> dy == -15 ? "stone" : (dy == 2 ? "leaves" : "air"), -1);
        assertEquals("a 14-block fall must be reported as such regardless of what is overhead",
            Integer.valueOf(14), d);
    }

    @Test
    public void aSubCubeFloorUnderfootIsNotAFallAtAll() {
        // Found by adversarial review, and it was the dangerous direction. The origin is FLOORED
        // from posY, so a player standing on a bottom slab at posY=63.5 has an origin of 63 -- which
        // IS the slab. Probing from dy=-1 looked past it: measured live on a slab bridge over a
        // 20-deep void, the player's own column reported drop=20 while surfaceDy correctly said 0,
        // so an agent would refuse to cross the bridge it was standing on.
        assertEquals("a floor at the feet layer itself means no fall",
            Integer.valueOf(0), LocalGrid.dropDepthOf(dy -> dy == 0 ? "floor" : "air", 0));
        assertEquals("and it must win over whatever is below it",
            Integer.valueOf(0),
            LocalGrid.dropDepthOf(dy -> (dy == 0 || dy == -20) ? "floor" : "air", 0));
        assertEquals("while a genuine void under the feet layer still reports deep",
            null, LocalGrid.dropDepthOf(dy -> "air", 0));
    }

    @Test
    public void aBottomlessColumnIsNullRatherThanTheProbeBound() {
        assertNotNull("sanity: a floor inside the bound is found",
            LocalGrid.dropDepthOf(dy -> dy == -20 ? "stone" : "air", -1));
        // Past the bound the honest answer is "no floor found", not the bound itself -- reporting
        // 24 would read as a survivable-with-mitigation number rather than "do not step here".
        assertEquals("nothing within the probe bound must be null, not a number",
            null, LocalGrid.dropDepthOf(dy -> "air", -1));
    }

    @Test
    public void aLethalDropAndAHarmlessStepDownAreNotTheSameObservation() throws Exception {
        // The property in the terms the caller reasons in. Built through the canonical constructor
        // reflectively for the same reason the accessors are looked up that way: a direct `new`
        // would not compile before the change, and a build error names no missing term.
        // sampleColumnar itself needs a live WorldClient, but the geometry it would produce for
        // these two cases is exactly this.
        Object harmless = column(-3, "stone", 3, 1);
        Object lethal = column(null, "air", 40, 1);

        assertNotEquals("a 3-block step down and a 40-block shaft must not be indistinguishable",
            describe(harmless), describe(lethal));
        assertTrue("and the lethal one must be identifiable as such: vanilla hurts above "
                + "ceil(distance-3), so anything past 3 costs health",
            depth(lethal) > 3 && depth(harmless) <= 3);
    }

    /** The canonical constructor, whatever its arity, so this test survives the change. */
    private static Object column(Integer surfaceDy, String surface, Integer dropDepth, int walk)
            throws Exception {
        var ctors = LocalGrid.Column.class.getDeclaredConstructors();
        assertEquals("Column should have exactly one canonical constructor", 1, ctors.length);
        var ctor = ctors[0];
        int n = ctor.getParameterCount();
        assertTrue("Column must have gained dropDepth and walk components; it still has " + n
                + " parameters, so the drop/lethality distinction cannot be represented at all", n >= 9);
        Object[] args = new Object[n];
        args[0] = 0;                    // dx
        args[1] = 0;                    // dz
        args[2] = surfaceDy;
        args[3] = surface;
        args[4] = "air";                // feet
        args[5] = "air";                // head
        args[6] = java.util.List.of();  // profile
        args[7] = dropDepth;
        args[8] = walk;
        return ctor.newInstance(args);
    }

    private static int depth(Object col) throws Exception {
        Object d = accessor("dropDepth").invoke(col);
        return d == null ? Integer.MAX_VALUE : (Integer) d;
    }

    private static String describe(Object col) throws Exception {
        return accessor("surface").invoke(col) + "/" + accessor("surfaceDy").invoke(col)
                + "/" + accessor("dropDepth").invoke(col) + "/" + accessor("walk").invoke(col);
    }
}
