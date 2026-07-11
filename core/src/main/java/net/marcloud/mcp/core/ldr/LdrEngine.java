package net.marcloud.mcp.core.ldr;

import net.marcloud.mcp.core.mm.MmAccess;

import java.lang.instrument.UnmodifiableClassException;
import java.util.Map;

/**
 * Unified hot-load entry point: compile Java source, then either load it as new
 * classes or redefine existing ones. Both the {@code eval_java} MCP tool and the
 * file-watch deployer route through here so there is one code path.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #loadNew} — compile + define brand-new classes (capability 1).
 *       Always available (plain JDK).</li>
 *   <li>{@link #redefineExisting} — compile + redefine an already-loaded class
 *       (capability 2/3). Needs the agent; structural changes need DCEVM.</li>
 * </ul>
 */
public final class LdrEngine {

    private final InMemoryCompiler compiler;
    private final LdrRedefiner redefiner;
    private final ClassLoader gameLoader;
    /** Notified with the target class after every SUCCESSFUL redefine, so caches
     *  keyed by class layout (e.g. MmAccess VarHandles) can invalidate. A
     *  DCEVM structural redefine can move field offsets; a stale VarHandle would
     *  then write the wrong address. Null until wired. */
    private volatile java.util.function.Consumer<Class<?>> onRedefined;

    public LdrEngine(ClassLoader gameLoader) {
        this.compiler = new InMemoryCompiler();
        this.redefiner = new LdrRedefiner();
        this.gameLoader = gameLoader;
    }

    /** Register a listener invoked with the target class after each successful
     *  redefine (see {@link #onRedefined}). Replaces any previous listener. */
    public void setOnRedefined(java.util.function.Consumer<Class<?>> listener) {
        this.onRedefined = listener;
    }

    /** Result of a load/redefine attempt: the outcome plus compiler diagnostics. */
    public record LoadOutcome(boolean success, String message, Class<?> loadedClass) {
    }

    /**
     * Compile {@code source} and define it as a NEW class named {@code className}.
     * A fresh {@link DynamicClassLoader} is used so re-invoking with the same name
     * yields a distinct class version (old holders keep the old one).
     */
    public LoadOutcome loadNew(String className, String source) {
        CompileResult r = compiler.compile(className, source);
        if (!r.success()) {
            return new LoadOutcome(false, "compile failed:\n" + r.diagnosticsText(), null);
        }
        try {
            DynamicClassLoader loader = new DynamicClassLoader(gameLoader);
            loader.registerAll(r.bytecode());
            Class<?> c = loader.loadClass(className);
            return new LoadOutcome(true, "loaded " + className, c);
        } catch (ClassNotFoundException | LinkageError e) {
            return new LoadOutcome(false, "load failed: " + e, null);
        }
    }

    /**
     * Compile {@code source} (must be the full new version of {@code target}'s
     * class) and redefine the already-loaded {@code target}.
     */
    public LoadOutcome redefineExisting(Class<?> target, String source) {
        if (!redefiner.isAvailable()) {
            return new LoadOutcome(false,
                    "redefine unavailable: start with -javaagent:core-agent.jar", null);
        }
        CompileResult r = compiler.compile(target.getName(), source);
        if (!r.success()) {
            return new LoadOutcome(false, "compile failed:\n" + r.diagnosticsText(), null);
        }
        byte[] bytes = r.bytecode().get(target.getName());
        if (bytes == null) {
            return new LoadOutcome(false,
                    "compiled output did not contain " + target.getName(), null);
        }
        try {
            redefiner.redefine(target, bytes);
            // Invalidate layout-dependent caches (MmAccess VarHandles): a
            // structural redefine may have moved field offsets.
            java.util.function.Consumer<Class<?>> cb = onRedefined;
            if (cb != null) {
                try {
                    cb.accept(target);
                } catch (Throwable ignored) {
                    // a cache-invalidation fault must not fail the redefine
                }
            }
            return new LoadOutcome(true, "redefined " + target.getName(), target);
        } catch (UnsupportedOperationException e) {
            return new LoadOutcome(false,
                    "structural change rejected (need JBR + DCEVM): " + e.getMessage(), null);
        } catch (UnmodifiableClassException | IllegalStateException e) {
            return new LoadOutcome(false, "redefine failed: " + e.getMessage(), null);
        }
    }

    /** Expose the compiler for callers that only need bytecode (e.g. tests). */
    public Map<String, byte[]> compileToBytecode(String className, String source) {
        CompileResult r = compiler.compile(className, source);
        if (!r.success()) {
            throw new IllegalArgumentException("compile failed:\n" + r.diagnosticsText());
        }
        return r.bytecode();
    }

    public boolean redefineAvailable() {
        return redefiner.isAvailable();
    }
}
