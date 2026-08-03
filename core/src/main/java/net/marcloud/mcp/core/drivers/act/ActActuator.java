package net.marcloud.mcp.core.drivers.act;

/**
 * The client-free seam between the pure controller state machines
 * ({@link LookController}, {@link DigController}, {@link InteractController},
 * {@link HotbarController}) and the live game. Every game touch a controller
 * needs is a method here; the sole {@code net.minecraft} implementation is
 * {@link LivePlayerActuator}, and tests drive the controllers through a
 * scriptable {@code FakeActuator}. This is the wedge that makes the whole
 * action layer headlessly testable.
 *
 * <p>All methods run on the GAME THREAD (the controllers are ticked by
 * {@link ActTickLoop}); implementations must not marshal threads themselves.
 * Read accessors return neutral values ({@code false}/empty) when there is no
 * world rather than throwing, so a controller can honestly fail instead of
 * blowing up.
 */
public interface ActActuator {

    // ===== world / player reads =====

    /** True if the player is in a world (safe to act). */
    boolean inWorld();

    /** Player eye position {@code [x,y,z]} in world coords, or null if not in world. */
    double[] eyePos();

    /** Current yaw in degrees. */
    float yaw();

    /** Current pitch in degrees. */
    float pitch();

    /** Player block-reach distance (game-mode dependent). */
    double reachDistance();

    /** What the player is currently looking at (crosshair ray), never null. */
    Target mouseOver();

    /** True if a (non-air) block is present at the given coords. */
    boolean blockPresent(int x, int y, int z);

    /** The current hotbar slot (0-8). */
    int heldSlot();

    /** Eye position {@code [x,y,z]} of the entity with {@code id}, or null if gone. */
    double[] entityEyePos(int id);

    // ===== rotation =====

    /** Snap rotation to {@code yaw}/{@code pitch} (prev==cur; no interpolation). */
    void setRotation(float yaw, float pitch);

    /**
     * Set both the previous and current rotation explicitly, so the client can
     * render a smooth slew step ({@code pYaw}/{@code pPitch} = previous frame,
     * {@code yaw}/{@code pitch} = this frame).
     */
    void setRotationInterp(float pYaw, float pPitch, float yaw, float pitch);

    // ===== dig (multi-tick) =====

    /** Begin breaking the block; returns whether the controller accepted the start. */
    boolean startDig(int x, int y, int z, Face face);

    /** Continue breaking the block one tick; returns whether damage was applied. */
    boolean pumpDig(int x, int y, int z, Face face);

    /** Abort any in-progress dig. */
    void cancelDig();

    /** Instantly break the block (creative); returns whether it broke. */
    boolean instantBreak(int x, int y, int z, Face face);

    // ===== use / place / attack =====

    /** Right-click a block face at within-block hit offset (hx,hy,hz). */
    boolean rightClickBlock(int x, int y, int z, Face face, double hx, double hy, double hz);

    /**
     * Use the held item in the air; returns whether the use STARTED.
     *
     * <p><b>Not the same question as {@code PlayerControllerMP.sendUseItem}'s return value</b>, and
     * the difference is load-bearing. That method answers "did the stack change", so for anything
     * with a use DURATION -- food, a bow, a potion -- it returns false even though the use began:
     * vanilla's {@code onItemRightClick} for those items calls {@code setItemInUse} and hands back
     * the same stack. Measured on a live client with bread: {@code sendUseItem} returned false while
     * {@code getItemInUseCount()} went to 32 and {@code getItemInUse()} became non-null. Reporting
     * that as a rejection made {@code InteractController} fail with "use rejected in air" on a use
     * that had in fact started, which points the reader at the wrong thing entirely.
     */
    boolean useItemInAir();

    /** Attack the entity with {@code id}; returns whether the attack was dispatched. */
    boolean attackEntity(int id);

    /** Swing the held item (animation + packet). */
    void swing();

    // ===== hotbar =====

    /** Select hotbar {@code slot} (0-8). */
    void setHeldSlot(int slot);

    // ===== value types =====

    /** A block face, mapped to {@code EnumFacing} inside {@link LivePlayerActuator}. */
    enum Face {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;

        /** Vanilla facing index (D-U-N-S-W-E = 0-5). */
        public int index() {
            return ordinal();
        }

        /** Face for a vanilla index 0-5, or {@link #DOWN} if out of range. */
        public static Face fromIndex(int i) {
            Face[] v = values();
            return i >= 0 && i < v.length ? v[i] : DOWN;
        }
    }

    /**
     * A crosshair ray-trace result.
     *
     * @param kind     BLOCK, ENTITY, or MISS
     * @param x        block X (BLOCK)
     * @param y        block Y (BLOCK)
     * @param z        block Z (BLOCK)
     * @param side     which face was hit (BLOCK), else null
     * @param hitVec   hit point {@code [x,y,z]} in world coords, or null
     * @param entityId hit entity id (ENTITY), else {@code -1}
     * @param dist     distance from the eye to the hit
     */
    record Target(Kind kind, int x, int y, int z, Face side, double[] hitVec, int entityId, double dist) {

        /** What the crosshair ray hit. */
        public enum Kind { BLOCK, ENTITY, MISS }

        /** The empty "hit nothing" target. */
        public static Target miss() {
            return new Target(Kind.MISS, 0, 0, 0, null, null, -1, 0.0);
        }

        /** A block hit. */
        public static Target block(int x, int y, int z, Face side, double[] hitVec, double dist) {
            return new Target(Kind.BLOCK, x, y, z, side, hitVec, -1, dist);
        }

        /** An entity hit. */
        public static Target entity(int entityId, double[] hitVec, double dist) {
            return new Target(Kind.ENTITY, 0, 0, 0, null, hitVec, entityId, dist);
        }
    }
}
