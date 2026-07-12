package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;

import java.util.function.Consumer;

/**
 * Per-class context handed to every {@link HardenPass}: the class's selected
 * hardening level, a deterministic seed, and a log sink. Immutable.
 *
 * <p>The seed is what makes hardening a moving target while staying reproducible:
 * a pass derives its randomness from {@code seed} (mixed with the class name), so
 * the same (seed, class) always yields the same output — pin the seed for a
 * reproducible build, or let the build generate a fresh seed per build for a
 * per-release moving target.
 */
public final class HardenContext {

    private final String className;
    private final Guarded.Level level;
    private final long seed;
    private final Consumer<String> log;

    public HardenContext(String className, Guarded.Level level, long seed, Consumer<String> log) {
        this.className = className;
        this.level = level;
        this.seed = seed;
        this.log = (log != null) ? log : msg -> { };
    }

    /** Fully-qualified name of the class being hardened (dotted). */
    public String className() {
        return className;
    }

    /** The level selected by the class's {@link Guarded} annotation. */
    public Guarded.Level level() {
        return level;
    }

    /**
     * Deterministic seed for this build. A pass MUST derive any randomness from
     * this (typically {@code seed ^ className.hashCode()}) so its output is
     * reproducible given the same seed.
     */
    public long seed() {
        return seed;
    }

    /** A per-class seed a pass can use directly (seed mixed with the class name). */
    public long classSeed() {
        return seed ^ (className.hashCode() * 0x9E3779B97F4A7C15L);
    }

    /** Emit a diagnostic line (routed to the plugin's Maven log). */
    public void log(String message) {
        log.accept(message);
    }
}
