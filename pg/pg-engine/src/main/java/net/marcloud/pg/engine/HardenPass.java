package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;

/**
 * A single hardening transformation over one class's bytecode. The extension
 * point of the whole engine: each hardening technique (string obfuscation,
 * control-flow flattening, ISA virtualization, and — later — a native-stub
 * emitter) is one {@code HardenPass}. Adding a technique means adding a pass and
 * registering it against a {@link Guarded.Level}; nothing in {@code pg-api} or in
 * business code changes.
 *
 * <p>Contract:
 * <ul>
 *   <li>A pass takes raw class bytes and returns transformed class bytes, or
 *       returns the input unchanged if it has nothing to do for this class.</li>
 *   <li>A pass MUST NOT throw for normal "not applicable" cases — it returns the
 *       input. It MAY throw on a genuine internal error; the {@link HardenEngine}
 *       treats a throw as "this pass failed, keep the pre-pass bytes" (fail-safe),
 *       so a buggy pass can never make the build emit unloadable bytecode.</li>
 *   <li>A pass must be deterministic given the same input and
 *       {@link HardenContext#seed()}, so builds are reproducible when the seed is
 *       pinned.</li>
 * </ul>
 */
public interface HardenPass {

    /** Stable id for logging and pass-selection (e.g. {@code "string-constant"}). */
    String id();

    /**
     * The minimum {@link Guarded.Level} at which this pass runs. The engine applies
     * a pass to a class only when the class's level is at least this. Ordering the
     * levels this way makes each level a superset of the one below.
     *
     * @return the lowest level that activates this pass
     */
    Guarded.Level minLevel();

    /**
     * Transform {@code classBytes}. Return the transformed bytes, or {@code
     * classBytes} unchanged if this pass does not apply. Never return {@code null}.
     *
     * @param classBytes the current class bytecode (already reflects earlier passes)
     * @param ctx        seed, level, and logging for this class
     * @return transformed (or unchanged) class bytecode, never null
     */
    byte[] apply(byte[] classBytes, HardenContext ctx);
}
