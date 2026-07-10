package net.marcloud.mcp.core.debug;

/** A sink for {@link DebugEvent}s drained from the native callback queue. */
@FunctionalInterface
public interface DebugEventListener {

    void onDebugEvent(DebugEvent e);
}
