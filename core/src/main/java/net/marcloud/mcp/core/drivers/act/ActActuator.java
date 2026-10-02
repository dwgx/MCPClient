package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.util.Belief;
import net.marcloud.mcp.core.util.Graded;

/**
 * The client-free seam between the pure controller state machines
 * ({@link LookController}, {@link DigController}, {@link InteractController},
 * {@link HoldController}, {@link HotbarController}, {@link DropController}) and the live game.
 * Every game touch a controller needs is a method here; the sole {@code net.minecraft}
 * implementation is
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

    /**
     * What the player is currently looking at (crosshair ray), and how well-earned that is.
     *
     * <p><b>This declaration used to be the cleanest structural lie in this project, and now says
     * so in its own type.</b> It read "What the player is currently looking at (crosshair ray),
     * never null", and the only production implementation satisfied it by returning
     * {@code mc.objectMouseOver}. That field has exactly two writers -- {@code Minecraft.runTick}
     * and {@code EntityRenderer.renderWorld} -- and this act loop fires at {@code runTick} ENTRY,
     * so every act-layer reader has been handed a ray traced against the PREVIOUS frame's
     * rotation, with nothing at the seam able to say so.
     *
     * <p><b>Why the method changed shape instead of gaining a sibling.</b> A second
     * {@code mouseOverGraded()} beside the old one would leave the ungraded door standing open, and
     * the next caller would walk through it and be lied to exactly as before -- while the diff that
     * added the honest one reads like a feature. There are no production consumers to migrate, so
     * the change is clean: the method is not deleted, and it becomes an honest measurement instead
     * of a silent one.
     *
     * <p><b>What a reader can now ask that they could not before:</b> "is the thing I aimed at
     * computed from the rotation I JUST made?" {@code mayActOn()} answers the half of that with an
     * answer available today, and {@link #STALE_ROTATION} says the other half out loud. Before this
     * change the question had no shape to be asked in.
     */
    Graded<Target> mouseOver();

    // ===== the two grades a crosshair read may carry =====
    //
    // Two, and there is no third door. Every implementation reaches its answer through one of these,
    // so no implementation can invent a grade, and a reader comparing two implementations is
    // comparing the same two derivations rather than two private opinions about them.

    /**
     * Why a traced crosshair is {@link Belief#INFERRED} and not {@link Belief#OBSERVED}.
     *
     * <p>A derivation, not a hedge. The value is real -- vanilla traced it -- but the rotation it
     * was traced against is the one the PREVIOUS frame wrote, because this act loop runs at the
     * entry of {@code runTick} and {@code objectMouseOver} is assigned inside {@code runTick}
     * (:1751) and again in {@code renderWorld} ({@code EntityRenderer}:1299), both after we return.
     */
    String STALE_ROTATION = "ray traced against the PREVIOUS frame's rotation: objectMouseOver is "
            + "written inside Minecraft.runTick and EntityRenderer.renderWorld, and this act loop "
            + "fires at runTick entry, so the crosshair predates the rotation this client just "
            + "wrote";

    /**
     * Why a missing client is {@link Belief#UNKNOWN} and not a miss.
     *
     * <p>This is the distinction the old signature could not make at all: {@code Target.miss()} was
     * returned both when the ray genuinely hit nothing and when there was no client to trace with,
     * so a caller could not tell "I looked and it is empty" from "there was nothing to look
     * through". The two answer opposite questions and must not be one value.
     */
    String NO_RAY = "there is no client to trace a crosshair through, so nothing was looked at: "
            + "this is an absence of observation, not a ray that hit nothing";

    /**
     * A crosshair this client traced -- against the rotation of one frame ago.
     *
     * <p>Accepts a {@link Target#miss()} deliberately: a ray that traced and hit nothing is a real
     * observation with a stale aim behind it, which is not the same fact as having no client.
     */
    static Graded<Target> tracedLastFrame(Target traced) {
        return Graded.inferred(traced, STALE_ROTATION);
    }

    /** No client, so no ray was traced at all and nothing about the crosshair is known. */
    static Graded<Target> noRay() {
        return Graded.unknown(Target.miss(), NO_RAY);
    }

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

    /**
     * Whether the player is on a ladder or a vine right now -- vanilla's
     * {@code EntityLivingBase.isOnLadder}, unchanged.
     *
     * <p>Exists because {@link #onGround()} cannot answer the question a climb has to ask. A
     * ladder holds a player up without ever setting {@code onGround}, so "is the world holding
     * this player up" and "is the player standing on something" are the same question on the
     * ground and different questions four blocks up a shaft. A route onto a ladder cell is
     * executable and verifiable only if the seam can say which of the two is true.
     *
     * <p>Not defaulted, for the reason {@link LocomotionController#jump()} is not: a default of
     * false here would be a hardcoded "never climbing" that every future implementation would
     * inherit silently, and the failure it produces is a controller that can never complete a
     * climb while reporting a plausible-looking reason.
     */
    boolean onClimbable();

    /**
     * Whether the player is in water right now -- vanilla's {@code Entity.isInWater}, which is the
     * {@code inWater} flag {@code handleWaterMovement} maintains.
     *
     * <p>The same gap as {@link #onClimbable()}, and the same reason it cannot be inferred: a
     * swimmer is not on the ground, and "supported" for a swimmer means the water, which nothing
     * else on this interface reports.
     */
    boolean inWater();

    /**
     * Vanilla's remaining air, in ticks, or {@code -1} when it cannot be read.
     *
     * <p>Not a convenience. This is the number a route spends as it swims, and a controller that
     * guessed at it would be reporting on a resource it never observed: vanilla fills it to 300 out
     * of water and drains one per tick submerged
     * ({@code EntityLivingBase:301,326,439}), and {@code -1} is the honest "unknown", which is why
     * it is not 0 -- 0 would read as "drowning" and drive a caller to cancel a swim that is fine.
     */
    int air();
    /**
     * Vanilla's {@code Entity.fallDistance}, in blocks.
     *
     * <p>Maintained by the client alone ({@code Entity.updateFallState:1034-1055}: it accumulates
     * downward motion, is zeroed the tick the body lands, and is halved in water at {@code
     * Entity.java:511}), so unlike health or damage this is a fact the seam can state outright.
     * It is the input to two things vanilla decides with it: {@link CritWindow}'s conjunction and
     * {@link net.marcloud.mcp.core.drivers.world.FallDamage}'s arithmetic.
     *
     * <p>Not defaulted, for {@link #onGround()}'s reason: a default of 0.0 here reads as "never
     * falling", which is a hardcoded "a crit is impossible" every implementation would inherit
     * silently.
     */
    double fallDistance();

    /**
     * Whether the player is BLOCKING right now — vanilla's {@code EntityPlayer.isBlocking()},
     * unchanged.
     *
     * <p>Distinct from {@link #isUsingItem()} on purpose and not a convenience: a shield's use and
     * a bread's use are both {@code isUsingItem() == true}, and only one of them is a block. A
     * caller that checked {@code isUsingItem} to answer "am I blocking" would be right about food
     * and wrong about every other held item. {@code isBlocking} is
     * {@code isUsingItem() && itemInUse.getItem().getItemUseAction(itemInUse) == EnumAction.BLOCK}
     * ({@code EntityPlayer.java:257-260}).
     *
     * <p>The use ACTION is not on this seam either, for the same reason the crit's blindness term
     * is not: the item's own declaration is the client's to read and vanilla's to apply, and an
     * interface that held it would hold an {@code net.minecraft} type.
     */
    boolean blocking();

    // ===== the clock =====
    //
    // Two reads, and the pairing is the point. `isDaytime()` on its own would be a boolean with
    // no evidence behind it; `worldTime()` on its own is a number no caller can interpret without
    // re-deriving the curve. A reader that wants to know WHY it is night, or to check the answer
    // against its own arithmetic, needs both, and the seam that has only one of them forces one of
    // those two failures.
    //
    // What this is NOT: a decision input. Nothing in a controller may branch on these. The Owner's
    // acceptance object is the weak model's decision, and a Java component that chooses an action
    // because the clock says so replaces that decision with an arithmetic fact
    // (2026-10-01-northstar-intent.md:557 -- 策略不许写进 Java). A clock is an OBSERVATION: it is
    // reportable, measurable and assertable, and the model decides what to do about it. That is
    // why both reads are plain accessors in the same shape as `air()` and `fallDistance()` and not
    // something a controller is handed a "safe until dawn" verdict by.

    /**
     * Vanilla's {@code World.getWorldTime}, the tick within the day since the epoch (NOT
     * {@code getTotalWorldTime}, which counts from world creation and is a different number).
     *
     * <p>One tick per game tick while {@code doDaylightCycle} holds, on the client as well as the
     * server ({@code WorldClient.java:71-74}), and corrected by the server's
     * {@code S03PacketTimeUpdate} about once a second. So it is a live clock on a multiplayer
     * client -- which is exactly what makes the frozen {@code isDaytime} a defect rather than a
     * design: the world knows what time it is and the day/night read was not consulting it.
     *
     * <p>May be negative: {@code /time set} and a restored save both reach it, and the
     * normalisation that makes it comparable with a clock reading lives in
     * {@link net.marcloud.mcp.core.drivers.world.WorldViewCapture#timeOfDay(long)} rather than
     * here, so a caller cannot quietly apply it twice.
     */
    long worldTime();

    /**
     * Whether the world is in daylight, computed from {@link #worldTime()}.
     *
     * <p><b>The one thing an implementation must not do here is delegate to
     * {@code World.isDaytime()}.</b> That is the frozen read this pair exists to replace: on a
     * multiplayer client it is {@code skylightSubtracted < 4} against a field written once in the
     * {@code WorldClient} constructor and never again, so it answers "day" at midnight, forever.
     * A production implementation that delegated would be correct-looking and identically broken,
     * which is worse than the original defect because it would look fixed.
     */
    boolean isDaytime();


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

    // ===== inventory / drop =====
    //
    // What a DROP controller reads and writes, in the same spirit as the locomotion and
    // sustained-use blocks above. A drop is not an event either: `Container.slotClick` mode 4
    // (Container.java:445-457) decrements the slot and hands the stack to
    // `dropPlayerItemWithRandomChoice`, so the only confirmable fact is what the slot reads
    // AFTERWARDS -- and `PlayerControllerMP.windowClick` applies the click to the client's own
    // container before the packet is even queued (:534-539), so a send that is never read back
    // is a claim about nothing.

    /**
     * How many items are in player inventory slot {@code playerSlot} (0-35, vanilla's
     * {@code InventoryPlayer.mainInventory} indexing, so 0-8 is the hotbar), or 0 when the slot
     * is empty, out of range, or unreadable.
     *
     * <p>The index is the INVENTORY's, not a container's. Every container numbers the same 36
     * stacks differently -- {@code ContainerPlayer} puts them at 9..44, main inventory before
     * hotbar -- and that inversion is what {@code SimCraftWindow}'s own javadoc exists to warn
     * about. Doing the translation inside the actuator keeps a caller from having to know which
     * window is open, or whether one is.
     *
     * <p>0 rather than -1 for unreadable, because 0 is also what an empty slot says and a drop
     * has to treat the two the same way: there is nothing there to throw.
     */
    int slotStackSize(int playerSlot);

    /**
     * Throw the whole stack in player inventory slot {@code playerSlot} out onto the floor, as
     * vanilla's drop-from-a-slot click does ({@code SlotClickMode#DROP_SLOT}, mode 4, with
     * {@code clickedButton} 1 -- {@code Container.java:452} decrements by
     * {@code slot.getStack().stackSize}, the whole stack, and calls
     * {@code dropPlayerItemWithRandomChoice}).
     *
     * <p>The return value answers "was the click ISSUED", not "did the stack leave": a mode-4
     * click on a window whose slot refuses the pickup, on a spectator, or with no
     * {@code PlayerControllerMP} at all, is accepted and does nothing, which is the same
     * silently-ignored state {@code SlotClickMode#CREATIVE_TAKE_STACK} describes. So the return
     * is never the whole answer, and {@link DropController} re-reads {@link #slotStackSize} to
     * get the rest of it.
     */
    boolean dropStack(int playerSlot);

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
