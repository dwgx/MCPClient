package net.marcloud.mcp.core.eval;

/**
 * One moving body's position, velocity and {@code Entity.moveEntity} collision.
 *
 * <p><b>Why this was lifted out of {@link SimWorld} rather than written twice.</b> The player and
 * the mobs in {@link SimMob} have to agree about what a wall is, what a floor is and how high a
 * step can be stepped. Two copies of that arithmetic would be free to drift, and the first drift
 * would show up as a combat task whose verdict depended on whether the mob's body had been
 * updated since the player's -- which is not a fact about Minecraft. So there is exactly one
 * implementation here and {@link SimWorld}'s player path calls it.
 *
 * <p><b>It is vanilla's, not a simplification.</b> Every method is the code that was in
 * {@code SimWorld}, moved verbatim: the axis-separated resolution of {@code Entity.moveEntity}, the
 * {@code onGround = isCollidedVertically && d4 < 0} rule that is NOT inside an {@code if}, the
 * full-height step-up retry, the 0.05 sneak edge guard with its three cases, and the exact
 * landing-surface and ceiling resolvers. The line numbers and the reasons each of those matter are
 * on the methods; see {@code SimWorld.KNOWN_GAPS} for what the resulting world still is not.
 *
 * <p><b>What differs from the player path</b> is only the two knobs vanilla itself parameterises:
 * {@code this.width} / {@code this.height} (a spider is not the size of a zombie -- well, it is,
 * but a cave spider is not) and {@code isSneaking()}, which is {@code false} for every mob because
 * {@code Entity.moveEntity:626} gates the edge guard on
 * {@code onGround && isSneaking() && this instanceof EntityPlayer}.
 */
final class SimBody {

    /** The world's own answers, so nothing here re-decides what a block is. */
    interface Solid {
        boolean solid(int bx, int by, int bz);

        /**
         * {@code Block.slipperiness} at a cell, and {@code 1.0} for an empty one. That one is what
         * makes {@code slipperiness * 0.91} come out as plain air friction when there is no block,
         * which is what the inlined lookup this replaced left standing.
         */
        double slipperiness(int bx, int by, int bz);
    }

    final double width;
    final double height;

    double x;
    double y;
    double z;
    double motionX;
    double motionY;
    double motionZ;
    boolean onGround = true;
    boolean collidedHorizontally;
    /** {@code Entity.fallDistance}, maintained exactly as {@code updateFallState:1034-1055} does. */
    double fallDistance;
    /**
     * The {@code fallDistance} the body was carrying on the tick it landed, which is the argument
     * vanilla hands to {@code fall()} at {@code Entity.updateFallState:1046}.
     *
     * <p>Recorded rather than recomputed because the value is destroyed on the very line that
     * captures it: vanilla zeroes {@code fallDistance} in the same branch that calls {@code fall},
     * and so does the transcription below. A caller that wanted the landing distance afterwards
     * would find zero, which is exactly the number a fall that happened and a fall that did not
     * both report. Zero here means "this body did not land from a fall".
     */
    double landedFrom;

    /**
     * {@code EntityPlayer.isSneaking()} read DURING this tick's move, which is when the edge guard
     * reads it. False for every mob, and that is the game's rule rather than a shortcut: the guard
     * is {@code Entity.moveEntity:626}'s and it names {@code instanceof EntityPlayer}.
     */
    boolean sneakingThisTick;

