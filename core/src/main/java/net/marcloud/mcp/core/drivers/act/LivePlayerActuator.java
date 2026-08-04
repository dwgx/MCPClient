package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.GameAccess;
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
    public Target mouseOver() {
        Minecraft mc = game.mc();
        if (mc == null) {
            return Target.miss();
        }
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
            return Target.miss();
        }
        double[] hit = mop.hitVec == null ? null
                : new double[] {mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord};
        double dist = distEye(hit);
        if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            int id = mop.entityHit == null ? -1 : mop.entityHit.getEntityId();
            return Target.entity(id, hit, dist);
        }
        BlockPos bp = mop.getBlockPos();
        Face side = faceOf(mop.sideHit);
        return Target.block(bp.getX(), bp.getY(), bp.getZ(), side, hit, dist);
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
