package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.drivers.world.Daylight;
import net.marcloud.mcp.core.util.Graded;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * The sole {@code net.minecraft} implementation of {@link ActActuator}: everything
 * that touches {@link PlayerControllerMP}, {@link EntityPlayerSP}, or
 * {@code mc.objectMouseOver} lives here, so the controllers stay pure and headless.
 * All methods run on the game thread (the controllers are ticked by
 * {@link ActTickLoop}); this class marshals nothing itself.
 *
 * <p>{@link ActActuator.Face} is mapped to/from {@link EnumFacing} internally, so
 * the controller layer never sees a vanilla enum. Every {@code PlayerControllerMP}
 * path is live-only by nature — there is no headless surrogate for the real
 * network/interaction side effects — which is exactly why the {@code *LiveIT}
 * shells exist to exercise them against a running client.
 */
public final class LivePlayerActuator implements ActActuator {

    private final GameAccess game;

    public LivePlayerActuator(GameAccess game) {
        this.game = game;
    }

    // ===== reads =====

    @Override
    public boolean inWorld() {
        return game.isInWorld();
    }

    @Override
    public double[] eyePos() {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return null;
        }
        Vec3 eye = p.getPositionEyes(1.0F);
        return new double[] {eye.xCoord, eye.yCoord, eye.zCoord};
    }

    @Override
    public float yaw() {
        EntityPlayerSP p = game.player();
        return p == null ? 0f : p.rotationYaw;
    }

    @Override
    public float pitch() {
        EntityPlayerSP p = game.player();
        return p == null ? 0f : p.rotationPitch;
    }

    @Override
    public double reachDistance() {
        PlayerControllerMP pc = playerController();
        return pc == null ? 4.5 : pc.getBlockReachDistance();
    }

    @Override
    public Graded<Target> mouseOver() {
        Minecraft mc = game.mc();
        if (mc == null) {
            // No client, so no ray was traced. This is NOT the same answer as the MISS below, and
            // before the grade existed both of them were the same Target.miss() value.
            return ActActuator.noRay();
        }
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
            return ActActuator.tracedLastFrame(Target.miss());
        }
        double[] hit = mop.hitVec == null ? null
                : new double[] {mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord};
        double dist = distEye(hit);
        if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            int id = mop.entityHit == null ? -1 : mop.entityHit.getEntityId();
            return ActActuator.tracedLastFrame(Target.entity(id, hit, dist));
        }
        BlockPos bp = mop.getBlockPos();
        Face side = faceOf(mop.sideHit);
        return ActActuator.tracedLastFrame(Target.block(bp.getX(), bp.getY(), bp.getZ(), side, hit, dist));
    }

    @Override
    public boolean blockPresent(int x, int y, int z) {
        WorldClient w = game.world();
        return w != null && !w.isAirBlock(new BlockPos(x, y, z));
    }

    @Override
    public String blockAt(int x, int y, int z) {
        WorldClient w = game.world();
        if (w == null) {
            return null;
        }
        try {
            BlockPos pos = new BlockPos(x, y, z);
            if (w.isAirBlock(pos)) {
                return null;
            }
            var loc = net.minecraft.block.Block.blockRegistry.getNameForObject(
                    w.getBlockState(pos).getBlock());
            if (loc == null) {
                return null;
            }
            // Stripped of the namespace, matching what world_view and find_block already emit, so a
            // name read out of either can be compared with one from here.
            String s = loc.toString();
            int colon = s.indexOf(':');
            return colon >= 0 ? s.substring(colon + 1) : s;
        } catch (Throwable t) {
            // Null rather than a guess: this feeds a completion test, and inventing a name would
            // make an unreadable position look like a block that is still there (or one that
            // vanished, depending on the guess). Absence is the honest answer, and the caller
            // (DigController) treats an unreadable target as "gone" only in combination with having
            // started, exactly as it treats real air.
            return null;
        }
    }

    @Override
    public int heldSlot() {
        EntityPlayerSP p = game.player();
        return p == null ? -1 : p.inventory.currentItem;
    }

    @Override
    public double[] entityEyePos(int id) {
        WorldClient w = game.world();
        if (w == null) {
            return null;
        }
        Entity e = w.getEntityByID(id);
        if (e == null) {
            return null;
        }
        Vec3 eye = e.getPositionEyes(1.0F);
        return new double[] {eye.xCoord, eye.yCoord, eye.zCoord};
    }

    // ===== locomotion state =====

    @Override
    public double[] position() {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return null;
        }
        // getEntityBoundingBox().minY rather than posY: they agree for a standing player, but posY
        // is the eye-height reference in some paths and the box floor is what a path node and a
        // block coordinate both mean. Vanilla's own pathfinder starts from exactly this value.
        return new double[] {p.posX, p.getEntityBoundingBox().minY, p.posZ};
    }

    @Override
    public boolean onGround() {
        EntityPlayerSP p = game.player();
        return p != null && p.onGround;
    }

    @Override
    public boolean collidedHorizontally() {
        EntityPlayerSP p = game.player();
        return p != null && p.isCollidedHorizontally;
    }

    @Override
    public boolean onClimbable() {
        EntityPlayerSP p = game.player();
        // isOnLadder, not a block read: vanilla's own test already floors the bounding box and
        // checks the cell the FEET are in, and re-deriving that here would be a second copy of
        // the rule that decides whether a player is climbing.
        return p != null && p.isOnLadder();
    }

    @Override
    public boolean inWater() {
        EntityPlayerSP p = game.player();
        return p != null && p.isInWater();
    }

    @Override
    public int air() {
        EntityPlayerSP p = game.player();
        // -1 for "no player to ask", never 0. Vanilla's own value is a short from a data watcher
        // (Entity:2229) and 0 means the player is at the drowning threshold, so a reader that got 0
        // back from "there is no player here" would report an emergency that does not exist.
        return p == null ? -1 : p.getAir();
    }
    @Override
    public double fallDistance() {
        EntityPlayerSP p = game.player();
        return p == null ? 0.0D : p.fallDistance;
    }

    @Override
    public boolean blocking() {
        EntityPlayerSP p = game.player();
        // EntityPlayer.isBlocking(), not isUsingItem(): vanilla's own predicate already asks for the
        // use AND for the item's use ACTION to be BLOCK, and the second half is what makes it a
        // block rather than a meal.
        return p != null && p.isBlocking();
    }

    @Override
    public long worldTime() {
        WorldClient w = game.world();
        // 0 rather than a sentinel: this is the value a client world is CONSTRUCTED with
        // (WorldInfo.populateFromWorldSettings assigns no worldTime, so the field keeps its
        // default), and "no world" and "a world at sunrise" are the same instant to a caller
        // asking whether it is safe to act -- so the honest answer for the first is the second.
        // The -1 convention belongs to air(), where 0 is a live reading meaning "drowning".
        return w == null ? 0L : w.getWorldTime();
    }

    @Override
    public boolean isDaytime() {
        WorldClient w = game.world();
        // Daylight.isDaytime(worldTime), NOT World.isDaytime(). That is the whole point of this
        // line and it is worth being explicit about why, because the obvious-looking
        // `return w != null && w.isDaytime();` is the defect this file was changed to remove.
        //
        // World.isDaytime() is `skylightSubtracted < 4` (World.java:866-869), and on a client
        // skylightSubtracted is written exactly once -- by calculateInitialSkylight() in the
        // WorldClient constructor (WorldClient.java:59) -- and never again, because the only
        // clock-path writer is WorldServer.tick (WorldServer.java:197-202) and this is a client.
        // Measured: a client world built at worldTime 0 holds 0 and therefore answers "day" at
        // worldTime 18000, where vanilla's own curve says 11 and the honest answer is "night".
        return w != null && Daylight.isDaytime(w.getWorldTime());
    }


    // ===== rotation =====

    @Override
    public void setRotation(float yaw, float pitch) {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return;
        }
        p.rotationYaw = yaw;
        p.rotationPitch = pitch;
        p.prevRotationYaw = yaw;
        p.prevRotationPitch = pitch;
    }

    @Override
    public void setRotationInterp(float pYaw, float pPitch, float yaw, float pitch) {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return;
        }
        p.prevRotationYaw = pYaw;
        p.prevRotationPitch = pPitch;
        p.rotationYaw = yaw;
        p.rotationPitch = pitch;
    }

    // ===== dig =====

    @Override
    public boolean startDig(int x, int y, int z, Face face) {
        PlayerControllerMP pc = playerController();
        return pc != null && pc.clickBlock(new BlockPos(x, y, z), enumFacing(face));
    }

    @Override
    public boolean pumpDig(int x, int y, int z, Face face) {
        PlayerControllerMP pc = playerController();
        return pc != null && pc.onPlayerDamageBlock(new BlockPos(x, y, z), enumFacing(face));
    }

    @Override
    public void cancelDig() {
        PlayerControllerMP pc = playerController();
        if (pc != null) {
            pc.resetBlockRemoving();
        }
    }

    @Override
    public boolean instantBreak(int x, int y, int z, Face face) {
        PlayerControllerMP pc = playerController();
        return pc != null && pc.onPlayerDestroyBlock(new BlockPos(x, y, z), enumFacing(face));
    }

    // ===== use / place / attack =====

    @Override
    public boolean rightClickBlock(int x, int y, int z, Face face, double hx, double hy, double hz) {
        PlayerControllerMP pc = playerController();
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();
        if (pc == null || p == null || w == null) {
            return false;
        }
        ItemStack held = p.inventory.getCurrentItem();
        Vec3 hit = new Vec3(x + hx, y + hy, z + hz);
        return pc.onPlayerRightClick(p, w, held, new BlockPos(x, y, z), enumFacing(face), hit);
    }

    @Override
    public boolean useItemInAir() {
        PlayerControllerMP pc = playerController();
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();
        if (pc == null || p == null || w == null) {
            return false;
        }
        ItemStack held = p.inventory.getCurrentItem();
        if (held == null) {
            return false;
        }
        // sendUseItem answers "did the stack change", which is false for every item with a use
        // DURATION even when the use started -- see the seam contract on ActActuator.useItemInAir.
        // So take either signal: the stack changed (instant use, e.g. a thrown snowball), or the
        // player is now using an item (sustained use, e.g. eating). Measured live with bread:
        // sendUseItem false, getItemInUseCount 32.
        boolean stackChanged = pc.sendUseItem(p, w, held);
        return stackChanged || p.isUsingItem();
    }

    @Override
    public boolean attackEntity(int id) {
        PlayerControllerMP pc = playerController();
        EntityPlayerSP p = game.player();
        WorldClient w = game.world();
        if (pc == null || p == null || w == null) {
            return false;
        }
        Entity target = w.getEntityByID(id);
        if (target == null) {
            return false;
        }
        pc.attackEntity(p, target);
        return true;
    }

    @Override
    public void swing() {
        EntityPlayerSP p = game.player();
        if (p != null) {
            p.swingItem();
        }
    }

    // ===== sustained use =====
    //
    // The one place in the kernel that reaches into net.minecraft.client.settings, and deliberately
    // so: core imported nothing from that package before the hold channel, and scattering key writes
    // would put live-client contact in several files at once. This class already owns that contact.
    //
    // No reflection and no compat patch: KeyBinding.setKeyBindState is public static
    // (KeyBinding.java:37) and looks the binding up by keyCode in a static hash, writing its private
    // 'pressed' field. Note what it does NOT touch: pressTime. So an assertion here never makes
    // isPressed() true, and vanilla's edge-triggered loops (Minecraft.java:2130/2147) stay quiet --
    // only the level-triggered reads (2120's isKeyDown, 2158's isKeyDown) see our hold, which is
    // exactly the pair the hold channel needs.

    @Override
    public boolean holdUseKey() {
        return setUseKey(true);
    }

    @Override
    public boolean releaseUseKey() {
        return setUseKey(false);
    }

    @Override
    public boolean useKeyHeld() {
        KeyBinding kb = useKeyBinding();
        return kb != null && kb.isKeyDown();
    }

    @Override
    public boolean isUsingItem() {
        EntityPlayerSP p = game.player();
        return p != null && p.isUsingItem();
    }

    @Override
    public int itemInUseCount() {
        EntityPlayerSP p = game.player();
        return p == null ? 0 : p.getItemInUseCount();
    }

    @Override
    public int maxItemUseDuration() {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return 0;
        }
        // The stack currently BEING used when there is one, falling back to what is in hand.
        // getItemInUse() is what vanilla itself passes to onPlayerStoppedUsing, so during a draw it
        // is the authority; between uses it is null and the held stack is the only thing to ask.
        net.minecraft.item.ItemStack inUse = p.getItemInUse();
        net.minecraft.item.ItemStack stack = inUse != null ? inUse : p.getHeldItem();
        return stack == null ? 0 : stack.getMaxItemUseDuration();
    }

    /**
     * Write vanilla's use-key state and CONFIRM by reading it back.
     *
     * <p>The read-back is the point. {@code setKeyBindState} is a void that silently does nothing
     * when the keyCode is absent from its static hash, and that is a state the game can genuinely be
     * in: {@code KeyBinding.setKeyCode} updates the binding's field while the hash still holds the
     * old code until {@code resetKeyBindingArrayAndHash} runs, so a rebind mid-session can leave the
     * lookup pointing elsewhere. Without the read-back a hold would report success and hold nothing.
     *
     * <p>{@code getKeyCode()} is read live rather than hardcoding the {@code -99} default
     * ({@code GameSettings.java:135}) for the same reason: a user who rebound "use" would otherwise
     * have us pressing a key that is no longer theirs.
     */
    private boolean setUseKey(boolean pressed) {
        KeyBinding kb = useKeyBinding();
        if (kb == null) {
            return false;
        }
        KeyBinding.setKeyBindState(kb.getKeyCode(), pressed);
        return kb.isKeyDown() == pressed;
    }

    private KeyBinding useKeyBinding() {
        Minecraft mc = game.mc();
        if (mc == null || mc.gameSettings == null) {
            return null;
        }
        return mc.gameSettings.keyBindUseItem;
    }

    // ===== hotbar =====

    @Override
    public void setHeldSlot(int slot) {
        EntityPlayerSP p = game.player();
        if (p != null && slot >= 0 && slot <= 8) {
            p.inventory.currentItem = slot;
        }
    }

    // ===== inventory / drop =====

    @Override
    public int slotStackSize(int playerSlot) {
        EntityPlayerSP p = game.player();
        if (p == null || playerSlot < 0 || playerSlot >= p.inventory.mainInventory.length) {
            return 0;
        }
        ItemStack s = p.inventory.mainInventory[playerSlot];
        return s == null ? 0 : s.stackSize;
    }

    /**
     * {@code PlayerControllerMP.windowClick} with vanilla's own drop-from-a-slot arguments.
     *
     * <p>Three things are transcribed rather than invented, and each is a line of frozen client
     * source:
     *
     * <ol>
     *   <li><b>mode 4, button 1.</b> {@code Container.slotClick:446-456} gates on
     *       {@code mode == 4 && inventoryplayer.getItemStack() == null && slotId >= 0} and then
     *       decrements by {@code clickedButton == 0 ? 1 : slot3.getStack().stackSize}. Button 1 is
     *       the whole stack, which is what a player means by throwing a pack full away, and
     *       {@link net.marcloud.mcp.core.drivers.world.SlotClickMode#DROP_SLOT} names the mode
     *       rather than leaving a 4 in the call.
     *   <li><b>The slot number.</b> {@code windowClick} takes a CONTAINER slot, and the player's
     *       own container does not number its stacks like {@code mainInventory}:
     *       {@code ContainerPlayer:26-68} adds the result at 0, the 2x2 at 1..4, four ARMOUR
     *       slots at 5..8, main inventory 9..35 at 9..35, and the HOTBAR last at 36..44. So
     *       {@code mainInventory[i]} is container slot {@code i} for i >= 9 and {@code i + 36}
     *       below that -- the same inversion {@code SimCraftWindow} documents from the other side.
     *       Sending {@code mainInventory} indices straight through would throw whatever happens
     *       to be in armour slot 5.
     *   <li><b>{@code windowClick}, not a bare packet.</b> {@code PlayerControllerMP:534-539}
     *       applies the click to the client's container and THEN queues the C0E, so the local
     *       state a read-back inspects is the same one the server is about to be told about. A
     *       hand-built packet would leave the client's own container untouched until the server's
     *       reply, and every read-back would be measuring the round trip rather than the drop.
     * </ol>
     *
     * <p>The spectator gate is vanilla's own, and taken from the same place the client player
     * itself takes it: {@code EntityPlayerSP:824-825} asks
     * {@code playerController.isSpectatorMode()} rather than {@code thePlayer.isSpectator()},
     * because {@code AbstractClientPlayer.isSpectator} (:33-36) walks the net handler's
     * player-info map and this runs with no connection to answer it. A spectator throwing a
     * stack into a world that will not give it back is a silent no-op worth refusing outright.
     */
    @Override
    public boolean dropStack(int playerSlot) {
        PlayerControllerMP pc = playerController();
        EntityPlayerSP p = game.player();
        if (pc == null || p == null || pc.isSpectatorMode()) {
            return false;
        }
        if (playerSlot < 0 || playerSlot >= p.inventory.mainInventory.length) {
            return false;
        }
        ItemStack stack = p.inventory.mainInventory[playerSlot];
        if (stack == null || stack.stackSize <= 0) {
            return false;
        }
        pc.windowClick(p.openContainer.windowId, playerContainerSlot(playerSlot), 1,
                net.marcloud.mcp.core.drivers.world.SlotClickMode.DROP_SLOT, p);
        return true;
    }

    /**
     * Which {@link ContainerPlayer} slot holds a given {@code mainInventory} index.
     *
     * <p>Container slots run main inventory 9..35 and THEN the hotbar, so the hotbar -- which is
     * {@code mainInventory[0..8]} -- sits 36 higher than its own index.
     */
    static int playerContainerSlot(int playerSlot) {
        return playerSlot < 9 ? playerSlot + 36 : playerSlot;
    }

    // ===== internals =====

    private PlayerControllerMP playerController() {
        Minecraft mc = game.mc();
        return mc == null ? null : mc.playerController;
    }

    private double distEye(double[] hit) {
        if (hit == null) {
            return 0.0;
        }
        double[] eye = eyePos();
        if (eye == null) {
            return 0.0;
        }
        double dx = hit[0] - eye[0];
        double dy = hit[1] - eye[1];
        double dz = hit[2] - eye[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Map an {@link ActActuator.Face} to the vanilla {@link EnumFacing} by index. */
    static EnumFacing enumFacing(Face face) {
        return EnumFacing.getFront(face == null ? 0 : face.index());
    }

    /** Map a vanilla {@link EnumFacing} back to an {@link ActActuator.Face}. */
    static Face faceOf(EnumFacing facing) {
        return facing == null ? Face.DOWN : Face.fromIndex(facing.getIndex());
    }
}
