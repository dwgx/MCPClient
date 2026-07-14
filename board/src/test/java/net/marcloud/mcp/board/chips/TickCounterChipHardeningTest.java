package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import net.marcloud.mcp.board.Trace;

/**
 * KI-5 proof: PatchGuard actually hardens real bytecode. {@link TickCounterChip}
 * is the first {@code @Guarded} consumer in the project, and this test asserts the
 * build-time {@code pg-maven-plugin} hardening pass ran over its packaged
 * {@code .class} — the plaintext {@code "diagnostic"} category label is GONE from
 * the constant pool (encoded by the StringConstantPass), the synthesized
 * {@code pg$dec} decoder was injected, AND the class still LOADS and BEHAVES (the
 * value decodes back to {@code "diagnostic"} at runtime).
 *
 * <p><b>Non-vacuous.</b> This test loads the SAME hardened {@code .class} the JVM
 * runs (from {@code target/classes}, rewritten in the {@code process-classes}
 * phase before test-compile). On the old state — no {@code @Guarded} annotation or
 * the plugin not wired into {@code board}'s build — the constant pool would still
 * carry the raw {@code "diagnostic"} UTF-8 and {@link #categoryLabelIsEncodedInPackagedClass()}
 * would fail. It also fails if a future regression makes the pass silently revert
 * (KI-7) and ship plaintext while reporting success.
 */
public class TickCounterChipHardeningTest {

    /** The display label this class returns; it must NOT survive as plaintext bytecode. */
    private static final String PLAINTEXT_LABEL = "diagnostic";

    /** The decoder method the StringConstantPass injects into every hardened class. */
    private static final String INJECTED_DECODER = "pg$dec";

    /**
     * The packaged bytes of {@link TickCounterChip} as the JVM sees them on the
     * classpath — i.e. {@code target/classes/.../TickCounterChip.class} AFTER the
     * harden pass rewrote it in place. This is the real artifact, not a re-read of
     * source, so the assertions below measure what actually ships.
     */
    private static byte[] packagedClassBytes() throws IOException {
        String resource = TickCounterChip.class.getSimpleName() + ".class";
        try (InputStream in = TickCounterChip.class.getResourceAsStream(resource)) {
            assertNotNull("could not locate packaged " + resource + " on the classpath", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** True if {@code needle} appears as a raw byte subsequence in {@code haystack}. */
    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    public void categoryLabelIsEncodedInPackagedClass() throws IOException {
        byte[] classBytes = packagedClassBytes();

        // (1) The plaintext label must be GONE from the packaged constant pool.
        //     Fails on the old (un-hardened) state where it is stored verbatim.
        assertFalse(
                "plaintext \"" + PLAINTEXT_LABEL + "\" is still present in the packaged "
                        + "TickCounterChip.class — the pg harden pass did NOT run (KI-5 regressed)",
                containsBytes(classBytes, PLAINTEXT_LABEL.getBytes(StandardCharsets.UTF_8)));

        // (2) The injected decoder proves the StringConstantPass positively ran
        //     (absence of plaintext alone could be a false negative; the decoder
        //     is the pass's fingerprint).
        assertTrue(
                "the injected " + INJECTED_DECODER + " decoder is absent — the class was not hardened",
                containsBytes(classBytes, INJECTED_DECODER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void hardenedClassStillLoadsAndDecodesAtRuntime() {
        // Constructing + calling exercises the injected pg$dec decoder: if the
        // hardened bytecode were unloadable (the KI-6 "reported HARDENED but
        // broken" bug) this throws at class-init/verify; if the decoder were wrong
        // the label would not round-trip.
        TickCounterChip chip = new TickCounterChip(new Trace());
        assertEquals(
                "hardened class must decode its category back to the original literal",
                PLAINTEXT_LABEL, chip.category());
    }
}
