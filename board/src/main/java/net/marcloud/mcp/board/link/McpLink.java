package net.marcloud.mcp.board.link;

import net.marcloud.mcp.board.Backplane;

/**
 * The cross-subsystem bridge to mcp-core — reflection only, zero compile-time
 * dependency. Board and mcp-core are peers with zero hard dependency (design doc
 * 06 §1/§7): Board must not {@code import} a single core class. So this class
 * reaches core purely through {@link Class#forName}: present ⇒ use it, absent ⇒
 * return {@code false}/no-op and Board runs standalone.
 *
 * <p>Two questions this bridge answers, both degrading gracefully:
 * <ul>
 *   <li>{@link #isCorePresent()} — is {@code net.marcloud.mcp.core.McpCore} on
 *       the classpath at all?</li>
 *   <li>{@link #isCoreRunning()} — did core actually start (its
 *       {@code CoreBootstrap.core()} handle is non-null)?</li>
 *   <li>{@link #tryStart()} — best-effort ignite core via its bootstrap.</li>
 * </ul>
 *
 * <p>All reflection is caught: a {@link Throwable} from a missing class, a
 * changed signature, or a failing core start must NEVER propagate into Board —
 * the whole point of the peer decoupling is that either side surviving the other
 * being absent or broken.
 *
 * <p>Not part of the frozen skeleton; this is a seam under {@code board.link}.
 */
public final class McpLink {

    /** mcp-core's entry class (looked up reflectively, never imported). */
    public static final String CORE_CLASS = "net.marcloud.mcp.core.McpCore";

    /** mcp-core's bootstrap (the neutral ignition point). */
    public static final String BOOTSTRAP_CLASS = "net.marcloud.mcp.core.boot.CoreBootstrap";

    private McpLink() {
    }

    /**
     * {@code true} if mcp-core's entry class can be resolved on the current
     * classloader — i.e. the core module is bundled alongside board. Does not
     * initialize the class (so merely asking is side-effect free).
     */
    public static boolean isCorePresent() {
        return coreClass() != null;
    }

    /**
     * {@code true} if mcp-core is actually running — resolved by reflecting
     * {@code CoreBootstrap.core()} and checking the returned handle is non-null.
     * {@code false} if core is absent, has not started, or the lookup fails.
     */
    public static boolean isCoreRunning() {
        try {
            Class<?> bootstrap = Class.forName(BOOTSTRAP_CLASS);
            Object core = bootstrap.getMethod("core").invoke(null);
            return core != null;
        } catch (Throwable t) {
            // Absent / changed / failed → treat as "not running". Never leak up.
            return false;
        }
    }

    /**
     * Best-effort start of mcp-core through its bootstrap. Returns {@code true}
     * if core is running after the attempt (already-running counts as success —
     * {@code CoreBootstrap.onGameInitialized()} is idempotent). Returns
     * {@code false} if core is absent or the ignition throws — Board carries on
     * standalone either way.
     *
     * <p>Reflectively invokes {@code CoreBootstrap.onGameInitialized()}, the same
     * neutral entry the launcher hook uses, so Board lights core up exactly the
     * way the agent would.
     */
    public static boolean tryStart() {
        try {
            Class<?> bootstrap = Class.forName(BOOTSTRAP_CLASS);
            bootstrap.getMethod("onGameInitialized").invoke(null);
        } catch (Throwable t) {
            // No core, or ignition failed — degrade to standalone.
            System.err.println("[Board] mcp-core not started (Board continues standalone): " + t);
            return false;
        }
        return isCoreRunning();
    }

    /**
     * A handle to the peer via the {@link Backplane}, if mcp-core registered one
     * under {@code "mcp"} / {@code "mcp.port"}. Returns an opaque {@code Object}
     * (never a core type) or {@code null} when the peer is absent. This is the
     * runtime service-discovery path — complementary to the classpath-reflection
     * checks above.
     */
    public static Object findCorePort() {
        Object port = Backplane.find("mcp.port");
        return port != null ? port : Backplane.find("mcp");
    }

    /**
     * Register Board's outward-facing {@link BoardPort} on the {@link Backplane}
     * so mcp-core (present or arriving later) can discover a live Board by the
     * neutral key {@link BoardPort#KEY}, with zero compile-time coupling. Returns
     * the registered port. Idempotent — re-registering simply replaces.
     */
    public static BoardPort publishBoardPort() {
        BoardPort port = new BoardPort();
        Backplane.register(BoardPort.KEY, port);
        return port;
    }

    /** Resolve mcp-core's entry class without initializing it, or {@code null}. */
    private static Class<?> coreClass() {
        try {
            return Class.forName(CORE_CLASS, false, McpLink.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }
}
