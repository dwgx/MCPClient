package net.marcloud.pg.plugin;

import net.marcloud.pg.engine.HardenEngine;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The PatchGuard build step. Binds after {@code process-classes} (compile is done,
 * jar not yet packaged), scans {@code outputDirectory} for {@code .class} files
 * marked {@link net.marcloud.pg.Guarded @Guarded}, hardens them via
 * {@link HardenEngine}, and atomically replaces the class files in place so the
 * subsequently-packaged jar carries the hardened bytecode. Zero source pollution.
 *
 * <p>Fail-safe end to end: the engine never returns unloadable bytes, and the
 * file write is atomic (temp + move), so a crash mid-run cannot leave a half-written
 * class. A per-class engine failure leaves that class untouched.
 */
@Mojo(name = "harden", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public final class HardenMojo extends AbstractMojo {

    /** Directory of compiled classes to harden (defaults to target/classes). */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "pg.outputDirectory")
    private String outputDirectory;

    /**
     * Seed for the moving-target hardening. Default -1 means "generate a fresh
     * random seed each build" (per-release moving target). Pin it to a fixed value
     * for a reproducible build.
     */
    @Parameter(defaultValue = "-1", property = "pg.seed")
    private long seed;

    /** If true, only report what would be hardened without writing (dry run). */
    @Parameter(defaultValue = "false", property = "pg.dryRun")
    private boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        Path root = Paths.get(outputDirectory);
        if (!Files.isDirectory(root)) {
            getLog().info("[pg] no output directory (" + root + "); nothing to harden.");
            return;
        }
        long effectiveSeed = (seed == -1L) ? new java.security.SecureRandom().nextLong() : seed;
        HardenEngine engine = HardenEngine.defaults(effectiveSeed, msg -> getLog().info(msg));

        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
        } catch (IOException e) {
            throw new MojoExecutionException("[pg] failed to walk " + root, e);
        }

        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger hardened = new AtomicInteger();
        for (Path classFile : classFiles) {
            scanned.incrementAndGet();
            byte[] original;
            try {
                original = Files.readAllBytes(classFile);
            } catch (IOException e) {
                getLog().warn("[pg] could not read " + classFile + " (skipped): " + e);
                continue;
            }
            String className = classNameOf(root, classFile);
            HardenEngine.Result result = engine.harden(className, original);
            if (!result.changed()) {
                continue;
            }
            hardened.incrementAndGet();
            getLog().info("[pg] hardened " + className + " " + result.level()
                    + " passes=" + result.appliedPasses());
            if (dryRun) {
                continue;
            }
            try {
                atomicReplace(classFile, result.bytes());
            } catch (IOException e) {
                // Fail-safe: original class file is left intact (temp write failed
                // before move, or move failed). We do NOT fail the build for one
                // class, but we do surface it loudly.
                getLog().warn("[pg] could not write hardened " + className
                        + " — leaving original in place: " + e);
            }
        }
        getLog().info("[pg] done: scanned " + scanned.get() + " classes, hardened "
                + hardened.get() + (dryRun ? " (dry run, nothing written)" : "")
                + "; seed=" + (seed == -1L ? "random-per-build" : String.valueOf(seed)));
    }

    /** target/classes-relative path -> dotted class name. */
    private static String classNameOf(Path root, Path classFile) {
        String rel = root.relativize(classFile).toString();
        String noExt = rel.substring(0, rel.length() - ".class".length());
        return noExt.replace('/', '.').replace('\\', '.');
    }

    /** Write bytes to a sibling temp file then ATOMIC_MOVE onto the target. */
    private static void atomicReplace(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".pg-tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Some filesystems lack atomic move; fall back to plain replace.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
