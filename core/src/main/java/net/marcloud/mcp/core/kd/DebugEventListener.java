package net.marcloud.mcp.core.kd;

/** A sink for {@link DebugEvent}s drained from the native callback queue. */
@FunctionalInterface
public interface DebugEventListener {

    void onDebugEvent(DebugEvent e);
}
