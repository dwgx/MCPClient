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

    // ---- recorded calls ----
    final List<String> calls = new ArrayList<>();
    int startDigCalls;
    int pumpDigCalls;
    int cancelDigCalls;
    int swingCalls;
    int attackCalls;
    int rightClickCalls;
    int useInAirCalls;
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
        return useInAirResult;
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
