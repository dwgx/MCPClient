package net.marcloud.mcp.core.eval;

import java.lang.reflect.Field;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

import net.marcloud.mcp.core.GameAccess;

/**
 * The one {@link NightHealth.Vitals} a running client can supply: the local player's own three
 * health fields, read on the game thread.
 *
 * <p><b>Why this class exists rather than an anonymous {@code Vitals} in {@code McpCore}.</b> The
 * wiring has to hand {@link NightHealth} something that reaches the live game, and one of the
 * three quantities cannot be read through an accessor at all -- see {@link #lastDamage()} for the
 * reflection and why it is resolved once rather than per tick. That reflection needs a home, and
 * a lambda cannot hold a cached {@code Field}.
 *
 * <p><b>It allocates nothing per tick.</b> {@code getHealth()} is a {@code DataWatcher} float
 * lookup on an object the game already holds, {@code hurtTime} is a public field read, and
 * {@link #lastDamage} is a cached reflective read. No {@code BlockPos}, no boxing, no list. The
 * class exists once, for the life of the process.
 *
 * <p><b>Why {@code lastDamage} is reflective, and why it is a {@code Field} and not a
 * {@code MethodHandle}.</b> {@code EntityLivingBase.lastDamage} is declared
 * {@code protected float lastDamage} ({@code EntityLivingBase.java:142}) and the vendored tree has
 * NO public getter for it -- the four references over the entity package are the writes at
 * {@code :898}, {@code :903}, {@code :904} and {@code :909}, all inside the declaring class. The
 * other two quantities are public, so this is the only one that needs the detour.
 *
 * <p>The field is resolved ONCE, lazily, on first read, and the result -- including a failure --
 * is cached. A per-tick {@code getDeclaredField} would put a reflective lookup on the game's hot
 * path, which is exactly the cost {@code EventBus} carries a dispatch cache to avoid
 * ({@code EventBus.java:20-26}).
 *
 * <p><b>What happens when the field cannot be read.</b> {@link #lastDamage()} returns
 * {@link Float#NaN} and {@link NightHealth} SKIPS it rather than folding in a zero. That
 * distinction is the whole reason this class has no fallback: a zero would be a claim that the
 * biggest registered hit was worth nothing, and the honest reading of an unreadable field is
 * "there is no reading". {@link NightHealth#lastDamageReadable()} then reports false and the
 * payload prints the reason, so the gap is visible rather than silent.
 *
 * <p><b>Why the health read is guarded.</b> {@code getHealth()} reads the datawatcher at index 6,
 * which {@code EntityLivingBase.entityInit} registers as {@code Float.valueOf(1.0F)}
 * ({@code :216}) before {@code :201} calls {@code setHealth(getMaxHealth())}. A read taken in that
 * window would see 1.0 -- below the 18 floor -- and the accumulator would record a floor breach
 * that never happened to a player who was merely being constructed. The guard turns that into "no
 * reading", which the accumulator also skips, so a construction artefact cannot become a night
 * verdict. It also covers a datawatcher index that has not been registered at all, which would
 * otherwise throw.
 *
 * <p><b>Threading.</b> GAME THREAD ONLY, inherited from {@link NightHealth.Vitals}: every method
 * reads live entity state, and the only caller is the tick handler.
 */
public final class ClientVitals implements NightHealth.Vitals {

    /**
     * The cached reflective handle on {@code EntityLivingBase.lastDamage}, or null once a lookup
     * has failed. Assigned on the game thread and only ever read from there.
     *
     * <p>Three states on purpose: null-before-lookup, a {@link Field} once one has been resolved,
     * and null-after-failure with {@link #lookupFailed} set. Collapsing the last two would make
     * "not looked yet" and "looked and could not" the same value, and {@link NightHealth} would
     * then be unable to tell "no reading because nothing has been sampled" from "no reading
     * because the field is gone".
     */
    private static Field lastDamageField;
    private static boolean lookupAttempted;
    private static boolean lookupFailed;

    private final GameAccess game;

    public ClientVitals(GameAccess game) {
        this.game = game;
    }

    @Override
    public Object world() {
        return game.world();
    }

    /**
     * {@code World.getWorldTime()} -- the day-cycle clock, asked of the same accessor
     * {@link ClientBody#worldTime()} uses.
     *
     * <p>{@code getTotalWorldTime()} is the other one and is deliberately not used here: the
     * three rulers share one clock, and {@link NightShelter}'s window boundaries come from the
     * day-cycle clock, so a floor ledger folding the total-time clock would be measuring a
     * different curve from the shelter beside it.
     */
    @Override
    public long worldTime() {
        WorldClient w = game.world();
        return w == null ? 0L : w.getWorldTime();
    }

    /**
     * {@code Entity.getHealth()}, or {@link Float#NaN} when it could not be read.
     *
     * <p>NaN rather than zero, because zero is a legal reading -- a dead player's bar -- and
     * folding an unreadable tick in as zero would read as a corpse. See the class doc for the
     * construction window this guards.
     */
    @Override
    public float health() {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return Float.NaN;
        }
        try {
            float hp = p.getHealth();
            // Datawatcher index 6 is registered as 1.0F before EntityLivingBase:201 raises it to
            // maxHealth, so a read inside that window is a construction artefact rather than a
            // bar, and 1.0 is below the north-star floor. Refuse anything at or under it as "no
            // reading"; a live player is never at 1.0 half-hearts.
            return hp > 1.0F ? hp : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /**
     * {@code EntityLivingBase.lastDamage}, or {@link Float#NaN} when the field cannot be read.
     *
     * <p>See the class doc for why reflection is the only route and why the handle is cached.
     */
    @Override
    public float lastDamage() {
        Field f = lastDamageField();
        if (f == null) {
            return Float.NaN;
        }
        EntityPlayerSP p = game.player();
        if (p == null) {
            return Float.NaN;
        }
        try {
            Object v = f.get(p);
            return v instanceof Number n ? n.floatValue() : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /**
     * Whether {@link #lastDamageField()} has ever resolved.
     *
     * <p>Exposed so a test can assert the reflective route is the one being taken rather than a
     * number that happened to come out right, and so the wiring can report an unreadable field as
     * a known condition instead of discovering it through a NaN in a payload.
     */
    public static boolean lastDamageReadable() {
        return lastDamageField() != null;
    }

    /** Whether a lookup was tried and failed, which is a different fact from never having tried. */
    public static boolean lastDamageLookupFailed() {
        lastDamageField();
        return lookupFailed;
    }

    /**
     * The cached handle, resolved at most once per process.
     *
     * <p>Walks the hierarchy from {@code EntityLivingBase} rather than asking the concrete class
     * alone, because the object handed here is an {@code EntityPlayerSP} and the field is declared
     * two levels up: asking the subclass answers nothing, and a subclass that DID shadow the name
     * would then be read instead of the field this instrument documents.
     */
    private static synchronized Field lastDamageField() {
        if (lookupAttempted) {
            return lastDamageField;
        }
        lookupAttempted = true;
        for (Class<?> c = net.minecraft.entity.EntityLivingBase.class; c != null;
                c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("lastDamage");
                f.setAccessible(true);
                lastDamageField = f;
                return f;
            } catch (NoSuchFieldException ignored) {
                // Keep walking: the field may live higher up the hierarchy.
            } catch (Throwable t) {
                lookupFailed = true;
                return null;
            }
        }
        lookupFailed = true;
        return null;
    }

    @Override
    public int hurtTime() {
        EntityPlayerSP p = game.player();
        return p == null ? 0 : p.hurtTime;
    }
}