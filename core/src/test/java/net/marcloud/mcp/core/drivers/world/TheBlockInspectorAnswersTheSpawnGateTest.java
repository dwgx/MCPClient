package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The block inspector must answer the question that actually decides where to stand: can a hostile
 * spawn HERE, and is the cell safe to build in.
 *
 * <p>The gate is {@code EntityMob.isValidLightLevel} ({@code EntityMob.java:145-165}) ANDed with a
 * floor and room for a body. Two things about it are easy to get wrong, and this repository has
 * already shipped both.
 *
 * <p><b>The comparison runs the opposite way from the intuition.</b> A cell spawns when the light
 * AT it is <i>at most 7</i>. Writing {@code > 7} inverts the gate: the agent then believes bright
 * cells are the dangerous ones and shelters in the dark. That is not a subtle degradation, it is
 * the answer backwards, and it happened the first time this file's author transcribed the rule.
 * {@link #theProductionGateAgreesWithTheVanillaRuleItWasTranscribedFrom()} sweeps every
 * (sky, block, amount) triple so the two transcriptions cannot drift apart again.
 *
 * <p><b>Thunder makes MIDDAY spawnable.</b> {@code EntityMob} saves {@code skylightSubtracted},
 * forces it to 10, re-reads and restores. At noon a stored sky of 15 becomes 5 -- under the
 * threshold. So a thunderstorm opens spawns at midday that a clear day closes, which is the
 * opposite of the folk memory that "night is the dangerous part". Planning a shelter against one
 * light reading is planning against the wrong night.
 *
 * <p><b>Light is stored already-propagated.</b> {@code getLightSubtracted(pos, amount)} computes
 * {@code max(storedSky - amount, storedBlock)}; the stored skylight accounts for what is overhead,
 * so {@code amount} is neither shade nor daylight, and reading the stored value means passing 0.
 * Passing 15 reported 0 at noon on open grass and passing 0 reported 15 seven blocks down -- either
 * one feeds the gate its exact opposite.
 */
public final class TheBlockInspectorAnswersTheSpawnGateTest {

    /** {@code World.getSkylightSubtracted} is about 0 at noon and 11 at night. */
    private static final int NOON = 0;
    private static final int NIGHT = 11;
    private static final int THUNDER = 10;

    /**
     * The vanilla rule, transcribed here independently of the implementation.
     *
     * <p>Step 1 is kept and shown to be inert rather than dropped: it is the kind of line that
     * looks load-bearing, and leaving it out would let someone "fix" the gate later by re-adding
     * a sky check that does not exist in vanilla.
     */
    private static boolean spawnGatePasses(int storedSky, int storedBlock, int skylightAmount) {
        if (storedSky > 31) {                       // getLightFor(SKY, pos) > rand(32)
            return false;                           // unreachable in 1.8.9: sky tops out at 15
        }
        return Math.max(storedSky - skylightAmount, storedBlock) <= 7;   // i <= rand(8)
    }

    @Test
    public void theGateRefusesDaylightAndAdmitsDarkness() {
        assertTrue("noon on open grass: light at the cell is 15, so no mob",
                !spawnGatePasses(15, 0, NOON));
        assertTrue("midnight on open grass: stored sky 0 less 11 is 0, so a mob CAN spawn",
                spawnGatePasses(0, 0, NIGHT));
        assertTrue("and a torch is what stops it: block light 14 puts the cell over 7",
                !spawnGatePasses(0, 14, NIGHT));
    }

    @Test
    public void thunderMakesMiddaySpawnable() {
        assertTrue("precondition: a clear noon is safe",
                !spawnGatePasses(15, 0, NOON));
        assertTrue("but under thunder EntityMob forces skylightSubtracted to 10, the same cell "
                + "reads 5 and spawns -- a storm is more dangerous than a clear day",
                spawnGatePasses(15, 0, THUNDER));
        assertTrue("while the same storm leaves a torch-lit cell safe, because block light wins "
                + "the max", !spawnGatePasses(15, 14, THUNDER));
    }

    @Test
    public void theProductionGateAgreesWithTheVanillaRuleItWasTranscribedFrom() {
        int mismatches = 0;
        for (int sky = 0; sky <= 31; sky++) {
            for (int blockLight = 0; blockLight <= 15; blockLight++) {
                for (int amount : new int[] {NOON, 5, THUNDER, NIGHT}) {
                    if (spawnGatePasses(sky, blockLight, amount)
                            != BlockInspector.gatePasses(sky, blockLight, amount)) {
                        mismatches++;
                    }
                }
            }
        }
        assertEquals("the implementation and this file's independent transcription must agree on "
                + "every triple, including the 7/8 boundary and the thunder amount", 0, mismatches);
    }

    @Test
    public void theCombinedLightIsTheMaxOfTheTwoStores() {
        assertEquals("stored sky 15 at noon", 15,
                BlockInspector.lightAtPosition(15, 0, NOON));
        assertEquals("stored sky 0, block 14: block light is the max, not the sum", 14,
                BlockInspector.lightAtPosition(0, 14, NIGHT));
        assertEquals("a clamp is not implied: 0 less 11 is -11, and the max picks block light", 14,
                BlockInspector.lightAtPosition(0, 14, NIGHT));
        assertEquals("stored sky 15 under thunder reads as 5", 5,
                BlockInspector.lightAtPosition(15, 0, THUNDER));
    }

    @Test
    public void thunderOverridesTheHourRatherThanAddingToIt() {
        assertEquals("a storm pins the factor at 10 whatever midday implies",
                THUNDER, BlockInspector.skylightAmountFor(NOON, true));
        assertEquals("and whatever midnight implies, because EntityMob saves, forces and restores "
                + "the world's field rather than blending them",
                THUNDER, BlockInspector.skylightAmountFor(NIGHT, true));
        assertEquals("without a storm the world's own reading stands",
                NOON, BlockInspector.skylightAmountFor(NOON, false));
        assertEquals(NIGHT, BlockInspector.skylightAmountFor(NIGHT, false));
    }

    @Test
    public void theVerdictCarriesTheNumbersThatProducedIt() {
        String why = BlockInspector.Spawn.LIGHT.because(9, 0, false, true, true);
        assertTrue("a caller told 'light' with no numbers cannot tell a cell that missed by one "
                + "from one that missed by nine, and those want different work: " + why,
                why.contains("=9"));

        String stormy = BlockInspector.Spawn.YES.because(5, 0, true, true, true);
        assertTrue("under thunder the message must say so, because the same stored sky is admitted "
                + "once a storm starts: " + stormy, stormy.contains("thunder"));
    }

    @Test
    public void aMissingFloorIsARefusalOfNowNotOfEver() {
        assertTrue("a hole is where the mob arrives, so the message must not read as permanently "
                + "safe: " + BlockInspector.Spawn.NO_FLOOR.because(0, 0, false, false, true),
                BlockInspector.Spawn.NO_FLOOR.because(0, 0, false, false, true).contains("LATER"));
        assertTrue("a blocked cell is the same case",
                BlockInspector.Spawn.NO_ROOM.because(0, 0, false, true, false).contains("LATER"));
    }

    @Test
    public void unknownClaimsNothingAndIsNeverASpawn() {
        assertTrue(BlockInspector.Spawn.UNKNOWN.because(-1, -1, false, false, false)
                .contains("nothing"));
        assertTrue("an unreadable cell is not a safe cell",
                !BlockInspector.Spawn.UNKNOWN.allowed());
        assertTrue("nor is a cell with no floor, whatever the light says",
                !BlockInspector.Spawn.NO_FLOOR.allowed());
        assertTrue("but a dark cell IS an allowed spawn -- allowed() names the gate, not danger",
                BlockInspector.Spawn.LIGHT.allowed());
    }
}