    SimBody(double width, double height, double x, double y, double z) {
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The cell the feet are in, the way {@code EntityLivingBase.getBlockPos} reads one. */
    int feetX() {
        return (int) Math.floor(x);
    }

    int feetY() {
        return (int) Math.floor(y);
    }

    int feetZ() {
        return (int) Math.floor(z);
    }

    /** The eye, i.e. {@code posY + getEyeHeight()}. */
    double eyeY() {
        return y + SimWorld.EYE_HEIGHT;
    }

    // ===== collision =====

    boolean collides(Solid grid, double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ) {
        int x0 = (int) Math.floor(minX);
        int x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY);
        int y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ);
        int z1 = (int) Math.floor(maxZ);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (grid.solid(bx, by, bz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean bodyFits(Solid grid, double px, double py, double pz) {
        double h = width / 2.0D;
        return !collides(grid, px - h, py, pz - h, px + h, py + height, pz + h);
    }

    /**
     * {@code Entity.moveEntity} in the order vanilla resolves it.
     *
     * <p><b>{@code onGround} follows {@code Entity.moveEntity:820},
     * {@code this.onGround = this.isCollidedVertically && d4 < 0.0D}, which is NOT inside an
     * {@code if}.</b> Taking the assignment at 605-607 to be conditional carries the previous
     * tick's value forward whenever the vertical move was free -- right for a body standing still
     * and wrong for a body walking off a ledge, where nothing collides so vanilla clears
     * {@code onGround} and a stale {@code true} reports "standing" in mid-fall. That is the state
     * {@code CritWindow} and {@code NavController} both read to decide a fall is happening.
     *
     * <p>Resolution is exact for full cubes, which is all {@link SimWorld#solid} admits: the
     * surface the body lands on is the top of the highest solid cell in its own footprint, not a
     * swept guess.
     */
    void moveWithCollision(Solid grid, double mx, double my, double mz) {
        boolean wasOnGround = onGround;
        collidedHorizontally = false;

        // --- Y ---
        double ny = y + my;
        if (my != 0.0D && !bodyFits(grid, x, ny, z)) {
            double h = width / 2.0D;
            int x0 = (int) Math.floor(x - h);
            int x1 = (int) Math.floor(x + h);
            int z0 = (int) Math.floor(z - h);
            int z1 = (int) Math.floor(z + h);
            if (my < 0.0D) {
                // The landing surface: the highest solid top the body can descend onto, read from
                // the blocks alone. Seeding it with the CURRENT y makes the resolver unable to
                // correct a stale height: a body that ended a tick at y=64.0147 could never come
                // back down to the 64.0 its floor actually has.
                double top = Double.NEGATIVE_INFINITY;
                for (int bx = x0; bx <= x1; bx++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        for (int by = (int) Math.floor(y) - 1; by >= (int) Math.floor(ny); by--) {
                            if (grid.solid(bx, by, bz)) {
                                top = Math.max(top, by + 1.0D);
                            }
                        }
                    }
                }
                if (top != Double.NEGATIVE_INFINITY) {
                    ny = Math.max(ny, top);
                }
            } else {
                // The ceiling: the lowest solid bottom at or above the current head.
                double bottom = Double.POSITIVE_INFINITY;
                int y0 = (int) Math.floor(y + height);
                int y1 = (int) Math.floor(ny + height);
                for (int bx = x0; bx <= x1; bx++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        for (int by = y0; by <= y1; by++) {
                            if (grid.solid(bx, by, bz)) {
                                bottom = Math.min(bottom, by - height);
                            }
                        }
                    }
                }
                if (bottom != Double.POSITIVE_INFINITY) {
                    ny = Math.min(ny, bottom);
                }
            }
            onGround = my < 0.0D;
            motionY = 0.0D;
        } else if (my < 0.0D) {
            // Entity.moveEntity:820, unconditional. Downward motion with nothing under it is a fall.
            onGround = false;
        }
        // Entity.updateFallState:1034-1055, with the motion actually applied rather than the one
        // asked for: accumulate downward travel, and clear on the tick the body lands.
        if (onGround) {
            landedFrom = fallDistance;
            fallDistance = 0.0D;
        } else if ((ny - y) < 0.0D) {
            fallDistance -= (ny - y);
        }
        y = ny;

        // Entity.moveEntity:626-662. `flag = onGround && isSneaking() && instanceof EntityPlayer`,
        // and while flag holds each horizontal axis is walked back in 0.05 steps until the body
        // would be standing over nothing. That is the whole of vanilla's sneak-at-an-edge rule.
        double gx = wasOnGround && sneakingThisTick ? edgeGuard(grid, mx, true) : mx;
        double gz = wasOnGround && sneakingThisTick ? edgeGuard(grid, mz, false) : mz;

        // --- X, then Z, each with the step-up retry (Entity.moveEntity:616-700) ---
        if (!slide(grid, gx, true, wasOnGround)) {
            collidedHorizontally = true;
        }
        if (!slide(grid, gz, false, wasOnGround)) {
            collidedHorizontally = true;
        }
    }

    /**
     * Vanilla's edge shrink for one axis: {@code Entity.moveEntity:632-661}, the 0.05 loop and its
     * three cases verbatim.
     *
     * <p>It shrinks by 0.05 rather than snapping to the brink, which is why a sneaking player eases
     * to a stop over several ticks instead of stopping dead.
     */
    private double edgeGuard(Solid grid, double delta, boolean alongX) {
        final double step = 0.05D;
        double v = delta;
        while (v != 0.0D && floorClearBelow(grid, alongX ? x + v : x, alongX ? z : z + v)) {
            if (v < step && v >= -step) {
                v = 0.0D;
            } else if (v > 0.0D) {
                v -= step;
            } else {
                v += step;
            }
        }
        return v;
    }

    /** {@code getCollidingBoundingBoxes(this, box.offset(0, -1, 0)).isEmpty()} at an XZ. */
    private boolean floorClearBelow(Solid grid, double px, double pz) {
        double h = width / 2.0D;
        return !collides(grid, px - h, y - 1.0D, pz - h, px + h, y - 1.0D + height, pz + h);
    }

