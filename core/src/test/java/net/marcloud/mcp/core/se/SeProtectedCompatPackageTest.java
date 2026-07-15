package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * S6 teeth (self-lobotomy gap): the compat trust-core package
 * ({@code net.marcloud.mcp.core.compat.*}) MUST be protected against redefine/retransform.
 * Without it, {@code redefine_class} / Byte Buddy could hot-swap
 * {@code Ed25519PatchSigner.verify} to always-true and disarm signature checking from inside.
 *
 * <p>These assertions FAIL on pre-fix code (the compat package had no prefix rule), and pass
 * once {@code COMPAT_PACKAGE} is OR'd into {@link SeProtectedObjects#isProtected(String)}.
 */
public final class SeProtectedCompatPackageTest {

    @Test
    public void compatSignerIsProtected() {
        assertTrue("Ed25519PatchSigner must be protected — hot-swapping verify()->true disarms signing",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.Ed25519PatchSigner"));
    }

    @Test
    public void compatEngineIsProtected() {
        assertTrue("CompatEngine (the arming spine) must be protected",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.CompatEngine"));
    }

    @Test
    public void deeperCompatTrustClassesAreProtectedByPrefix() {
        // Whole-package coverage: trust classes named later are protected automatically.
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.PatchChain"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.TrustAnchors"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.patches.SomeFuturePatch"));
    }

    @Test
    public void compatInnerAndArrayFormsAreCovered() {
        // normalize() strips inner-class and array wrappers before the prefix check.
        assertTrue("inner class of a compat type is covered",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.compat.CompatEngine$PatchTransformer"));
        assertTrue("array form of a compat type is covered",
                SeProtectedObjects.isProtected("[Lnet.marcloud.mcp.core.compat.CompatEngine;"));
    }

    @Test
    public void nonCompatGameClassStaysUnprotected() {
        // The legitimate use case — patching vanilla — must remain allowed.
        assertFalse("vanilla MC classes must never be protected",
                SeProtectedObjects.isProtected("net.minecraft.client.Minecraft"));
        // A class whose name merely CONTAINS "compat" but is not in the package is not covered.
        assertFalse("a non-core class outside the compat package is not protected",
                SeProtectedObjects.isProtected("com.example.compat.Thing"));
    }
}
