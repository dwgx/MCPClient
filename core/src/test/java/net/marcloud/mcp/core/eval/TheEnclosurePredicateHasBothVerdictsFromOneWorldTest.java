package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;

/**
 * The enclosure predicate answers both ways, and each half can be the one that fails.
 *
 * <p><b>The claim being defended.</b> {@link Enclosure} exists to close a hole nobody had noticed:
 * until this slice the eval had no way to say whether the player had a shelter, and the north star
 * is a shelter. A predicate added to close such a hole is worthless unless it can say no. Every
 * measurement in this layer is asserted in the shape the previous slice used -- build one world,
 * read it two ways, and demand that the two readings disagree -- because a predicate that has only
 * ever agreed with itself has not been tested, it has merely been run.
 *
 * <p><b>Why "one world" and not two fixtures.</b> Two worlds differing in a wall could differ in
 * anything else as well, and a failure would not say which. One world read at two cells has exactly
 * one variable: where the body is. The sealed cell is built inside the open plain, so the two
 * readings come from the same grid, the same clock and the same {@code SimWorld.solid} rule.
 *
 * <p><b>What each test here is for.</b>
 * <ul>
 *   <li>{@link #theSameWorldGivesBothVerdictsAtTwoPositions} -- the headline: both verdicts, one
 *       world, one variable.</li>
 *   <li>{@link #removingOneRoofCellIsTheOnlyThingThatSeparatesThem} -- the negative control, in the
 *       shape the control-pair rule requires: plant the case the predicate must reject, prove it is
 *       rejected, remove the plant, prove it is accepted.</li>
 *   <li>{@link #aRoofWithoutWallsIsNotAShelterAndWallsWithoutARoofIsNotEither} -- neither half is
 *       allowed to stand in for the other. A definition that passed on either half alone would
 *       pass this.</li>
 *   <li>{@link #theWallsAreThePhysicsOwnSolidityRuleAndNotASecondCopy} -- the enclosure's idea of a
 *       wall and the physics' idea of a wall are the same predicate over every block in the
 *       registry, not two transcriptions that happen to agree today.</li>
 * </ul>
 */
public final class TheEnclosurePredicateHasBothVerdictsFromOneWorldTest {

    private static final int X = EvalSuite.T25ShelterThroughTheNight.SHELTER_X;
    private static final int Z = EvalSuite.T25ShelterThroughTheNight.SHELTER_Z;
    private static final int FEET_Y = EvalSuite.T25ShelterThroughTheNight.FEET_Y;
    private static final int ROOF_Y = EvalSuite.T25ShelterThroughTheNight.ROOF_Y;
    private static final int OPEN_X = EvalSuite.T25ShelterThroughTheNight.OPEN_X;
    private static final int OPEN_Z = EvalSuite.T25ShelterThroughTheNight.OPEN_Z;

    /**
     * The headline claim.
     *
     * <p>One {@code SimWorld}, two cells read, two different verdicts -- and the reason they
     * differ is printed in the failure, so a red here names the half rather than the row.
     */
    @Test
    public void theSameWorldGivesBothVerdictsAtTwoPositions() {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        Enclosure.CellGrid grid = w.enclosureGrid();

        Enclosure.Verdict inside = Enclosure.at(grid, X, FEET_Y, Z);
        assertTrue("the sealed cell is the north star's shape: four walls at both body heights and"
                + " a roof over the head, so it is sky-closed and side-closed and therefore"
                + " enclosed. Verdict was " + inside.describe(), inside.enclosed());
        assertTrue("and the sky half on its own: " + inside.describe(), inside.skyClosed());
        assertTrue("and the side half on its own: " + inside.describe(), inside.sideClosed());

        Enclosure.Verdict outside = Enclosure.at(grid, OPEN_X, FEET_Y, OPEN_Z);
        assertFalse("THE CLAIM. The very same world, the very same grid, read at a cell on the"
                + " open plain, is NOT a shelter -- otherwise the predicate can only ever agree"
                + " with the fixture that built it. Verdict was " + outside.describe(),
                outside.enclosed());
        assertFalse("and it fails the sky half first, because there is nothing at all above that"
                + " column up to y=255. Verdict was " + outside.describe(), outside.skyClosed());
        assertEquals("and all eight of its horizontal neighbours are air, four sides at the feet"
                + " cell and the same four at the head cell", 8, outside.wallsMissing());
    }

