package net.marcloud.mcp.core.drivers.act;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A scriptable, in-memory {@link ActActuator} for headless controller tests. It
 * records every call and lets a test program the world it presents: eye position,
 * reach, present blocks, entity positions, and the boolean results the dig/use/
 * place/attack methods return. No {@code net.minecraft} type is touched, so the
 * controller state machines can be exercised entirely in the JVM.
 *
 * <p>The sustained-use fields are not just recorders: {@link #advanceGameTick()} SIMULATES
 * vanilla's own use lifecycle, because a hold that is never contradicted is not a hold. See its
 * javadoc for which lines of {@code Minecraft}/{@code EntityPlayer} each rule comes from.
 */
class FakeActuator implements ActActuator {

    // ---- programmable world ----
    boolean inWorld = true;
    double[] eye = {0, 1.62, 0};
    float yaw = 0f;
    float pitch = 0f;
    double reach = 4.5;
    Target mouseOver = Target.miss();
    final Set<Long> presentBlocks = new HashSet<>();
    final java.util.Map<Integer, double[]> entityEyes = new java.util.HashMap<>();
    int heldSlot = 0;
    /** Feet position, mutable so a test can script movement between controller ticks. */
    final double[] pos = {0, 0, 0};
    boolean onGround = true;
    boolean collidedHorizontally = false;

    // ---- programmable results ----
    /** startDig returns false this many times, then true. */
    int startDigFailFirst = 0;
    /** pumpDig returns false (stall) once we reach this pump count (0 = never). */
    int pumpStallAt = 0;
    /** number of pumpDig calls after which the target block disappears (0 = never). */
    int breakAfterPumps = 0;
    boolean rightClickResult = true;
    boolean useInAirResult = true;
    boolean attackResult = true;
    boolean instantBreakResult = true;

    // ---- sustained use (see advanceGameTick) ----
    /** Whether the use-key writes are allowed to take at all (false models a missing binding). */
    boolean useKeyWritable = true;
    /** Vanilla's use key state, as a controller would read it back. */
    boolean useKeyDown = false;
    /** Whether a use is in progress, i.e. vanilla's isUsingItem. */
    boolean usingItem = false;
    /** Vanilla's remaining use count while {@link #usingItem}. */
    int useCount = 0;
    /** Count a successful {@code useItemInAir} starts a use with (32 = food, 72000 = bow/sword). */
    int useStartCount = 32;
    /**
     * The held item's own max use duration, i.e. vanilla's {@code getMaxItemUseDuration}.
     *
     * <p>Separate from {@link #useStartCount} on purpose, and that separation is the whole point of
     * this field: they are equal only when the use STARTED here. Setting useStartCount below this
     * models a use already in progress when the controller adopts it -- a human holding right-click
     * before the hold arrives -- which is the case where a controller measuring elapsed ticks from
     * its own first observation disagrees with vanilla, which measures from this.
     */
    int maxUseDuration = 32;
    /** If false, a successful {@code useItemInAir} changes the stack but starts no sustained use. */
    boolean useStartsSustained = true;
    /**
     * Ticks after the client count reaches 0 before the use clears, modelling the server's status
     * id 9 round trip. 0 exercises the tightest boundary the controller has to survive.
     */
    int serverFinishDelayTicks = 2;
    /** Times {@link #advanceGameTick} restarted a use because the key was still down. */
    int autoStarts;

    // ---- recorded calls ----
    final List<String> calls = new ArrayList<>();
    int startDigCalls;
    int pumpDigCalls;
    int cancelDigCalls;
    int swingCalls;
    int attackCalls;
    int rightClickCalls;
    int useInAirCalls;
    int holdUseKeyCalls;
    int releaseUseKeyCalls;
    Float lastSetYaw;
    Float lastSetPitch;
    Float lastPrevYaw;
    Float lastPrevPitch;
    boolean lastSetWasSnap;

    static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }

    void putBlock(int x, int y, int z) {
        presentBlocks.add(key(x, y, z));
    }

    void removeBlock(int x, int y, int z) {
        presentBlocks.remove(key(x, y, z));
    }

    // ===== ActActuator =====

    @Override
    public boolean inWorld() {
        return inWorld;
    }

    @Override
    public double[] eyePos() {
        return inWorld ? eye : null;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public double reachDistance() {
        return reach;
    }

    @Override
    public Target mouseOver() {
        return mouseOver;
    }

    @Override
    public boolean blockPresent(int x, int y, int z) {
        return presentBlocks.contains(key(x, y, z));
    }

    @Override
    public int heldSlot() {
        return heldSlot;
    }

    @Override
    public double[] entityEyePos(int id) {
        return entityEyes.get(id);
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        calls.add("setRotation(" + yaw + "," + pitch + ")");
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastSetYaw = yaw;
        this.lastSetPitch = pitch;
        this.lastPrevYaw = yaw;
        this.lastPrevPitch = pitch;
        this.lastSetWasSnap = true;
    }

    @Override
    public void setRotationInterp(float pYaw, float pPitch, float yaw, float pitch) {
        calls.add("setRotationInterp(" + pYaw + "," + pPitch + "," + yaw + "," + pitch + ")");
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastSetYaw = yaw;
        this.lastSetPitch = pitch;
        this.lastPrevYaw = pYaw;
        this.lastPrevPitch = pPitch;
        this.lastSetWasSnap = false;
    }

    @Override
    public boolean startDig(int x, int y, int z, Face face) {
        startDigCalls++;
        calls.add("startDig(" + x + "," + y + "," + z + "," + face + ")");
        if (startDigCalls <= startDigFailFirst) {
            return false;
        }
        return true;
    }

    @Override
    public boolean pumpDig(int x, int y, int z, Face face) {
        pumpDigCalls++;
        calls.add("pumpDig#" + pumpDigCalls + "(" + x + "," + y + "," + z + ")");
        if (pumpStallAt > 0 && pumpDigCalls >= pumpStallAt) {
            return false;
        }
        if (breakAfterPumps > 0 && pumpDigCalls >= breakAfterPumps) {
            removeBlock(x, y, z);
        }
        return true;
    }

    @Override
    public void cancelDig() {
        cancelDigCalls++;
        calls.add("cancelDig()");
    }

    @Override
    public boolean instantBreak(int x, int y, int z, Face face) {
        calls.add("instantBreak(" + x + "," + y + "," + z + ")");
        if (instantBreakResult) {
            removeBlock(x, y, z);
        }
        return instantBreakResult;
    }

    @Override
    public boolean rightClickBlock(int x, int y, int z, Face face, double hx, double hy, double hz) {
        rightClickCalls++;
        calls.add("rightClickBlock(" + x + "," + y + "," + z + "," + face + ")");
        return rightClickResult;
    }

    @Override
    public boolean useItemInAir() {
        useInAirCalls++;
        calls.add("useItemInAir()");
        if (useInAirResult && useStartsSustained) {
            // Synchronous on the live client too: sendUseItem -> onItemRightClick -> setItemInUse all
            // run on the game thread inside the call, so isUsingItem is true before it returns.
            usingItem = true;
            useCount = useStartCount;
        }
        return useInAirResult;
    }

    // ---- sustained use ----

    @Override
    public boolean holdUseKey() {
        holdUseKeyCalls++;
        calls.add("holdUseKey()");
        if (!useKeyWritable) {
            return false;
        }
        useKeyDown = true;
        return true;
    }

    @Override
    public boolean releaseUseKey() {
        releaseUseKeyCalls++;
        calls.add("releaseUseKey()");
        if (!useKeyWritable) {
            return false;
        }
        useKeyDown = false;
        return true;
    }

    @Override
    public boolean useKeyHeld() {
        return useKeyDown;
    }

    @Override
    public boolean isUsingItem() {
        return usingItem;
    }

    @Override
    public int itemInUseCount() {
        return usingItem ? useCount : 0;
    }

    @Override
    public int maxItemUseDuration() {
        return maxUseDuration;
    }

    /**
     * The rest of the game tick, AFTER the controller ran. Call this between controller ticks or every
     * hold assertion in a test is vacuous.
     *
     * <p>This models the four vanilla behaviours a hold lives or dies by, and THE ORDER IS THE POINT
     * -- it is the order they occur in within one {@code Minecraft.runTick}:
     *
     * <ol>
     *   <li><b>Auto-release</b> ({@code Minecraft.java:2118-2122}). On any tick the use key is not
     *       down while an item is in use, {@code onStoppedUsingItem} ends it. This is the whole
     *       reason the hold channel exists, so without it here a "still using after 30 ticks"
     *       assertion would pass for a controller that asserts nothing.
     *   <li><b>Auto-restart</b> ({@code Minecraft.java:2158}). A fresh use starts when the key is
     *       down and nothing is in use -- this is why holding right-click eats a whole stack. A
     *       controller that forgets to release when a use completes consumes a second item, and
     *       {@link #autoStarts} is how a test sees that.
     *   <li><b>The count runs down</b> ({@code EntityPlayer.onUpdate:275-295}, reached from
     *       {@code Minecraft.java:2202}). Once per tick, and on a client it keeps going past zero:
     *       {@code onItemUseFinish} there is server-only.
     *   <li><b>The server's finish arrives</b> ({@code handleStatusUpdate} id 9 →
     *       {@code EntityPlayer.java:509-511}), after {@link #serverFinishDelayTicks}, which is what
     *       actually clears the use on a client. An earlier version of this note cited
     *       {@code Minecraft.java:2261} as the delivery point; that line is in the
     *       {@code theWorld == null} branch and is unreachable in a world. Inbound packets are
     *       enqueued by {@code PacketThreadUtil} and drained at {@code Minecraft.java:1101}, i.e.
     *       BEFORE {@code runTick} rather than at the end of it. The delay this fake applies is still
     *       the right shape -- the finish is a round trip, not instantaneous -- but do not trust the
     *       old line number if you are reasoning about within-tick ordering.
     * </ol>
     *
     * <p>Steps 2 and 4 in that order are what makes an UNTIL_DONE hold observable at all: the finish
     * lands at the END of a tick, after the restart check has already passed, so a controller running
     * at the TOP of the next tick sees the cleared use and can release before vanilla would restart
     * it. Model the restart after the clear instead and no controller could ever see a completion --
     * which is how this fake was written first, and the ordering bug looked exactly like a controller
     * bug.
     *
     * <p>Simplified deliberately in one respect: vanilla gates the restart behind
     * {@code rightClickDelayTimer == 0}, which this ignores, so restart is modelled at its earliest
     * possible tick. That is the worst case, and the worst case is what a hold has to survive.
     */
    /**
     * Model a screen being open, which SUSPENDS vanilla's key-up-ends-the-use coupling.
     *
     * <p>Not a detail. That whole block, the stop branch included, sits inside
     * {@code if (currentScreen == null || currentScreen.allowUserInput)} at
     * {@code Minecraft.java:1829}, and {@code allowUserInput} is a bare field defaulting false that
     * only {@code GuiInventory} and {@code GuiContainerCreative} set. So with chat, the pause menu, a
     * chest or a furnace open, the key can be down or up and vanilla ends nothing -- the use keeps
     * ticking. Until this flag existed the fake coupled key-up to use-stop unconditionally, so it
     * agreed with a controller that reported "vanilla has already stopped the use", and no test could
     * see that the claim was backwards on the commonest path there is.
     */
    boolean screenGatesVanillaStop = false;

    void advanceGameTick() {
        if (screenGatesVanillaStop) {
            // Vanilla's own gate is closed: no stop, no restart. The use itself still runs down,
            // because EntityPlayer.onUpdate is reached from a different call site that the screen
            // guard does not cover.
            if (usingItem) {
                useCount--;
                if (useCount <= -serverFinishDelayTicks) {
                    usingItem = false;
                    useCount = 0;
                }
            }
            return;
        }
        if (usingItem && !useKeyDown) {
            usingItem = false;
            useCount = 0;
            return;
        }
        if (!usingItem && useKeyDown && useStartsSustained) {
            autoStarts++;
            usingItem = true;
            useCount = useStartCount;
        }
        if (usingItem) {
            useCount--;
            if (useCount <= -serverFinishDelayTicks) {
                usingItem = false;
                useCount = 0;
            }
        }
    }

    /** Take the item away mid-use, the way a hotbar switch clears vanilla's itemInUse. */
    void interruptUse() {
        usingItem = false;
        useCount = 0;
    }

    /**
     * Interrupt by SWITCHING HOTBAR SLOT, which is what vanilla's own clearing path is about.
     *
     * <p>Distinct from {@link #interruptUse} because the slot is the observable that separates an
     * interruption from a completion in the ticks where the clock cannot: near the end of a meal both
     * leave a small count, and a controller reading only the count calls the interruption a success.
     * A fake that clears the use without moving the slot can never expose that.
     */
    void interruptBySwitchingSlot(int toSlot) {
        heldSlot = toSlot;
        usingItem = false;
        useCount = 0;
    }

    // ---- locomotion state ----

    @Override
    public double[] position() {
        calls.add("position()");
        return new double[] {pos[0], pos[1], pos[2]};
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public boolean collidedHorizontally() {
        return collidedHorizontally;
    }

    /**
     * Move the fake player, the way a test scripts a dig breaking.
     *
     * <p>Present so a nav test can advance the world between ticks without reaching into the
     * field, which is what {@code putBlock}/{@code removeBlock} already do for digging.
     */
    void setPosition(double x, double y, double z) {
        pos[0] = x;
        pos[1] = y;
        pos[2] = z;
    }

    /** Advance by a delta, for scripting a controller that is making progress. */
    void nudge(double dx, double dy, double dz) {
        pos[0] += dx;
        pos[1] += dy;
        pos[2] += dz;
    }

    @Override
    public boolean attackEntity(int id) {
        attackCalls++;
        calls.add("attackEntity(" + id + ")");
        return attackResult;
    }

    @Override
    public void swing() {
        swingCalls++;
        calls.add("swing()");
    }

    @Override
    public void setHeldSlot(int slot) {
        calls.add("setHeldSlot(" + slot + ")");
        if (slot >= 0 && slot <= 8) {
            this.heldSlot = slot;
        }
    }
}
