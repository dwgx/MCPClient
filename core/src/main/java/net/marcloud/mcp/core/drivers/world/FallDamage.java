package net.marcloud.mcp.core.drivers.world;

/**
 * Vanilla's fall-damage arithmetic, transcribed exactly, and nothing else.
 *
 * <p><b>Why this exists at all, given that the damage is server-side.</b> {@code
 * EntityLivingBase.onLivingUpdate:233} applies the damage only under {@code !worldObj.isRemote},
 * so the client can never watch a number go down and no read-back can ever prove a fall hurt. What
 * the client DOES own, and owns alone, is the other half of the same rule: {@code Entity
 * .updateFallState:1034-1055} maintains {@code fallDistance} locally — it accumulates downward
 * motion, it is zeroed the tick the body lands, and it is halved in water ({@code
 * Entity.java:511}). So the fall distance is a client fact, the damage is a server fact, and the
 * arithmetic connecting them is fixed, published, and identical on both sides. That is enough to
 * answer the only question a caller actually has before walking off a cliff — "what will this cost
 * me" — with the real number instead of a constant somebody picked.
 *
 * <p><b>What it is not.</b> Not a prediction of survival and not a claim that damage was dealt.
 * {@link #damageFor} is the damage vanilla WOULD apply to a body with these inputs, which is why
 * every caller that reports it has to say "if you land". Two real inputs are deliberately absent
 * because they are not knowable here: the landing block's own {@code onFallenUpon} behaviour
 * ({@code Entity.java:1040-1042}, so a cactus costs more than the arithmetic says), and the
 * player's own {@code capabilities.allowFlying}, which {@code EntityPlayer.fall:1932} short-circuits
 * entirely — a creative player takes no fall damage at all, and this class cannot see that.
 *
 * <p>The three-block threshold is not a tuning constant of this project. It is {@code
 * EntityLivingBase.fall}'s own literal, which is also why {@code Stance.SAFE_DROP_MAX} is 3: the
 * planner's refusal boundary and vanilla's damage boundary are the same number, and a second,
 * different number here would be a disagreement with the game dressed up as a policy.
 */
public final class FallDamage {

    /**
     * Fall distance vanilla absorbs for free, in blocks.
     *
     * <p>{@code EntityLivingBase.fall:1156} subtracts {@code 3.0F} before taking the ceiling, so
     * 3.0 blocks is exactly 0 damage and 3.01 is the first half heart.
     */
    public static final float SAFE_DISTANCE = 3.0F;

    /** No fall damage below this distance, in half-hearts. Also the only negative this returns. */
    public static final int NONE = 0;

    private FallDamage() {
    }

    /**
     * Half-hearts of damage vanilla would deal for a fall of {@code distance} blocks.
     *
     * <p>{@code EntityLivingBase.fall:1156} verbatim:
     * {@code int i = MathHelper.ceiling_float_int((distance - 3.0F - f) * damageMultiplier);} where
     * {@code f} is the Jump amplifier plus one, or 0 without the potion, and the damage is applied
     * only {@code if (i > 0)}.
     *
     * @param distance      fall distance in blocks, i.e. vanilla's own {@code fallDistance}
     * @param jumpAmplifier the Jump potion's amplifier, or {@code -1} for no Jump effect — so the
     *                      {@code +1} vanilla applies to a live effect is folded in here rather
     *                      than left for a caller to remember
     * @param damageMultiplier vanilla's own multiplier; 1.0 on every ordinary fall and on every
     *                      fall this client can see
     * @return half-hearts, never negative — a 1-block fall is {@link #NONE}, not a negative number
     */
    public static int damageFor(float distance, int jumpAmplifier, float damageMultiplier) {
        float absorbed = SAFE_DISTANCE + (jumpAmplifier < 0 ? 0f : jumpAmplifier + 1);
        int i = (int) Math.ceil((distance - absorbed) * damageMultiplier);
        return Math.max(NONE, i);
    }

    /**
     * Whether this fall alone exceeds a health pool, i.e. whether landing kills.
     *
     * <p>Uses the same ceiling as {@link #damageFor}, so it agrees with it exactly: a body on 4 HP
     * facing 8 half-hearts dies, a body on 20 HP facing 8 does not. This says nothing about what
     * happens on the way down — a fall can kill without landing, and lava and cactus are not this
     * class's arithmetic — so it is a question about the landing only, and callers must word it that
     * way.
     *
     * @param health the player's current health in half-hearts
     */
    public static boolean lethal(float distance, int jumpAmplifier, float damageMultiplier,
                                 float health) {
        return damageFor(distance, jumpAmplifier, damageMultiplier) >= health;
    }
}
