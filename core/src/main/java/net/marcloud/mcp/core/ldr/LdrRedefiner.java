package net.marcloud.mcp.core.ldr;

import net.marcloud.mcp.core.boot.CoreAgent;
import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

import net.marcloud.mcp.core.boot.AgentAccess;
import net.marcloud.mcp.core.se.SeProtectedObjects;

/**
 * Redefines an already-loaded class with new bytecode via {@link Instrumentation}.
 *
 * <p>Capability tiers (verified on this machine):
 * <ul>
 *   <li><b>Standard HotSpot / JBR without the flag:</b> may change method bodies
 *       only. Adding/removing fields or methods, or changing signatures, throws
 *       {@link UnsupportedOperationException}.</li>
 *   <li><b>JBR 25 + {@code -XX:+AllowEnhancedClassRedefinition} (DCEVM):</b> may
 *       additionally add/remove fields and methods and change signatures. Cannot
 *       change inheritance (no runtime can).</li>
 * </ul>
 *
 * <p>Semantics (JVMTI): a redefine affects <i>new</i> invocations; frames already
 * on the stack keep running the old bytecode. Static state and existing instances
 * are preserved (initializers do NOT re-run). Newly added fields read as their
 * default value on pre-existing instances.
 *
 * <p>Requires the {@link CoreAgent} to have been loaded (preferably via
 * {@code -javaagent}); otherwise {@link #isAvailable()} is false and calls throw
 * with a clear message.
 */
public final class LdrRedefiner {

    /** True if Instrumentation is present and supports redefinition. */
    public boolean isAvailable() {
        Instrumentation inst = AgentAccess.instrumentation();
        return inst != null && inst.isRedefineClassesSupported();
    }

    /**
     * Replace {@code target}'s definition with {@code newBytecode}.
     *
     * @throws IllegalStateException          if the agent/Instrumentation is missing
     * @throws UnsupportedOperationException  if the change is unsupported by this
     *                                        runtime (e.g. adding a field without DCEVM)
     * @throws UnmodifiableClassException     if the class cannot be modified at all
     */
    public void redefine(Class<?> target, byte[] newBytecode) throws UnmodifiableClassException {
        // Guard first: never let a redefine rewrite the guard's own machinery
        // (SeClearancePolicy/Ring/IoManager/CoreAgent/...). This is the
        // enforceable choke point for the redefine_class tool path.
        if (SeProtectedObjects.isProtected(target.getName())) {
            throw new IllegalStateException(
                    "refusing to redefine protected Core class " + target.getName()
                    + " (privilege-model self-modification is not allowed)");
        }
        Instrumentation inst = AgentAccess.instrumentation();
        if (inst == null) {
            throw new IllegalStateException(
                    "Instrumentation unavailable: start the JVM with "
                    + "-javaagent:core-agent.jar (or enable self-attach). "
                    + "Cannot redefine " + target.getName() + ".");
        }
        if (!inst.isRedefineClassesSupported()) {
            throw new IllegalStateException(
                    "This JVM reports redefineClasses is not supported.");
        }
        try {
            inst.redefineClasses(new ClassDefinition(target, newBytecode));
        } catch (ClassNotFoundException e) {
            // Cannot happen: target is an already-loaded Class instance, not a
            // name lookup. Rethrow as unchecked to keep the caller signature clean.
            throw new IllegalStateException("redefine target not found: " + target.getName(), e);
        }
    }

    /**
     * True if this runtime allows structural changes (add field/method), i.e. it
     * is DCEVM-enhanced. Probed lazily by callers that need to warn before
     * attempting a structural redefine; here we expose the coarse signal.
     */
    public boolean supportsStructuralChanges() {
        Instrumentation inst = AgentAccess.instrumentation();
        // No standard API reports DCEVM directly; the reliable signal is that a
        // structural redefine does not throw. Callers should attempt and catch.
        return inst != null && inst.isRedefineClassesSupported();
    }
}
