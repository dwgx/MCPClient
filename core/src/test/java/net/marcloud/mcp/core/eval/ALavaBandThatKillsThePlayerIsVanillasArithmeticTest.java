package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import net.marcloud.mcp.core.drivers.world.DrowningDamage;
import net.marcloud.mcp.core.drivers.world.FallDamage;
import net.marcloud.mcp.core.drivers.world.FireDamage;
import net.marcloud.mcp.core.eval.SurvivalDamage.Source;
import org.junit.Test;

/**
 * The health bar moves, and it moves by vanilla's numbers.
 *
 * <p><b>What this pins, and why it is not the same claim as the ones it cites.</b>
 * {@code FireAndDrownAreVanillasArithmeticTest} and {@code TheFallDamageNumberIsVanillasTest}
 * already prove the three damage classes carry vanilla's arithmetic. What they cannot prove is
 * that anything APPLIES it -- and before this slice nothing did, which is why every
 * {@code w.health() > 0.0D} in {@code EvalSuite} evaluated {@code 20.0 > 0.0}. These tests are
 * about the application: the sequence, the order, the throttling, and the fact that a body in
 * lava ends up dead.
 *
 * <p><b>Every expected number below is derived from the vendored source, then the source line is
 * named, and the test walks the whole sequence rather than spot-checking an end state.</b> The
 * sequence is the claim. A transcription that is right about "lava hurts" and wrong about "lava
 * hurts on which tick, and how often" produces a body that survives forever, and an
 * end-state-only assertion would call that a pass.
 *
 * <p><b>The one that matters most is the last.</b> {@link #theHurtWindowIsWhatMakesLavaARateAndNotAn
 * #InstantKill} is the arithmetic; {@link #aBodyThatWalksIntoTheBandIsDeadInsideTheWalk} is the
 * consequence. Both are here because the first alone could be satisfied by a ledger nobody calls.
 */
public final class ALavaBandThatKillsThePlayerIsVanillasArithmeticTest {

    /** A flat plain of stone at y=63 with a four-wide lava band laid across it at the feet. */
    private static SimWorld worldWithBand() {
        SimWorld w = new SimWorld().plain(64, "stone", -8, 12, -8, 8).standOn(4, 64, 0)
                .facing(0f).atHealth(20.0D);
        w.box(4, 64, -4, 7, 64, 4, "lava");
        return w;
    }

    /**
     * The whole lava sequence, tick by tick, against the arithmetic derived from the source.
     *
     * <p>Derived, not measured, and the derivation is spelled out in the expected array:
     * <ul>
     *   <li>Contact damage is {@code DamageSource.lava, 4.0F} = 8 half-hearts
     *       ({@code Entity.setOnFireFromLava:543}).</li>
     *   <li>The burn is {@code DamageSource.onFire, 1.0F} = 2 half-hearts on every tick where the
     *       counter is a multiple of 20 ({@code Entity.update:499-501}).</li>
     *   <li>{@code setFire(15)} puts 300 ticks on the counter ({@code Entity:544}, then
     *       {@code seconds * 20} at {@code :553}), and it only ever RAISES the counter
     *       ({@code :556}), so the burn counter is pegged at 300 for as long as the body stands
     *       in the lava and fires on every 20th tick.</li>
     *   <li>All of it goes through {@code attackEntityFrom}, whose refusal band is
     *       {@code hurtResistantTime > maxHurtResistantTime / 2.0F} = 10 ticks
     *       ({@code EntityLivingBase:896}, {@code :95}), so an equal-or-smaller hit inside the band
     *       is dropped and a bigger one keeps only the excess.</li>
     * </ul>
     * Which gives: 8 lands on tick 0; ticks 1..9 are all refused; tick 10 pays 2 (burn, band
     * expired) then 6 (contact, 8 minus the 2 just recorded); ticks 11..20 refused; tick 21 pays 2
     * then 6, and the bar is already at 2, so it ends at zero.
     */
    @Test
    public void theLavaSequenceIsTheOneTheVendoredSourceDescribes() {
        SimWorld w = worldWithBand();
        EvalHarness h = new EvalHarness(w);

        float[] expected = new float[24];
        // t0: contact 8 lands (the window is closed). t1..t9: window, all refused.
        // t10: the band has run out, so the burn's 2 lands whole and the contact's 8 keeps only
        //      the 6 that exceeds it. t11..t19: window, all refused again.
        // t20: the band has run out again; the burn's 2 lands, the contact's 6 takes the bar from
        //      2 to 0, and the body is dead.
        expected[0] = 12.0F;
        for (int t = 1; t <= 9; t++) {
            expected[t] = 12.0F;
        }
        expected[10] = 4.0F;
        for (int t = 11; t <= 19; t++) {
            expected[t] = 4.0F;
        }
        expected[20] = 0.0F;

        for (int t = 0; t < expected.length; t++) {
            h.tick();
            assertEquals("tick " + t + ": the health bar should be exactly where vanilla's fire"
                            + " clock, lava contact and 10-tick hurt window put it",
                    expected[t], w.health(), 0.0001F);
        }

        assertEquals("the burn counter is pegged at 300 while the body stands in lava, because"
                + " setFire only ever raises it (Entity:556)", 300, w.survival().fire());
        assertEquals("21 ticks elapsed and the body was inside the band on every one of them,"
                + " including the tick that killed it", 21, w.survival().ticksInLava());
        assertEquals("one contact per tick in the band, because setOnFireFromLava runs"
                + " unconditionally (Entity:508-512) and it is the WINDOW, not the call, that"
                + " decides what each contact costs", 21, w.survival().lavaContacts());
        assertEquals("the burn fired on ticks 1, 10 and 20; only the last two were outside the"
                + " window, so only those two paid", 4.0F,
                w.survival().damageFrom(Source.FIRE), 0.0001F);
        assertEquals("contact damage lands 8, then 6 and 6 -- the two later ones keep only the"
                + " excess over the 2 the burn recorded first (ELB:897-902)", 20.0F,
                w.survival().damageFrom(Source.LAVA), 0.0001F);
        assertTrue("the bar reached zero", w.survival().dead());
    }