    /**
     * The control pair: plant the case the predicate must reject, then remove the plant.
     *
     * <p>One cell is taken out of the roof. Everything else in the world is identical, and the
     * player has not moved, so if the removal is what changes the verdict then the verdict was
     * about the roof and not about anything else in the world.
     */
    @Test
    public void removingOneRoofCellIsTheOnlyThingThatSeparatesThem() {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        Enclosure.CellGrid grid = w.enclosureGrid();
        assertTrue("the control only means something if the shipped geometry passes to begin with",
                Enclosure.at(grid, X, FEET_Y, Z).enclosed());

        // ---- the plant ----
        w.remove(X, ROOF_Y, Z);
        Enclosure.Verdict holed = Enclosure.at(grid, X, FEET_Y, Z);
        assertFalse("a roof with a hole in it over the player's own column is a cover, not a"
                + " shelter: the column is open to the sky again, so a cell a body cannot enter"
                + " is not a claim the player can stand under. Verdict was " + holed.describe(),
                holed.enclosed());
        assertFalse("and it is the SKY half that failed, not the walls -- which is the half a"
                + " 'can the mob reach it' intuition would have missed", holed.skyClosed());
        assertTrue("while the eight sides are still closed, so the two halves really are"
                + " independent: " + holed.describe(), holed.sideClosed());

        // ---- the plant removed ----
        w.put(X, ROOF_Y, Z, "stone");
        Enclosure.Verdict restored = Enclosure.at(grid, X, FEET_Y, Z);
        assertTrue("putting the one cell back is the only thing that separates the two runs, so"
                + " the verdict was about the roof. Verdict was " + restored.describe(),
                restored.enclosed());
    }

    /**
     * A roof without walls is not a shelter, and walls without a roof is not either.
     *
     * <p>This is the test that pins the definition rather than the fixture. If either half alone
     * were enough, one of these two would come back enclosed -- and the answer a player means by
     * "I had a shelter" requires both, because a body in a roofed pen in the open can be reached
     * from every side and a body at the bottom of a sealed shaft can see every star.
     */
    @Test
    public void aRoofWithoutWallsIsNotAShelterAndWallsWithoutARoofIsNotEither() {
        SimWorld roofOnly = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int y = FEET_Y; y <= ROOF_Y; y++) {
                roofOnly.remove(X + side[0], y, Z + side[1]);
            }
        }
        Enclosure.Verdict roofButNoWalls = Enclosure.at(roofOnly.enclosureGrid(), X, FEET_Y, Z);
        assertTrue("the roof is still there", roofButNoWalls.skyClosed());
        assertEquals("but all eight sides of the two cells the body occupies are open, so this is"
                + " a roof and not a shelter", 8, roofButNoWalls.wallsMissing());
        assertFalse("and the predicate has to say so", roofButNoWalls.enclosed());

        SimWorld wallsOnly = EvalSuite.T25ShelterThroughTheNight.buildWorld().remove(X, ROOF_Y, Z);
        Enclosure.Verdict wallsButNoRoof = Enclosure.at(wallsOnly.enclosureGrid(), X, FEET_Y, Z);
        assertTrue("the eight sides are still closed", wallsButNoRoof.sideClosed());
        assertFalse("but the player's own column is open to y=255, and that is the other half of"
                + " the definition", wallsButNoRoof.skyClosed());
        assertFalse("and the predicate has to say so", wallsButNoRoof.enclosed());
    }

    /**
     * The enclosure's wall is the physics' wall.
     *
     * <p>{@link Enclosure.CellGrid#solidAt} restates {@code SimWorld.solid}'s rule rather than
     * reaching for it, and a restatement is exactly the kind of thing that is right today and
     * disagrees next month. So the two are compared over every block the registry holds: if a
     * future transcription adds a case to one and not the other, this goes red and names the
     * block.
     */
    @Test
    public void theWallsAreThePhysicsOwnSolidityRuleAndNotASecondCopy() {
        int checked = 0;
        for (ResourceLocation name : Block.blockRegistry.getKeys()) {
            Block block = Block.blockRegistry.getObject(name);
            Enclosure.CellGrid grid = (bx, by, bz) -> block;
            checked++;
            assertEquals("a cell holding " + name + " is solid for the physics and for the"
                            + " enclosure, or it is neither",
                    SimWorld.solid(block), grid.solidAt(0, 64, 0));
        }
        assertTrue("the registry was walked, so the equality above is not vacuous: " + checked
                + " blocks", checked > 100);
    }

    /**
     * The failure message has to say WHICH half failed, because "not a shelter" on its own is a
     * sentence a reader cannot act on.
     */
    @Test
    public void aVerdictNamesTheHalfThatFailedRatherThanPrintingBareFalse() {
        SimWorld w = EvalSuite.T25ShelterThroughTheNight.buildWorld();
        // One cell of wall at head height, taken out. The roof is untouched, so this is the run
        // that shows the sentence reporting a PARTIAL side closure rather than a bare false.
        w.remove(X, FEET_Y + 1, Z + 1);
        Enclosure.Verdict holed = Enclosure.at(w.enclosureGrid(), X, FEET_Y, Z);
        assertFalse(holed.enclosed());
        String said = holed.describe();
        assertTrue("the sentence says the sky half held, so a reader can see which half did not: "
                + said, said.contains("skyClosed=true"));
        assertTrue("and it reports the sides as a count rather than a boolean, so a reader sees"
                + " seven of eight rather than just 'not closed': " + said,
                said.contains("7 of 8 closed"));
        assertEquals(String.format(Locale.ROOT, "skyClosed=true, sides 7 of 8 closed"), said);
    }
}