package net.marcloud.mcp.core.drivers.act;

/**
 * The critical-hit window, as vanilla spells it, as a question this seam can actually ask.
 *
 * <p><b>The whole rule is one line of vanilla.</b> {@code
 * EntityPlayer.attackTargetEntityWithCurrentItem:1333}:
 *
 * <pre>
 * boolean flag = this.fallDistance &gt; 0.0F &amp;&amp; !this.onGround &amp;&amp; !this.isOnLadder()
 *              &amp;&amp; !this.isInWater() &amp;&amp; !this.isPotionActive(Potion.blindness)
 *              &amp;&amp; this.ridingEntity == null &amp;&amp; targetEntity instanceof EntityLivingBase;
 * </pre>
 *
 * and {@code flag} is what multiplies the damage by 1.5 (line 1335) and calls {@code
 * onCriticalHit} (line 1345). Seven terms, of which <b>four are the client's own body</b> and are
 * what a player controls by jumping and by being somewhere: falling, off the ground, not on a
 * ladder, not in water.
 *
 * <p><b>What this class decides, and what it refuses to decide.</b> {@link #firstDisproof} answers
 * only the four terms this seam carries, and it answers them by DISPROVING: a non-null result names
 * the first term vanilla's own conjunction fails. A null result means "nothing the client can see
 * forbids a crit" — which is NOT "a crit will happen". The remaining three terms (blindness,
 * riding, and whether the target is a {@code EntityLivingBase}) are real and are not on the seam:
 * the first two are not on the {@link ActActuator} contract and the third is the server's view of
 * what was hit. A caller that wants a guarantee has none, in vanilla or here, because the
 * multiplier is applied server-side at line 1335 and no packet reports it back.
 *
 * <p><b>Why a crit is a capability at all if the client cannot confirm it.</b> Because the
 * precondition is a body state the player CREATES, not a dice roll: a human presses jump, walks off
 * an edge, and swings at the bottom of the arc. There is exactly one instant in that fall where
 * vanilla's conjunction holds, and it lasts about one tick. An agent that only has {@code
 * InteractIntent.attack} has no way to be there — it submits an attack and the attack lands
 * whenever the tick happens to be. {@link InteractController} in {@code CRIT} mode uses this class
 * to wait for that instant and refuses rather than spending the swing on flat ground.
 *
 * <p><b>The ladder and water terms are not decoration.</b> They are the reason "walk off a cliff and
 * swing" is the whole technique: {@code Entity.moveEntityWithHeading:1642} zeroes {@code
 * fallDistance} when the body is in the clamp branch at the top of a ladder, and {@code
 * handleWaterMovement} zeroes it in water, so a climb or a swim can never bank a crit no matter
 * how far it falls.
 */
public final class CritWindow {

    private CritWindow() {
    }

    /**
     * The first client-visible term of vanilla's conjunction that is false right now, or null.
     *
     * <p>Null means exactly one thing: every term this seam carries holds. The doc on this class
     * says what it does not mean, and callers must not upgrade it.
     */
    public static String firstDisproof(ActActuator act) {
        // Order is vanilla's own left-to-right order, so the message names the term a human would
        // fix first: you cannot crit while standing, whatever else is true.
        if (act.onGround()) {
            return "on the ground (EntityPlayer:1333 requires !onGround)";
        }
        if (act.fallDistance() <= 0.0F) {
            return "fallDistance is " + act.fallDistance()
                    + ", so the body is not falling (EntityPlayer:1333 requires fallDistance > 0)";
        }
        if (act.onClimbable()) {
            return "on a ladder or vine (EntityPlayer:1333 requires !isOnLadder; the clamp branch "
                    + "at EntityLivingBase:1642 zeroes fallDistance on a ladder anyway)";
        }
        if (act.inWater()) {
            return "in water (EntityPlayer:1333 requires !isInWater; handleWaterMovement zeroes "
                    + "fallDistance, so a swim cannot bank a crit)";
        }
        return null;
    }

    /** Whether nothing this seam can see forbids a crit. NOT a promise that one will land. */
    public static boolean open(ActActuator act) {
        return firstDisproof(act) == null;
    }

    /**
     * The client-side rule stated in full, for the words a refusal has to use.
     *
     * <p>The three terms this class cannot read are named rather than dropped, because a message
     * that lists four conditions and stops reads as the complete rule and is not.
     */
    public static String description() {
        return "a critical hit needs the body falling: off the ground, fallDistance > 0, not on a "
                + "ladder, not in water (EntityPlayer:1333). Blindness, riding and hitting a "
                + "living entity are the other three terms and are the server's half -- the 1.5x is "
                + "applied server-side and no packet reports it back, so a crit can be aimed at and "
                + "never confirmed";
    }
}
