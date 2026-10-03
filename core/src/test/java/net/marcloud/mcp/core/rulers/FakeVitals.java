package net.marcloud.mcp.core.rulers;

import net.marcloud.mcp.core.eval.NightHealth;

/**
 * A hand-driven {@link NightHealth.Vitals}, so the accumulator can be put the SAME sequence of bars
 * in front of it twice with one value changed between the runs.
 *
 * <p><b>Why a fixture and not a live client.</b> The accumulator's whole claim is that it reads
 * three production fields. A live {@code EntityPlayerSP} cannot be made to receive a specific S06
 * sequence from a test, and more to the point it should not be: this file's job is to drive the
 * PRODUCTION accumulator over a known bar sequence, and the wiring test
 * ({@code TheRulersAreWiredIntoTheProductionKernelTest}) is what proves the accumulator is the one
 * a running client reaches. A test that drove {@code SimWorld} instead would be measuring the
 * fixture, which is the defect the whole north-star audit is about.
 *
 * <p><b>The three quantities are settable separately on purpose.</b> The instrument's central claim
 * is that {@code getHealth()}'s minimum, {@code lastDamage} and {@code hurtTime} each see something
 * the others cannot. A fixture that moved all three together whenever the bar dropped could not
 * tell that claim apart from "any one of them would do", so every setter here is independent and
 * the tests set the invulnerable case by leaving the BAR alone while still moving the other two --
 * which is exactly what {@code EntityPlayerSP.damageEntity:319-325} does on a real client.
 *
 * <p><b>The hurt flag decays the way vanilla's does.</b> {@link #hit(float)} raises
 * {@code hurtTime} to 10 and {@link #tick()} decrements it, mirroring
 * {@code EntityPlayerSP.setPlayerSPHealth:367} writing it and {@code EntityLivingBase:337-339}
 * counting it down. A fixture that pinned it at 10 forever would make the rising-edge logic look
 * correct for the wrong reason.
 */
final class FakeVitals implements NightHealth.Vitals {

    /** {@code EntityLivingBase.maxHurtTime} as {@code setPlayerSPHealth:367} writes it. */
    private static final int HURT_ON_HIT = 10;

    private final Object world;

    private long worldTime;
    private float health;
    private float lastDamage;
    private int hurtTime;
    private boolean lastDamageNaN;

    FakeVitals(Object world, long worldTime, float health) {
        this.world = world;
        this.worldTime = worldTime;
        this.health = health;
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
    public float health() {
        return health;
    }

    @Override
    public float lastDamage() {
        return lastDamageNaN ? Float.NaN : lastDamage;
    }

    @Override
    public int hurtTime() {
        return hurtTime;
    }

    /** Move the clock and count the hurt flag down, which is what one game tick does. */
    FakeVitals tick() {
        worldTime++;
        if (hurtTime > 0) {
            hurtTime--;
        }
        return this;
    }

    /** Move the clock without touching anything else, for windows where nothing happens. */
    FakeVitals advance(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
        return this;
    }

    /** Set the clock outright, which is how a {@code /time set} is driven. */
    FakeVitals at(long time) {
        this.worldTime = time;
        return this;
    }

    /**
     * A hit that MOVED the bar -- the ordinary case.
     *
     * <p>Writes {@code lastDamage} and raises {@code hurtTime} first and lowers the bar, in the
     * order {@code EntityPlayerSP.setPlayerSPHealth} does it (`:363`, `:367`, then the subtraction
     * at `:366}).
     */
    FakeVitals hit(float amount) {
        lastDamage = amount;
        hurtTime = HURT_ON_HIT;
        health -= amount;
        return this;
    }

    /**
     * A hit the invulnerability guard REFUSED -- the case a min-of-health is blind to.
     *
     * <p>{@code EntityPlayerSP.damageEntity:319-325} skips {@code setHealth} when
     * {@code isEntityInvulnerable} holds, so the bar does not move, while {@code :363} and
     * {@code :367} have already been written. This method therefore writes the two fields and NOT
     * the bar, and a test that uses it and then asserts {@code minHealth} did not move has proved
     * the asymmetry the payload depends on.
     */
    FakeVitals blockedHit(float amount) {
        lastDamage = amount;
        hurtTime = HURT_ON_HIT;
        return this;
    }

    /** Set the bar without any hit at all -- a regeneration or an external change. */
    FakeVitals health(float hp) {
        this.health = hp;
        return this;
    }

    /** Make {@code lastDamage} unreadable, which is a different state from "zero". */
    FakeVitals lastDamageUnreadable() {
        this.lastDamageNaN = true;
        return this;
    }

    /** A fresh full bar in a different world, for testing the world-identity reset. */
    FakeVitals inWorld(Object other, long time, float hp) {
        FakeVitals v = new FakeVitals(other, time, hp);
        v.lastDamage = this.lastDamage;
        return v;
    }
}