package net.marcloud.mcp.core.drivers.act;

import java.util.List;
import net.marcloud.mcp.core.util.Belief;

/**
 * An immutable snapshot of the whole act runtime for the {@code act_status} tool:
 * the current clock tick plus one line per slot. Built off a consistent read of
 * each slot's {@link SlotRecord} so the AI can see "what am I doing right now and
 * did the last thing finish".
 *
 * @param tickNow the clock tick at snapshot time
 * @param slots   one entry per {@link ActSlot}, in enum order
 */
public record ActStatus(long tickNow, List<SlotStatus> slots) {

    /**
     * One slot's public status.
     *
     * @param slot        which slot
     * @param phase       lifecycle phase
     * @param hasIntent   whether an intent currently occupies the slot
     * @param intentKind  a short label for the intent ("MOVE"/"LOOK:SET"/"INTERACT:DIG"/"-")
     * @param ticksActive how many ticks it has been ACTIVE
     * @param message     last human-readable status line
     * @param hazard      the hazard sampled on the line being walked, or null when none is known.
     *                    Present on every tick of a straight-line walk, not only the terminal one,
     *                    so a caller polling this sees the warning while there is still distance
     *                    left to act on it. See {@link NavHazard}.
     * @param belief      how well-earned {@code message} is as a statement about the world, or null
     *                    when no site has examined it. Null and {@link Belief#UNKNOWN} are different
     *                    values and mean different things -- null is "nobody looked", UNKNOWN is
     *                    "this client looked and could not see" -- so a reader can tell a line no
     *                    site examined from a refusal that counted unread cells. Without it the
     *                    sentence above is the whole answer, and a caller has to guess how much of
     *                    it to believe.
     * @param tactic      the movement decision the walk made on the tick being reported -- the keys
     *                    it published, the recovery lane it was on, and what it gave up to take it
     *                    rather than something else -- or null when no tactic has been chosen. See
     *                    {@link MoveTactic}.
     * @param unreadCells how many cells a search asked about and could not read, or null when the
     *                    line is not a statement about a searched area. Beside the grade and not
     *                    inside it: a grade says how a value was obtained, a count is a fact about
     *                    the world the claim was about. null and 0 are different claims and a caller
     *                    reading one for the other would read a capability it was never given.
     */
    public record SlotStatus(
            ActSlot slot,
            ActPhase phase,
            boolean hasIntent,
            String intentKind,
            int ticksActive,
            String message,
            NavHazard hazard,
            Belief belief,
            MoveTactic tactic,
            Integer unreadCells) {

        /**
         * The nine-field shape: a grade and a tactic, no count.
         *
         * <p>Carries {@link SlotRecord#NO_COUNT} and not {@code 0} -- a line that ran no search must
         * not claim that a search found nothing unread.
         */
        public SlotStatus(ActSlot slot, ActPhase phase, boolean hasIntent, String intentKind,
                          int ticksActive, String message, NavHazard hazard, Belief belief,
                          MoveTactic tactic) {
            this(slot, phase, hasIntent, intentKind, ticksActive, message, hazard, belief, tactic,
                    SlotRecord.NO_COUNT);
        }

        /**
         * The eight-field shape, unchanged in behaviour: the belief without a tactic.
         *
         * <p>Kept because {@code belief} arrived on its own and every construction site that
         * predates the tactic still has to compile. A field that changed the meaning of an existing
         * constructor would make the tactic look like it was always there, which is the one thing it
         * must not look like.
         */
        public SlotStatus(ActSlot slot, ActPhase phase, boolean hasIntent, String intentKind,
                          int ticksActive, String message, NavHazard hazard, Belief belief) {
            this(slot, phase, hasIntent, intentKind, ticksActive, message, hazard, belief, null,
                    SlotRecord.NO_COUNT);
        }

        /**
         * The seven-field shape, byte-identical in behaviour to what it was before the grade
         * existed, and carrying {@link SlotRecord#UNGRADED} rather than a claim nobody made.
         */
        public SlotStatus(ActSlot slot, ActPhase phase, boolean hasIntent, String intentKind,
                          int ticksActive, String message, NavHazard hazard) {
            this(slot, phase, hasIntent, intentKind, ticksActive, message, hazard,
                    SlotRecord.UNGRADED, null, SlotRecord.NO_COUNT);
        }

        /**
         * Whether {@code message} is a claim this line may be acted on without looking again.
         *
         * <p>Present for the reason {@link SlotRecord#mayActOn()} is: a bare
         * {@code belief != Belief.UNKNOWN} test fails OPEN against a null default, and this is the
         * one door that refuses in both cases.
         */
        public boolean mayActOn() {
            return belief != null && belief != Belief.UNKNOWN;
        }
    }
}