    /**
     * The tick-by-tick sequence above only means something if the band is lethal on its own
     * arithmetic, with no navigation, no policy and no assertion helper involved.
     *
     * <p>The mirror of the suite's own defect: a green row that no world could contradict. This
     * one is contradicted by a world with the lava removed.
     */
    @Test
    public void aBodyThatWalksIntoTheBandIsDeadInsideTheWalk() {
        SimWorld withLava = new SimWorld().plain(64, "dirt", -24, 23, -24, 20)
                .standOn(-8, 64, 0).facing(90f).atHealth(20.0D);
        withLava.box(4, 64, -24, 7, 64, 0, "lava");
        EvalHarness hl = new EvalHarness(withLava);
        hl.runtime().submitNav(new net.marcloud.mcp.core.drivers.act.NavIntent(
                20.5D, 64.0D, 0.0D, 600));
        hl.runMove(700);

        assertEquals("the walk was aimed straight across the band, so the band had to be entered",
                true, withLava.ticksInLava() > 0);
        assertEquals("walking into a four-block lava band kills a full health bar, and the run was"
                + " long enough for that to have happened", 0.0F, withLava.health(), 0.0001F);

        // The control: the same world with the band replaced by the dirt it replaced. Everything
        // else -- the spawn, the goal, the budget, the controller -- is identical.
        SimWorld noLava = new SimWorld().plain(64, "dirt", -24, 23, -24, 20)
                .standOn(-8, 64, 0).facing(90f).atHealth(20.0D);
        EvalHarness hn = new EvalHarness(noLava);
        hn.runtime().submitNav(new net.marcloud.mcp.core.drivers.act.NavIntent(
                20.5D, 64.0D, 0.0D, 600));
        hn.runMove(700);

        assertEquals("the control world reports no hazard reached", 0, noLava.ticksInLava());
        assertEquals("and the player is untouched in it", 20.0F, noLava.health(), 0.0001F);
        assertEquals("so the difference between the two runs is the lava and nothing else",
                false, noLava.anyHazard());
        assertTrue(withLava.anyHazard());
    }

    /**
     * The north-star number, and both sides of it.
     *
     * <p>The project's claim is "health never below 18". A threshold nothing can violate is not a
     * threshold, so this asserts both halves: a run that dips under 18 is detectable, and a run
     * that stays above it is reported as having stayed above it. The band is the plant -- four
     * half-hearts is one tick of contact damage, and 20 - 8 = 12 is already two below the floor.
     */
    @Test
    public void theNorthStarFloorIsReachableFromBothSides() {
        assertEquals("the floor is 18 half-hearts and it is not vanilla's number, it is the"
                + " project's", 18.0F, SurvivalDamage.NORTH_STAR_FLOOR, 0.0001F);

        // One tick of contact and the bar is at 12: below the floor, and the ledger says so.
        SimWorld dipped = worldWithBand();
        EvalHarness hd = new EvalHarness(dipped);
        hd.tick();

        assertEquals("one tick of lava contact is 4.0F at Entity:543, which is 8 half-hearts",
                12.0F, dipped.health(), 0.0001F);
        assertTrue("20 - 8 = 12 is below the 18 floor, and the instrument must be able to say so",
                dipped.brokeNorthStar());
        assertEquals("and it did", 12.0F, dipped.minimumHealth(), 0.0001F);

        // A plain with no hazard: the floor holds, and the claim is reported as holding.
        SimWorld clean = new SimWorld().plain(64, "stone", -8, 8, -8, 8).standOn(0, 64, 0)
                .facing(0f).atHealth(20.0D);
        new EvalHarness(clean).ticks(400);
        assertFalse("400 ticks on a bare plain cannot break the floor, and saying so is the"
                + " other half of a threshold being a threshold", clean.brokeNorthStar());
        assertEquals(20.0F, clean.minimumHealth(), 0.0001F);
    }

