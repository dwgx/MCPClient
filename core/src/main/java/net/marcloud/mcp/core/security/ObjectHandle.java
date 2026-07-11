package net.marcloud.mcp.core.security;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A frozen L6 capability over one resolved resource. Minted only by {@link
 * ObjectManager} (package-private ctor). Two invariants give the TOCTOU
 * guarantee:
 *
 * <ol>
 *   <li>The granted {@code frozenMask} is set once at {@code open()} and never
 *       widened — a handle opened READ-only can never later escalate, even if
 *       the subject re-enables a privilege mid-session.</li>
 *   <li>The {@code target} is a resolved-once snapshot — a resource swapped
 *       underneath (player replaced on respawn, Channel replaced on reconnect,
 *       jthread-id reuse) cannot silently redirect a live handle.</li>
 * </ol>
 *
 * <p>{@link AutoCloseable}: closing deregisters from the {@link ObjectManager}
 * and runs resource cleanup exactly once.
 */
public final class ObjectHandle implements AutoCloseable {

    private final long id;
    private final String owner;
    private final ResourceRef ref;
    private final int frozenMask;            // FROZEN at open — never widened
    private final Object target;             // resolved-once snapshot (jthread/Channel/Class/…)
    private final long openedAtNanos;
    private volatile long lastUsedNanos;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Consumer<ObjectHandle> onClose;   // ObjectManager dereg + resource cleanup

    ObjectHandle(long id, String owner, ResourceRef ref, int mask, Object target,
                 long now, Consumer<ObjectHandle> onClose) {
        this.id = id;
        this.owner = owner;
        this.ref = ref;
        this.frozenMask = mask;
        this.target = target;
        this.openedAtNanos = now;
        this.lastUsedNanos = now;
        this.onClose = onClose;
    }

    public long id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public ResourceRef ref() {
        return ref;
    }

    /** The frozen granted rights mask. */
    public int mask() {
        return frozenMask;
    }

    /** The resolved-once resource snapshot. */
    public Object target() {
        return target;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long openedAtNanos() {
        return openedAtNanos;
    }

    /** True if the handle is open and its frozen mask grants every bit in {@code need}. */
    boolean permits(int need) {
        return !closed.get() && AccessRight.subset(frozenMask, need);
    }

    void touch(long now) {
        lastUsedNanos = now;
    }

    long lastUsedNanos() {
        return lastUsedNanos;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.accept(this);
        }
    }
}
