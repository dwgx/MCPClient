package net.marcloud.mcp.core.drivers.act;

/**
 * The client-free seam between the pure controller state machines
 * ({@link LookController}, {@link DigController}, {@link InteractController},
 * {@link HoldController}, {@link HotbarController}) and the live game. Every game touch a controller
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

    /**
     * True if a (non-air) block is present at the given coords.
     *
     * <p>Answers "is there anything here", which is the right question for deciding whether a dig
     * has something to start on. It is NOT the right question for deciding whether a dig FINISHED --
     * see {@link #blockAt} for that, and for the defect that distinction was drawn from.
     */
    boolean blockPresent(int x, int y, int z);

    /**
     * The registry name of the block at the given coords ({@code "stone"}, {@code "iron_ore"}), or
     * {@code null} for air, out of range, or no world.
     *
     * <p><b>Why a name and not a boolean.</b> {@link #blockPresent} is
     * {@code getMaterial() != Material.air}, so it answers "is this air" -- and a dig is finished
     * when the TARGETED BLOCK is gone. Measured on a live client, {@code blockPresent} returns true
     * for water, flowing water, lava, gravel, tall grass and a torch: everything except air. So as a
     * completion test it reports "still digging" for any position that has been refilled, when the
     * honest answer is that the block broke.
     *
     * <p><b>What was NOT measured, stated because the first version of this javadoc claimed it.</b>
     * The predicted consequence was that mining stone underwater would report "dig stalled" about a
     * broken block. It does not, and the reason is a race this comment originally got wrong:
     * measured live, water reaches the emptied space <b>3 game ticks</b> after the break
     * (t=362045 to t=362048, water's {@code tickRate} is 5), while {@link DigController} polls once
     * per tick -- so the deciding poll happens while the space is still air and the old emptiness
     * test completed correctly. Lava is slower still ({@code tickRate} 30), and falling gravel
     * becomes an entity rather than a block, so it does not fill the space on the breaking tick
     * either.
     *
     * <p>So this accessor is <b>correctness by construction rather than a fix for an observed
     * failure</b>: it makes the completion test ask the caller's actual question, which holds
     * whatever the refill timing turns out to be on a server with different fluid rates, a modded
     * block that replaces itself instantly, or another player filling the hole. The reachable defect
     * that came with it is the ORDERING one -- {@link DigController} tested the stall before the
     * gone -- which does not depend on refill timing at all.
     *
     * <p>A name rather than an opaque handle because this interface holds no {@code net.minecraft}
     * type by design -- it is the seam that makes the controllers headlessly testable -- and because
     * the same registry names already cross the boundary in {@code world_view} and
     * {@code find_block}, so a caller comparing the two is comparing like with like.
     *
     * <p><b>The one case it cannot separate,</b> stated rather than papered over: digging gravel with
     * more gravel above it. The replacement has the same name as the target, so the identity test
     * reads "still there" and the controller keeps digging the block that fell in. Vanilla offers
     * nothing to distinguish them either -- a block has no per-instance identity -- and the
     * behaviour that results (keep digging until the column is clear) is what a caller asking to dig
     * gravel most likely wants.
     */
    String blockAt(int x, int y, int z);

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

    // ===== locomotion state =====
    //
    // What a closed-loop MOVE controller reads back, in the same spirit as blockPresent for
    // digging: DigController learns the block broke by polling the world, and locomotion needs the
    // same feedback to detect arrival, correct a heading and fail honestly on a jam. Without these
    // the MOVE slot can only count ticks -- MoveApplier reports "moving (tick N/M)", which says the
    // key was held, never that the player went anywhere.
    //
    // Each is one field on the live EntityPlayerSP, which LivePlayerActuator already holds at every
    // method, so the cost is a read rather than any new plumbing.

    /**
     * Feet position as {@code {x, y, z}}, or null when not in a world.
     *
     * <p>Feet rather than eyes because paths, arrival tests and block coordinates are all in feet
     * space; {@link #eyePos()} stays the aiming reference. Returning {@code double[]} matches
     * {@code eyePos}'s shape on purpose -- two conventions for a point in one interface is a trap.
     */
    double[] position();

    /** Whether the player is standing on something; false while falling or jumping. */
    boolean onGround();

    /**
     * Whether the player is pressed against a wall this tick -- the honest jam signal.
     *
     * <p>Preferred over velocity, which does distinguish jammed from walking (measured live: Z
     * component 0.09 against 0.0) but is only reachable through the observe path, and would make a
     * controller choose a float threshold for a question that is already a boolean here.
     */
    boolean collidedHorizontally();

    /** Swing the held item (animation + packet). */
    void swing();

    // ===== sustained use (the INTERACT hold channel) =====
    //
    // What a HOLD controller reads and writes, in the same spirit as the locomotion block above:
    // eating, drawing a bow and blocking are not events, they are STATES vanilla keeps only while
    // its use key is down. Minecraft.java:2118-2122 calls onStoppedUsingItem on ANY tick where
    // gameSettings.keyBindUseItem.isKeyDown() is false, so a one-shot start is cancelled within a
    // couple of ticks -- measured after commit 52647ad: useCount fell 32 -> 0 in about eight ticks
    // and food never rose. The only way to make the use PERSIST is to keep vanilla's own key
    // believing it is held, which is what holdUseKey does, and the only way to end a bow is to stop
    // believing that, which is what releaseUseKey does.

    /**
     * Assert vanilla's use key as held for this tick; returns whether the assertion TOOK.
     *
     * <p>False means the write could not be made at all (no client, or the binding is not in
     * {@code KeyBinding}'s static keyCode hash) -- a condition no number of retries improves, so a
     * controller should fail honestly rather than pump. It does NOT mean "the use stopped": that is
     * {@link #useKeyHeld()}'s question, read at the top of the next tick.
     *
     * <p>Must be re-asserted EVERY tick. {@code KeyBinding.unPressAllKeys} clears every binding
     * whenever a GUI opens ({@code Minecraft.java:1469} via {@code displayGuiScreen}), so a hold
     * that asserts once and trusts it would be silently dropped by a chat window.
     */
    boolean holdUseKey();

    /**
     * Release vanilla's use key; returns whether the write took (same contract as
     * {@link #holdUseKey()}).
     *
     * <p>This is an ACTION, not just cleanup. A bow fires from
     * {@code ItemBow.onPlayerStoppedUsing}, reached only when vanilla observes the key up, so
     * release is the tick the arrow leaves. It is also what stops vanilla immediately starting a
     * FRESH use on the tick a previous one finished, since {@code Minecraft.java:2158} re-fires
     * {@code rightClickMouse} while the key is down and nothing is in use.
     */
    boolean releaseUseKey();

    /**
     * Whether vanilla's use key currently reads as held.
     *
     * <p>Read BEFORE re-asserting, and the only honest way to notice that something else cleared
     * the hold: a GUI opening, focus loss, or the human letting go of a physically-held button.
     * Read-back rather than remembering what we wrote, because what we wrote is not evidence.
     */
    boolean useKeyHeld();

    /** Whether the player is in a sustained item use right now (vanilla's {@code isUsingItem}). */
    boolean isUsingItem();

    /**
     * Vanilla's remaining use count, or 0 when nothing is in use.
     *
     * <p>Carries the item's own duration, which is how a controller can tell a self-terminating use
     * from one that never ends without knowing what the item IS: food starts at 32, a bow and a
     * blocking sword at 72000. It also distinguishes a use that RAN OUT from one that was
     * interrupted -- on the tick vanilla clears the use, a count already at/below zero means it
     * completed, while a count still high means something took the item away.
     *
     * <p>Client-side it counts DOWN one per tick and keeps going negative:
     * {@code EntityPlayer.onUpdate:286} only calls {@code onItemUseFinish} when
     * {@code !worldObj.isRemote}, so on a client the use ends when the server says so
     * ({@code handleStatusUpdate} id 9), not when the count hits zero.
     */
    int itemInUseCount();

    /**
     * The held item's own maximum use duration, or 0 when nothing usable is held.
     *
     * <p>Exists because {@link #itemInUseCount()} counts DOWN, so it answers "how much is left", and
     * every question vanilla actually decides is about how much has ELAPSED. A bow's charge is
     * {@code getMaxItemUseDuration(stack) - timeLeft} ({@code ItemBow.java:32}), so elapsed ticks are
     * only recoverable with this value in hand.
     *
     * <p>The first version of the hold controller substituted the count observed when the controller
     * ADOPTED the use, which agrees with this only when the controller also started it. Adopting a
     * draw a human had already begun made it report a shorter draw than vanilla saw -- and since a
     * draw below three ticks fires no arrow at all, that under-report was a confidently worded claim
     * that nothing was shot when an almost fully charged arrow had been.
     */
    int maxItemUseDuration();

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