    /**
     * A fall, pinned against {@code EntityLivingBase.fall:1156}, end to end through the body.
     *
     * <p>Two directions, because the two halves of the rule are different numbers and a test that
     * only checks one of them passes a transcription that dropped the other. A nine-block drop is
     * {@code ceil(9 - 3)} = 6 half-hearts; a two-block step is under the floor and must cost
     * nothing at all, not a negative number.
     */
    @Test
    public void aFallCostsVanillasArithmeticAndAStepDownCostsNothing() {
        SimWorld pit = new SimWorld().plain(64, "stone", -12, 12, -12, 12).standOn(0, 64, -4)
                .facing(0f).atHealth(20.0D);
        for (int x = -2; x <= 2; x++) {
            for (int z = -1; z <= 3; z++) {
                for (int y = 63; y >= 55; y--) {
                    pit.remove(x, y, z);
                }
                pit.put(x, 54, z, "stone");
            }
        }
        EvalHarness hp = new EvalHarness(pit);
        hp.runtime().submitNav(new net.marcloud.mcp.core.drivers.act.NavIntent(
                0.5D, 64.0D, 3.0D, 600));
        hp.runMove(700);

        assertEquals("the body landed once, on the stone at y=55 -- nine blocks below y=64",
                1, pit.survival().fallLandings());
        assertEquals("nine blocks is ceil(9 - 3.0F) = 6 half-hearts at ELB:1156",
                6.0F, pit.survival().damageFrom(Source.FALL), 0.0001F);
        assertEquals("and the bar is 20 - 6", 14.0F, pit.health(), 0.0001F);
        assertEquals("which is the same number the class alone predicts",
                FallDamage.damageFor(9.0F, -1, 1.0F), 6);

        SimWorld step = new SimWorld().plain(64, "stone", -12, 12, -12, 12).standOn(0, 64, -4)
                .facing(0f).atHealth(20.0D);
        for (int x = -2; x <= 2; x++) {
            for (int z = -1; z <= 5; z++) {
                step.remove(x, 63, z);
                step.put(x, 61, z, "stone");
            }
        }
        EvalHarness hs = new EvalHarness(step);
        hs.runtime().submitNav(new net.marcloud.mcp.core.drivers.act.NavIntent(
                0.5D, 64.0D, 5.0D, 600));
        hs.runMove(700);

        assertEquals("a two-block step down is still a landing, so the counter moved", 1,
                step.survival().fallLandings());
        assertEquals("but it is under ELB's 3.0F floor, so it costs nothing", 0.0F,
                step.survival().damageFrom(Source.FALL), 0.0001F);
        assertEquals("and the bar never moved", 20.0F, step.minimumHealth(), 0.0001F);
    }

