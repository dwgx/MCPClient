package net.marcloud.mcp.core.drivers.act;

import java.util.Locale;

/**
 * One hazard sampled on the straight line a walk is following, as DATA rather than as a sentence.
 *
 * <p><b>Why this is a type and not a string.</b> {@link NavController} has always named hazards,
 * and it named them by gluing prose onto the running message. That is readable by a person and by
 * a language model, and it is exactly what audit 2.3 called the remaining half of the defect: a
 * caller polling {@code act_status} gets a sentence to parse, not a value to branch on, so the
 * only way to act on the warning is to have written the parser for it. The audit's own words are
 * "提前警告" — warn in advance — and a warning that cannot be read without string surgery cannot
 * be read in time, because by the time the model has decided what the sentence means the player
 * has walked into the water. So the hazard travels as a field: on {@link ActOutcome}, on
 * {@link SlotRecord}, on {@link ActStatus.SlotStatus}, and out of {@code act_status} as an object.
 *
 * <p><b>It changes nothing about the walk.</b> {@code walk_straight} is documented as never
 * planning and never steering, and this type is a report, not a veto. Nothing reads
 * {@link NavController#hazard()} to decide whether to move: no axis, no arrival test, no terminal
 * branch consults it. Silently refusing to walk would be a different tool wearing this one's
 * name, and a caller that asked to go somewhere deserves an honest walk plus an honest warning
 * rather than a stall it has to diagnose.
 *
 * <p><b>{@code blocksAhead} is the whole point of the type.</b> The string this replaces said
 * "37% of the way there", which on a 400-block line means 148 blocks away and on a 6-block line
 * means two — the same sentence describing both an imminent death and a non-event. A warning is
 * only actionable if it says how many blocks of walking are left before it, so the field is a
 * distance and it is recomputed from the live position every tick for free: the sampled CELL
 * cannot change while the player walks toward it, but the distance to it shrinks, and that is
 * arithmetic on two doubles rather than another world read.
 *
 * <p><b>The distance is SIGNED, and that is the honesty of it.</b> The scan looks ahead, so a
 * hazard leaves the sample as the player walks through it, and the warning stays sticky for the
 * rest of the intent rather than vanishing (see {@link NavController}). An unsigned distance
 * would then read "2.4 blocks ahead" for a cell the player is standing on, which is worse than
 * saying nothing. Negative means the hazard is behind them along the walk line, and
 * {@link #describe()} says "behind" rather than "ahead".
 *
 * <p><b>A hazard is never manufactured.</b> {@code blockAt} answers null for air and for "could
 * not read", so a null cell is an absence and not a fact; {@link NavController} gates the drop
 * test on a floor it has proven it can read, because an unloaded chunk read as a bottomless pit
 * on every line out of it. A warning a caller learns to ignore is worse than no warning, so
 * {@code null} here means "no hazard known", never "hazard of unknown kind".
 *
 * <p><b>Sampled, not exhaustive</b>, and {@link #describe()} says so in those words. A one-block
 * step can straddle a hazard between two sampled cells, so this cannot promise the line is clear.
 * It promises the KNOWN hazards are named, with a distance, while there is still walking left.
 *
 * @param kind        what was found, named rather than described
 * @param x           block X of the sampled hazard cell
 * @param y           block Y of the sampled hazard cell -- the feet's row for lava and water, and
 *                    the row the open air starts under for a drop
 * @param z           block Z of the sampled hazard cell
 * @param blocksAhead SIGNED offset along the walk line from the player to that cell's centre,
 *                    recomputed every tick from the live position: positive is still ahead of the
 *                    player, negative is already behind them. A caller can therefore watch the
 *                    warning close AND see that it has been passed, without parsing anything.
 * @param detail      the consequence -- what this hazard does to a player who walks into it, and
 *                    why it is worth interrupting for. Human-facing only; a caller branches on
 *                    {@code kind}, never on this text.
 */
public record NavHazard(Kind kind, int x, int y, int z, double blocksAhead, String detail) {

    /**
     * The three things worth interrupting a caller for, and the three that killed the player on a
     * live client: a {@code walk_straight} line walked off a 30-block cliff (health 20 to 0) and
     * then into an ocean (drowned). Enumerated rather than free text so a caller can branch on
     * it, and closed so a future scan cannot invent a kind nobody has a policy for.
     */
    public enum Kind {
        /** Never survivable: vanilla applies lava damage to a body standing in it. */
        LAVA,
        /** A {@code walk_straight} line that ends in an ocean walks into it and the player drowns. */
        WATER,
        /**
         * Open air under the line with no floor for {@link NavController}'s whole safe-drop budget.
         * Fall damage is {@code ceil(distance - 3)}, so a 6-block drop already costs 3 of 20 HP and
         * a 30-block drop is death.
         */
        DEEP_DROP
    }

    /** True once the player has walked past this hazard's cell on the line. */
    public boolean behind() {
        return blocksAhead < 0;
    }

    /**
     * The same hazard measured from where the player is now.
     *
     * <p>Cheap enough to run every tick, and that is the reason it exists as a number: the sampled
     * cell is a fact about the world and does not move, so the only part of the warning that
     * changes while the player walks is how far along the line it is.
     */
    public NavHazard aheadBy(double blocks) {
        return new NavHazard(kind, x, y, z, blocks, detail);
    }

    /**
     * The warning as the running message carries it.
     *
     * <p>Sampled on purpose. The word claims less than the scan can promise, and the distance is
     * in blocks rather than percent because a percentage of an unknown total is not a warning.
     */
    public String describe() {
        return String.format(Locale.ROOT, "sampled: %s at (%d,%d,%d), %.1f blocks %s%s",
                label(), x, y, z, Math.abs(blocksAhead), behind() ? "behind" : "ahead",
                detail.isEmpty() ? "" : " -- " + detail);
    }

    private String label() {
        return switch (kind) {
            case LAVA -> "LAVA";
            case WATER -> "WATER";
            // "Drop" rather than "gap": the cell named is where the open air BEGINS, and the depth
            // is in the detail, so the label only has to say what KIND of thing this is.
            case DEEP_DROP -> "an unfloored drop";
        };
    }
}
