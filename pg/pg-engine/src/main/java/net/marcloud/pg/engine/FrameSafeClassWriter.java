package net.marcloud.pg.engine;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} whose {@link #getCommonSuperClass} never loads a class.
 *
 * <p>ASM's {@code COMPUTE_FRAMES} needs to merge reference types at control-flow
 * joins (if/else, try/catch, loops that assign different object types to the same
 * slot). The stock {@link ClassWriter#getCommonSuperClass} resolves that merge by
 * {@code Class.forName}-ing both types on the writer's classloader. During
 * hardening that classloader is the plugin realm, which CANNOT see the project
 * classes being transformed — so the lookup throws {@link ClassNotFoundException}
 * (wrapped as {@link TypeNotPresentException}) and the whole transform aborts,
 * making the engine revert and ship the class with its plaintext strings intact.
 *
 * <p>Fix: return {@code java/lang/Object} for every merge. {@code Object} is a
 * valid (if imprecise) common supertype of any two reference types, so the frames
 * ASM computes stay verifiable, and no class is ever loaded. Correctness of the
 * emitted frames is unaffected — the JVM verifier accepts an {@code Object}-typed
 * slot wherever a more specific common supertype would also have been accepted.
 */
public final class FrameSafeClassWriter extends ClassWriter {

    public FrameSafeClassWriter(int flags) {
        super(flags);
    }

    public FrameSafeClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // Never load a type from the (wrong) classloader — Object always merges.
        return "java/lang/Object";
    }
}
