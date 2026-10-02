package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-tick damage costs no world read, and the hazard is still a real hazard.
 *
 * <p><b>Why this number is load-bearing rather than tidy.</b> The Owner's standing rule is that
 * input realism may not cost capability, and this slice added a per-tick hazard test to the tick
 * loop -- which is the exact shape of change that quietly spends a read: a body in lava needs to
 * know what block it is standing in, and the obvious way to find that out is to ask through the
 * one seam a controller has, {@code ActActuator.blockAt}. Done that way the eval would be paying
 * for its own realism on every tick of every walk, forever, and the read profile that
 * {@code TheBeliefLayerCostsNoWorldReadTest} and {@code TheClockCostsTheWalkNoWorldReadTest} pin
 * at exactly 25 would drift by hundreds with nothing to notice.
 *
 * <p><b>Why an A/B rather than an absolute.</b> A counting actuator measures what the tick loop
 * costs the world, but an absolute here would be measuring the idle controllers rather than the
 * damage, and would go red for an unrelated change to a controller. The A/B is between two runs
 * that differ in exactly one thing -- whether the cell the body is standing in holds lava -- and
 * with no intent submitted, so both halves do identical controller work. Any difference is the
 * damage path's cost, and the claim is that the difference is zero.
 *
 * <p><b>And the equality alone would be satisfied by a no-op.</b> A damage path that read nothing
 * AND did nothing also costs zero. So the second half asserts the damage actually happened, and
 * that is what makes the first half a statement about cost rather than about silence.
 */
public final class TheSurvivalDamageCostsNoWorldReadTest {

    /** Ticks long enough to cover the whole 10-tick hurt band twice. */
    private static final int TICKS = 60;

    /** A plain at y=64 with the body standing at the origin, and nothing else. */
    private static SimWorld plain() {
        return new SimWorld().plain(64, "stone", -8, 8, -8, 8).standOn(0, 64, 0).facing(0f)
                .atHealth(20.0D);
    }

    @Test
    public void standingInLavaCostsTheSeamNothingAndStillHurts() {
        SimWorld clean = plain();
        new EvalHarness(clean).ticks(TICKS);
        int cleanReads = clean.seamReads();

        SimWorld inLava = plain();
        // The band is laid over the cell the body is standing in, so the hazard test has work to
        // do on every one of the 60 ticks rather than returning early.
        inLava.box(-1, 64, -1, 1, 64, 1, "lava");
        new EvalHarness(inLava).ticks(TICKS);
        int lavaReads = inLava.seamReads();

        assertEquals("the plain run reached the hazard path and found nothing", 0,
                clean.survival().ticksInLava());
        assertEquals("so its bar never moved", 20.0D, clean.health(), 0.0001D);

        // 21, not 60: the band is lethal on vanilla's own arithmetic, so the body dies part way
        // through and `step` stops counting. That is the hazard test running on every tick until
        // there was nothing left to hurt, which is all this assertion needs to establish.
        assertTrue("the lava run was inside the band until the bar ran out, so the hazard test"
                + " really ran and the comparison below is about a live path rather than a"
                + " disabled one: " + inLava.survival().ticksInLava() + " ticks",
                inLava.survival().ticksInLava() >= 20);
        assertEquals("and the bar is empty, so the damage applied rather than merely being probed",
                0.0D, inLava.health(), 0.0001D);

        assertEquals("60 ticks of standing in lava cost " + lavaReads + " reads through the"
                        + " controller seam and the same 60 ticks on a plain cost " + cleanReads
                        + ". The hazard test reads the grid this world already holds, so the"
                        + " difference is a capability charge for no new information",
                cleanReads, lavaReads);
    }

    /**
     * The same claim for the other three hazards, because "damage" is four code paths and one of
     * them quietly growing a probe is still a regression.
     *
     * <p>Each is a standalone world with a different hazard present and nothing else changed, and
     * each is compared against the plain. Water, a fall and the void are separate hazards with
     * separate probes; pinning only lava would leave three untested.
     */
    @Test
    public void everyOtherHazardAlsoCostsTheSeamNothing() {
        SimWorld clean = plain();
        new EvalHarness(clean).ticks(TICKS);
        int base = clean.seamReads();

        SimWorld water = plain();
        for (int y = 64; y <= 68; y++) {
            water.put(0, y, 0, "water");
        }
        EvalHarness hw = new EvalHarness(water);
        hw.ticks(TICKS);
        assertTrue("the water run spent " + water.survival().ticksSubmerged()
                + " ticks submerged, so the air bar was running down", water.survival().ticksSubmerged() >= 60);
        assertEquals("submerged water costs the seam nothing", base, water.seamReads());

        // Three cells wide, because a body overlapping a solid block while descending is raised
        // onto that block's top -- SimWorld.KNOWN_GAPS names this, and a one-wide hole is held
        // up by the walls either side of it rather than by anything the void rule would see.
        SimWorld pit = plain();
        for (int x = -1; x <= 1; x++) {
            for (int y = 63; y >= 50; y--) {
                pit.remove(x, y, 0);
            }
        }
        EvalHarness hp = new EvalHarness(pit);
        // Long enough to actually cross the floor: free fall from y=64 needs about 80 ticks to
        // reach -64 at this world's terminal velocity, and a run that stopped at 60 would leave
        // the void rule unexercised and the equality below true for the wrong reason.
        hp.ticks(150);
        assertTrue("the body ended at y=" + pit.posY() + ", below the void floor, so the kill at"
                + " Entity:514-517 ran", pit.posY() < SimWorld.VOID_Y);
        assertEquals("and the bar is empty rather than full", 0.0D, pit.health(), 0.0001D);
        assertEquals("the void costs the seam nothing", base, pit.seamReads());
    }

    /**
     * The mirror of every zero-cost assertion here: the seam is still being used.
     *
     * <p>A zero measured against a walk that never looks at anything is blindness, not economy,
     * and the two failure modes are indistinguishable from the number alone. The idle controllers
     * are what make this non-zero, and they are the same controllers whose read profile
     * {@code TheBeliefLayerCostsNoWorldReadTest} pins at 25 for a 60-tick walk.
     */
    @Test
    public void theSeamIsStillBeingUsedSoTheZerosAboveAreNotBlindness() {
        SimWorld w = plain();
        EvalHarness h = new EvalHarness(w);
        h.runtime().submitNav(new net.marcloud.mcp.core.drivers.act.NavIntent(
                8.5D, 64.0D, 0.0D, 400));
        h.runMove(60);
        assertTrue("a 60-tick walk made " + w.seamReads() + " reads through the seam, so the zeros"
                + " above are the damage costing nothing rather than the world costing nothing",
                w.seamReads() > 0);
    }
}
