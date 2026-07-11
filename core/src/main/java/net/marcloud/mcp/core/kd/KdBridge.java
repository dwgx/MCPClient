package net.marcloud.mcp.core.kd;

import java.nio.file.Path;

/**
 * The single Java↔native choke point for the C6 CONTROL-EXEC layer: the JVMTI
 * debugger ({@code core-jvmti.dll}). Exposes suspend/pop-frame/force-return/
 * breakpoint/single-step/local-variable/field-watch as static methods that
 * delegate to native functions the DLL binds via {@code RegisterNatives} in its
 * {@code JNI_OnLoad}.
 *
 * <p><b>Graceful fallback is the default state</b> (the DLL is only built once
 * MSVC is installed). The static initializer tries to load the library and probe
 * {@link #nAgentReady()}; ANY failure (missing DLL, missing export, agent not
 * loaded via {@code -agentpath}, capabilities absent) resolves to
 * {@link #isAvailable()}{@code == false} with a fixed reason — an
 * {@link UnsatisfiedLinkError} is caught and never escapes. Every public wrapper
 * calls {@link #ensure()} first, so no native symbol is dereferenced when
 * unavailable. The {@code debug_*} MCP tools therefore stay honest, not dead:
 * they register, are callable, and report {@code isError} until the agent is
 * present.
 *
 * <p><b>This class is a {@link net.marcloud.mcp.core.se.SeProtectedObjects}
 * entry</b> — it must not be redefined/hooked, or the debugger gate could be
 * neutralized from inside.
 *
 * <p><b>Honest capability limits</b> (only functions verified present in the JBR
 * 25 {@code jvmti.h} are exposed): local-variable WRITE is int-slot only
 * ({@code SetLocalInt}); force-early-return is void/int/object; field watch is
 * MODIFICATION only. Anything else returns {@code JVMTI_ERROR_NOT_AVAILABLE}
 * cleanly rather than pretending.
 */
public final class KdBridge {

    private KdBridge() {
    }

    private static final String DEFAULT_MSG =
            "native debugger not loaded — launch with -agentpath:core-jvmti.dll";

    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;

    static {
        boolean ok = false;
        String why = DEFAULT_MSG;
        try {
            String p = System.getProperty("mcp.core.jvmtiLib"); // same file as -agentpath
            if (p != null && !p.isBlank()) {
                System.load(Path.of(p).toAbsolutePath().toString());
            } else {
                System.loadLibrary("core-jvmti"); // else java.library.path
            }
            ok = nAgentReady(); // native probe: Agent_OnLoad ran AND AddCapabilities succeeded
            if (!ok) {
                why = DEFAULT_MSG + " (library bound but JVMTI onload caps absent — "
                        + "dynamic attach cannot gain them)";
            }
        } catch (Throwable t) {
            // UnsatisfiedLinkError / SecurityException / anything — never escapes.
            ok = false;
            why = DEFAULT_MSG + " (" + t.getClass().getSimpleName() + ")";
        }
        AVAILABLE = ok;
        UNAVAILABLE_REASON = ok ? null : why;
    }

    /** True only when the DLL is loaded and its onload JVMTI capabilities are live. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Human reason the debugger is unavailable (null when available). */
    public static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    private static void ensure() {
        if (!AVAILABLE) {
            throw new DebuggerUnavailableException(UNAVAILABLE_REASON);
        }
    }

    // ---- public wrappers (each: ensure(); native call; JvmtiError.check) ----

    public static void suspendThread(Thread t) {
        ensure();
        JvmtiError.check(nSuspendThread(t));
    }

    public static void resumeThread(Thread t) {
        ensure();
        JvmtiError.check(nResumeThread(t));
    }

    /** Pop the top frame of {@code t} (t must be suspended). */
    public static void popFrame(Thread t) {
        ensure();
        JvmtiError.check(nPopFrame(t));
    }

    public static void forceReturnVoid(Thread t) {
        ensure();
        JvmtiError.check(nForceEarlyReturnVoid(t));
    }

    public static void forceReturnInt(Thread t, int v) {
        ensure();
        JvmtiError.check(nForceEarlyReturnInt(t, v));
    }

    public static void forceReturnObject(Thread t, Object v) {
        ensure();
        JvmtiError.check(nForceEarlyReturnObject(t, v));
    }

    public static void setBreakpoint(Class<?> c, String method, String sig, long location) {
        ensure();
        JvmtiError.check(nSetBreakpoint(c, method, sig, location));
    }

    public static void clearBreakpoint(Class<?> c, String method, String sig, long location) {
        ensure();
        JvmtiError.check(nClearBreakpoint(c, method, sig, location));
    }

    public static void setSingleStep(Thread t, boolean enabled) {
        ensure();
        JvmtiError.check(nSetSingleStep(t, enabled));
    }

    /** Read an object local at {@code slot} in frame {@code depth} of {@code t}. */
    public static Object readLocalObject(Thread t, int depth, int slot) {
        ensure();
        Object[] out = new Object[1];
        JvmtiError.check(nGetLocalObject(t, depth, slot, out));
        return out[0];
    }

    public static int readLocalInt(Thread t, int depth, int slot) {
        ensure();
        int[] out = new int[1];
        JvmtiError.check(nGetLocalInt(t, depth, slot, out));
        return out[0];
    }

    /** Write an int local (the only verified local-write path). */
    public static void writeLocalInt(Thread t, int depth, int slot, int value) {
        ensure();
        JvmtiError.check(nSetLocalInt(t, depth, slot, value));
    }

    public static void watchFieldModification(Class<?> c, String field, String sig) {
        ensure();
        JvmtiError.check(nSetFieldModificationWatch(c, field, sig));
    }

    public static void unwatchFieldModification(Class<?> c, String field, String sig) {
        ensure();
        JvmtiError.check(nClearFieldModificationWatch(c, field, sig));
    }

    /**
     * Inbound native → Java event sink. Called from the JVMTI callback thread via
     * the cached {@code jmethodID}; MUST be cheap. Offers to the drop-oldest
     * queue and returns — no JVMTI re-entry.
     */
    static void onDebugEvent(int kind, Thread thread, String location, long numeric) {
        DebugEventQueue.INSTANCE.offer(DebugEvent.of(kind, thread, location, numeric));
    }

    // ---- native declarations (bound by the DLL's JNI_OnLoad RegisterNatives) ----

    private static native boolean nAgentReady();

    private static native int nSuspendThread(Thread t);

    private static native int nResumeThread(Thread t);

    private static native int nPopFrame(Thread t);

    private static native int nForceEarlyReturnVoid(Thread t);

    private static native int nForceEarlyReturnInt(Thread t, int v);

    private static native int nForceEarlyReturnObject(Thread t, Object v);

    private static native int nSetBreakpoint(Class<?> c, String method, String sig, long location);

    private static native int nClearBreakpoint(Class<?> c, String method, String sig, long location);

    private static native int nSetSingleStep(Thread t, boolean enabled);

    private static native int nGetLocalObject(Thread t, int depth, int slot, Object[] out);

    private static native int nGetLocalInt(Thread t, int depth, int slot, int[] out);

    private static native int nSetLocalInt(Thread t, int depth, int slot, int value);

    private static native int nSetFieldModificationWatch(Class<?> c, String field, String sig);

    private static native int nClearFieldModificationWatch(Class<?> c, String field, String sig);
}
