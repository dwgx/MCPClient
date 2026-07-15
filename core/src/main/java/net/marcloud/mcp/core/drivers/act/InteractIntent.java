package net.marcloud.mcp.core.drivers.act;

/**
 * A world-interaction intent for the {@link ActSlot#INTERACT} slot. One record
 * covers the whole interaction family, discriminated by {@link Kind}:
 *
 * <ul>
 *   <li>{@link Kind#DIG} — break a block (multi-tick: start, pump, poll for gone).
 *   <li>{@link Kind#USE} — right-click use in air or on a block.
 *   <li>{@link Kind#PLACE} — place/activate against a block face at a hit point.
 *   <li>{@link Kind#ATTACK} — left-click attack an entity.
 *   <li>{@link Kind#HOTBAR} — select a hotbar slot (0-8).
 * </ul>
 *
 * <p>Block coordinates + {@code face} are used by DIG/PLACE/USE-on-block;
 * {@code entityId} by ATTACK; {@code hotbarSlot} by HOTBAR. {@code hitX/Y/Z} is the
 * within-block hit offset for PLACE (defaults to the face center when unset).
 * All fields are plain data — no live game references — so the intent is safe to
 * build off-thread.
 *
 * @param kind      which interaction
 * @param blockX    target block X (DIG/PLACE/USE-on-block)
 * @param blockY    target block Y
 * @param blockZ    target block Z
 * @param hasBlock  true if a block target is supplied
 * @param face      block face index 0-5 (D-U-N-S-W-E), or {@code -1} for none
 * @param hitX      within-block hit offset X (PLACE), typically 0..1
 * @param hitY      within-block hit offset Y (PLACE)
 * @param hitZ      within-block hit offset Z (PLACE)
 * @param entityId   target entity id (ATTACK), or {@code -1}
 * @param hotbarSlot hotbar slot 0-8 (HOTBAR)
 */
public record InteractIntent(
        Kind kind,
        int blockX,
        int blockY,
        int blockZ,
        boolean hasBlock,
        int face,
        double hitX,
        double hitY,
        double hitZ,
        int entityId,
        int hotbarSlot) implements ActIntent {

    /** The interaction family. */
    public enum Kind {
        /** Break a block over multiple ticks. */
        DIG,
        /** Right-click use (in air, or on a block if a block target is set). */
        USE,
        /** Place/activate against a block face at a hit point. */
        PLACE,
        /** Left-click attack an entity. */
        ATTACK,
        /** Select a hotbar slot. */
        HOTBAR
    }

    /** Dig the given block, approaching from {@code face} (0-5). */
    public static InteractIntent dig(int x, int y, int z, int face) {
        return new InteractIntent(Kind.DIG, x, y, z, true, face, 0, 0, 0, -1, -1);
    }

    /** Use the held item in the air. */
    public static InteractIntent useInAir() {
        return new InteractIntent(Kind.USE, 0, 0, 0, false, -1, 0, 0, 0, -1, -1);
    }

    /** Place/activate against {@code face} of the given block at hit offset (hx,hy,hz). */
    public static InteractIntent place(int x, int y, int z, int face, double hx, double hy, double hz) {
        return new InteractIntent(Kind.PLACE, x, y, z, true, face, hx, hy, hz, -1, -1);
    }

    /** Attack the entity with the given id. */
    public static InteractIntent attack(int entityId) {
        return new InteractIntent(Kind.ATTACK, 0, 0, 0, false, -1, 0, 0, 0, entityId, -1);
    }

    /** Select hotbar {@code slot} (0-8). */
    public static InteractIntent hotbar(int slot) {
        return new InteractIntent(Kind.HOTBAR, 0, 0, 0, false, -1, 0, 0, 0, -1, slot);
    }

    @Override
    public ActSlot slot() {
        return ActSlot.INTERACT;
    }
}