    /**
     * The air bar and the drown tick, through the body, because the -20 is the number most easily
     * lost to a clamp.
     *
     * <p>{@code EntityLivingBase:301} decrements unconditionally and {@code :303} tests equality
     * against -20, so the band -1..-19 is real and the hit lands on the 320th submerged tick. A bar
     * clamped at zero never reaches -20 and the drown tick becomes unreachable code -- which is
     * exactly the bug this test was written against, and exactly the shape of defect this project
     * exists to catch.
     */
    @Test
    public void theDrownTickLandsOnThe320thSubmergedTickAndThenEvery20() {
        SimWorld pool = new SimWorld().plain(64, "stone", -8, 8, -8, 8).standOn(0, 64, 0)
                .facing(0f).atHealth(20.0D);
        for (int y = 64; y <= 68; y++) {
            pool.put(0, y, 0, "water");
        }
        EvalHarness h = new EvalHarness(pool);

        // 300 ticks run the bar down to 0 (ELB:301) and 19 more walk it through the negative
        // band, so the -20 is the 320th submerged tick.
        for (int t = 0; t < 319; t++) {
            h.tick();
        }
        assertEquals("319 submerged ticks in, the bar is at -19 and nothing has hurt the body yet",
                -19, pool.air());
        assertEquals("no drown tick yet", 0, pool.survival().drownTicks());
        assertEquals("and the health bar is untouched", 20.0F, pool.health(), 0.0001F);

        h.tick();
        assertEquals("the 320th tick took the bar to -20 -- the equality ELB:303 tests -- and it was"
                + " reset to 0 rather than to 300 (ELB:305), which is what makes the next hit 20"
                + " ticks away instead of 320", 0, pool.air());
        assertEquals("one drown tick", 1, pool.survival().drownTicks());
        assertEquals("2.0F at ELB:315 is 4 half-hearts", 16.0F, pool.health(), 0.0001F);

        // The accelerated cadence: 20 ticks per hit from here, not 320.
        for (int t = 0; t < 20; t++) {
            h.tick();
        }
        assertEquals("one more breath-band later, a second hit has landed", 2,
                pool.survival().drownTicks());
        assertEquals("4 half-hearts again", 12.0F, pool.health(), 0.0001F);

        // To the death, and to the same total the arithmetic predicts.
        for (int t = 0; t < 80; t++) {
            h.tick();
        }
        assertEquals("5 hits of 4 half-hearts is the whole bar", 5, pool.survival().drownTicks());
        assertEquals(0.0F, pool.health(), 0.0001F);
        assertEquals("and the class agrees on the count for the same dive",
                5 * DrowningDamage.DAMAGE_PER_TICK,
                DrowningDamage.damageOverTicks(320 + 4 * 20, 0));
    }

    /**
     * The band is HALF the window, and reading it as the whole window is the error this test
     * exists to prevent.
     *
     * <p>{@code EntityLivingBase:95} declares {@code maxHurtResistantTime = 20} and
     * {@code :896} tests {@code > maxHurtResistantTime / 2.0F}. Ten, not twenty. An implementation
     * that used 20 would halve every hazard's rate, and the eval built on it would send an agent
     * through a crossing a real player survives -- which is the failure mode that looks like
     * caution and is actually a wrong model of the game.
     */
    @Test
    public void theHurtWindowIsHalfOfMaxHurtResistantTimeAndNotTheWholeOfIt() {
        assertEquals("EntityLivingBase:95", 20, SurvivalDamage.MAX_HURT_RESISTANT_TICKS);
        assertEquals("and ELB:896 compares against half of it, so the band is 10",
                10, SurvivalDamage.HURT_WINDOW_TICKS);

        SimWorld w = worldWithBand();
        EvalHarness h = new EvalHarness(w);
        for (int t = 0; t < 10; t++) {
            h.tick();
        }
        assertEquals("ticks 1..9 were all inside the band and every one of them was refused, so"
                + " the 8 half-hearts of tick 0 are the only damage so far", 12.0F,
                w.health(), 0.0001F);
        assertEquals("so a 10-tick band is the reading that fits; a 20-tick one would already have"
                + " paid a second time by now", 12.0F, w.health(), 0.0001F);
    }

    /**
     * Two bodies, two worlds, one variable -- and the ledger names the cause rather than only
     * reporting a number.
     *
     * <p>The number alone is not enough for a failure message. A body at 12 half-hearts is the
     * same figure whether a zombie hit it or it walked into lava, and a report that cannot say
     * which is a report that sends the reader to the wrong controller.
     */
    @Test
    public void theLedgerNamesWhichHazardTookTheHealth() {
        SimWorld inLava = worldWithBand();
        new EvalHarness(inLava).ticks(3);
        String lavaLedger = inLava.survival().ledger();
        assertTrue("a lava run names LAVA: " + lavaLedger, lavaLedger.startsWith("LAVA="));

        SimWorld clean = new SimWorld().plain(64, "stone", -8, 8, -8, 8).standOn(0, 64, 0)
                .facing(0f).atHealth(20.0D);
        new EvalHarness(clean).ticks(3);
        assertEquals("a clean run says so in as many words rather than printing an empty ledger",
                "nothing damaged the player", clean.survival().ledger());
        assertFalse(clean.anyHazard());
        assertTrue(String.format(Locale.ROOT, "and the source it reads is %s", SurvivalDamage.class
                .getSimpleName()), SurvivalDamage.class.getName().endsWith("SurvivalDamage"));
        assertEquals("the burn class's own number is still the one being applied, not a copy",
                FireDamage.DAMAGE_PER_TICK, 2);
    }
}
