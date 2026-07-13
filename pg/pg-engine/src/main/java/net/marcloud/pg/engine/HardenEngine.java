package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The hardening engine: given a class's bytes, decide whether it is {@link
 * Guarded}, run the passes its level activates, and return hardened bytes — or
 * the ORIGINAL bytes if the class is not guarded or if anything goes wrong.
 *
 * <p><b>Fail-safe is the core contract.</b> Hardening is a build-time convenience;
 * it must never produce a jar that will not load. So every transformed class is
 * re-verified with {@link CheckClassAdapter}, and on any pass exception or
 * verification failure the engine discards the transformation and keeps the
 * last-known-good bytes. The worst case is "this class was not hardened", never
 * "this class is broken".
 *
 * <p>The engine is a general-purpose hardener with no dependency on core/board;
 * it can be lifted into its own repository unchanged.
 */
public final class HardenEngine {

    private final List<HardenPass> passes;
    private final long seed;
    private final Consumer<String> log;

    /** Build an engine with an explicit pass list and seed. */
    public HardenEngine(List<HardenPass> passes, long seed, Consumer<String> log) {
        this.passes = new ArrayList<>(passes);
        this.seed = seed;
        this.log = (log != null) ? log : msg -> { };
    }

    /** The default engine: the built-in passes, in canonical order, with {@code seed}. */
    public static HardenEngine defaults(long seed, Consumer<String> log) {
        List<HardenPass> ps = new ArrayList<>();
        ps.add(new net.marcloud.pg.engine.pass.StringConstantPass());
        // Future passes (FLOW / VIRTUALIZE) register here; each declares its own
        // minLevel, so ordering is by registration and gating is by level.
        return new HardenEngine(ps, seed, log);
    }

    /**
     * Harden one class if it is {@code @Guarded}. Returns the (possibly unchanged)
     * class bytes. Never throws for a single class — a failure is logged and the
     * original bytes are returned.
     *
     * @param className dotted class name (for logging + per-class seed)
     * @param original  the compiled class bytes
     * @return hardened bytes, or {@code original} if not guarded or on any failure
     */
    public Result harden(String className, byte[] original) {
        Guarded.Level level;
        try {
            level = GuardedScanner.scan(original);
        } catch (RuntimeException e) {
            log.accept("[pg] scan failed for " + className + " (skipped): " + e);
            return Result.skipped(original);
        }
        if (level == null) {
            return Result.notGuarded(original);
        }

        HardenContext ctx = new HardenContext(className, level, seed, log);
        byte[] current = original;
        List<String> applied = new ArrayList<>();

        for (HardenPass pass : passes) {
            if (level.ordinal() < pass.minLevel().ordinal()) {
                continue; // this level does not activate this pass
            }
            byte[] before = current;
            byte[] after;
            try {
                after = pass.apply(before, ctx);
            } catch (RuntimeException | Error e) {
                log.accept("[pg] pass '" + pass.id() + "' threw on " + className
                        + " — keeping pre-pass bytes: " + e);
                continue; // fail-safe: drop this pass, keep last-good
            }
            if (after == null || after == before || java.util.Arrays.equals(after, before)) {
                continue; // pass did nothing
            }
            String problem = verify(after);
            if (problem != null) {
                log.accept("[pg] pass '" + pass.id() + "' produced invalid bytecode for "
                        + className + " — reverting this pass: " + problem);
                continue; // fail-safe: revert to pre-pass bytes
            }
            current = after;
            applied.add(pass.id());
        }

        if (applied.isEmpty()) {
            return Result.notGuarded(original); // guarded, but no pass changed anything
        }
        return Result.hardened(current, level, applied);
    }

    /**
     * Re-verify transformed bytes: recompute stack-map frames and run ASM's
     * {@link CheckClassAdapter} structural checks. Returns null if valid, else a
     * diagnostic string. Does not resolve reference types through a classloader
     * (see the KI-7 note in the body).
     */
    private static String verify(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            // KI-7: verify WITHOUT resolving reference types through a classloader
            // that cannot see the project classes being hardened. Two changes work
            // together, because verify() had two independent type-loading paths:
            //
            //  1. FrameSafeClassWriter(COMPUTE_FRAMES) recomputes the stack-map
            //     frames, merging reference types to java/lang/Object instead of
            //     Class.forName-ing them (the stock ClassWriter threw
            //     TypeNotPresentException on a reference-type frame merge).
            //  2. CheckClassAdapter with checkDataFlow=FALSE runs STRUCTURAL checks
            //     only. The data-flow path (dataFlow=true) drives ASM's
            //     SimpleVerifier, which Class.forName-s the merged reference types
            //     and, for any type not on the build classloader, records an
            //     AnalyzerException — making verify() return non-null and forcing
            //     the engine to falsely revert a valid transform (shipping the
            //     class with its plaintext strings intact).
            //
            // The frame recomputation IS the meaningful "will this class load"
            // check here; structural checks catch malformed bytecode. Any real
            // structural or frame problem surfaces as an exception, caught below.
            ClassWriter cw = new FrameSafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            CheckClassAdapter cca = new CheckClassAdapter(cw, false);
            cr.accept(cca, 0);
            cw.toByteArray(); // force frame computation + serialization to surface errors
            return null;
        } catch (RuntimeException | Error e) {
            return "verification failed: " + e;
        }
    }

    /** Outcome of hardening one class. */
    public static final class Result {
        public enum Status { HARDENED, NOT_GUARDED, SKIPPED }

        private final Status status;
        private final byte[] bytes;
        private final Guarded.Level level;
        private final List<String> appliedPasses;

        private Result(Status status, byte[] bytes, Guarded.Level level, List<String> applied) {
            this.status = status;
            this.bytes = bytes;
            this.level = level;
            this.appliedPasses = (applied != null) ? applied : List.of();
        }

        static Result hardened(byte[] b, Guarded.Level lvl, List<String> applied) {
            return new Result(Status.HARDENED, b, lvl, applied);
        }

        static Result notGuarded(byte[] b) {
            return new Result(Status.NOT_GUARDED, b, null, List.of());
        }

        static Result skipped(byte[] b) {
            return new Result(Status.SKIPPED, b, null, List.of());
        }

        public Status status() {
            return status;
        }

        public byte[] bytes() {
            return bytes;
        }

        public Guarded.Level level() {
            return level;
        }

        public List<String> appliedPasses() {
            return appliedPasses;
        }

        public boolean changed() {
            return status == Status.HARDENED;
        }
    }
}
