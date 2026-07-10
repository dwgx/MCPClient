package net.marcloud.mcp.core.debug;

import net.marcloud.mcp.core.event.GameEvent;

/**
 * An immutable debugger event surfaced from the native JVMTI callback: a
 * breakpoint hit, a single-step, or a watched field being modified. Extends
 * {@link GameEvent} so it can also ride the shared {@code EventBus} — existing
 * subscribers see debug events with no new plumbing.
 */
public final class DebugEvent extends GameEvent {

    /** Event kinds, matching the {@code kind} int the native callback passes. */
    public enum Kind {
        BREAKPOINT, SINGLE_STEP, FIELD_MODIFICATION, UNKNOWN;

        static Kind of(int k) {
            return switch (k) {
                case 1 -> BREAKPOINT;
                case 2 -> SINGLE_STEP;
                case 3 -> FIELD_MODIFICATION;
                default -> UNKNOWN;
            };
        }
    }

    private final Kind kind;
    private final String threadName;
    private final long threadId;
    private final String location;
    private final long numeric;

    private DebugEvent(Kind kind, String threadName, long threadId, String location, long numeric) {
        this.kind = kind;
        this.threadName = threadName;
        this.threadId = threadId;
        this.location = location;
        this.numeric = numeric;
    }

    /**
     * Build from the native callback's raw args. {@code thread} may be null if the
     * JVMTI thread could not be resolved; {@code location} is a pre-formatted
     * string (e.g. {@code "net/minecraft/client/Minecraft.runTick()V@0"} or
     * {@code "owner#field"}); {@code numeric} is the bytecode index or the new
     * field value depending on kind.
     */
    @SuppressWarnings("deprecation")
    public static DebugEvent of(int kind, Thread thread, String location, long numeric) {
        String name = thread != null ? thread.getName() : "<unknown>";
        long id = thread != null ? thread.getId() : -1L;
        return new DebugEvent(Kind.of(kind), name, id, location, numeric);
    }

    public Kind kind() {
        return kind;
    }

    public String threadName() {
        return threadName;
    }

    public long threadId() {
        return threadId;
    }

    public String location() {
        return location;
    }

    public long numeric() {
        return numeric;
    }

    @Override
    public String toString() {
        return kind + "[" + threadName + "] " + location
                + (kind == Kind.FIELD_MODIFICATION ? " := " + numeric : " @" + numeric);
    }
}