    /**
     * One horizontal axis, with vanilla's step-up.
     *
     * <p>The step is a full {@link SimWorld#STEP_HEIGHT} raise followed by the same move, taken only
     * when the move is otherwise blocked and the raised body is free. A partial raise would let a
     * body stand on a stair's edge in a way vanilla never produces.
     *
     * @return whether any horizontal progress was made
     */
    private boolean slide(Solid grid, double delta, boolean alongX, boolean wasOnGround) {
        if (delta == 0.0D) {
            return true;
        }
        if (bodyFits(grid, alongX ? x + delta : x, y, alongX ? z : z + delta)) {
            if (alongX) {
                x += delta;
            } else {
                z += delta;
            }
            return true;
        }
        if (wasOnGround && bodyFits(grid, x, y + SimWorld.STEP_HEIGHT, z)) {
            double probe = alongX ? resolveX(grid, x, delta, y + SimWorld.STEP_HEIGHT, z)
                    : resolveZ(grid, z, delta, x, y + SimWorld.STEP_HEIGHT);
            if (Math.abs(probe - (alongX ? x : z)) > 1.0E-9) {
                y += SimWorld.STEP_HEIGHT;
                if (alongX) {
                    x = probe;
                } else {
                    z = probe;
                }
                return true;
            }
        }
        // Clamped against a wall at this height: vanilla keeps the body where it is and zeroes the
        // axis, which is what makes `collidedHorizontally` a real jam signal next tick.
        if (alongX) {
            motionX = 0.0D;
        } else {
            motionZ = 0.0D;
        }
        return false;
    }

    private double resolveX(Solid grid, double from, double delta, double atY, double atZ) {
        double p = from + delta;
        double h = width / 2.0D;
        int y0 = (int) Math.floor(atY);
        int y1 = (int) Math.floor(atY + height);
        int z0 = (int) Math.floor(atZ - h);
        int z1 = (int) Math.floor(atZ + h);
        if (delta > 0.0D) {
            int bx = (int) Math.floor(p + h);
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (grid.solid(bx, by, bz)) {
                        p = Math.min(p, bx - h);
                    }
                }
            }
        } else if (delta < 0.0D) {
            int bx = (int) Math.floor(p - h);
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (grid.solid(bx, by, bz)) {
                        p = Math.max(p, bx + 1.0D + h);
                    }
                }
            }
        }
        return bodyFits(grid, p, atY, atZ) ? p : from;
    }

    private double resolveZ(Solid grid, double from, double delta, double atX, double atY) {
        double p = from + delta;
        double h = width / 2.0D;
        int y0 = (int) Math.floor(atY);
        int y1 = (int) Math.floor(atY + height);
        int x0 = (int) Math.floor(atX - h);
        int x1 = (int) Math.floor(atX + h);
        if (delta > 0.0D) {
            int bz = (int) Math.floor(p + h);
            for (int by = y0; by <= y1; by++) {
                for (int bx = x0; bx <= x1; bx++) {
                    if (grid.solid(bx, by, bz)) {
                        p = Math.min(p, bz - h);
                    }
                }
            }
        } else if (delta < 0.0D) {
            int bz = (int) Math.floor(p - h);
            for (int by = y0; by <= y1; by++) {
                for (int bx = x0; bx <= x1; bx++) {
                    if (grid.solid(bx, by, bz)) {
                        p = Math.max(p, bz + 1.0D + h);
                    }
                }
            }
        }
        return bodyFits(grid, atX, atY, p) ? p : from;
    }

    /**
     * {@code EntityLivingBase.moveEntityWithHeading:1602-1682} for a walker on land, with the two
     * lines vanilla puts outside it: the input damp that {@code onUpdateWalk} applies before
     * travelling, and the gravity and damping that follow.
     *
     * @param moveSpeed {@code getAIMoveSpeed()} -- the {@code movementSpeed} attribute for a mob,
     *                  and the player's own attribute value for the player
     */
    void travel(Solid grid, double strafe, double forward, double moveSpeed) {
        // EntityLivingBase:1610-1617 -- friction comes from the block UNDERFOOT, so ice is
        // genuinely slippery here and a "the axes were right but the floor was wrong" bug is
        // visible rather than papered over.
        double friction = SimWorld.FRICTION_AIR;
        if (onGround) {
            friction = grid.slipperiness(feetX(), feetY() - 1, feetZ()) * SimWorld.FRICTION_AIR;
        }
        double accel = SimWorld.ACCEL / (friction * friction * friction);
        double applied = onGround ? moveSpeed * accel : SimWorld.AIR_ACCEL;

        // Entity.moveFlying:1224-1243 -- the yaw rotation, verbatim. The yaw was set before this
        // call by whoever steers this body, which for a mob is its look helper and for a player is
        // the input layer.
        double mag = strafe * strafe + forward * forward;
        if (mag >= 1.0E-4D) {
            double len = Math.sqrt(mag);
            if (len < 1.0D) {
                len = 1.0D;
            }
            double scale = applied / len;
            double s = strafe * scale;
            double f = forward * scale;
            double yawRad = Math.toRadians(yaw);
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);
            motionX += s * cos - f * sin;
            motionZ += f * cos + s * sin;
        }

        // Entity.moveEntity:598-813, axis-separated, with the step-up retry.
        moveWithCollision(grid, motionX, motionY, motionZ);

        // EntityLivingBase:1677-1682.
        motionY -= SimWorld.GRAVITY;
        motionY *= SimWorld.Y_DAMP;
        motionX *= friction;
        motionZ *= friction;
    }

    /**
     * Heading in degrees, the field {@code moveFlying} reads. A player's own {@code SimWorld} yaw
     * is not this one -- {@link SimWorld} keeps its yaw on the class because dozens of callers read
     * it -- so it is copied in and out around {@link #travel}.
     */
    double yaw;
}
