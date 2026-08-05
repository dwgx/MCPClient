package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Behavioural teeth for {@link Compat#defaultTrustAnchors()} under a BROKEN root chain, which is
 * the one configuration its existing guard cannot reach.
 *
 * <p>{@code TrustAnchorRevocationTest#defaultTrustAnchorsHasNoFallbackBranch} says so in its own
 * javadoc: "It pins shape rather than behaviour, because the shipped resources derive successfully
 * and so cannot exercise the empty branch from outside." That confession is exact, and it is the
 * whole gap. That guard greps the method body for the literal {@code RootTrust.effectiveAnchors()}
 * and for the absence of {@code KernelTrustAnchor} -- so a body which calls
 * {@code RootTrust.effectiveAnchors()}, finds it empty, and then reads
 * {@code root-metadata.json}'s targetsKeys directly WITHOUT any root-signature verification
 * satisfies both assertions while restoring the defect in a strictly worse form. Measured: that
 * mutation SURVIVED the whole selection ({@code TrustAnchorRevocationTest}, {@code Ki1SignedArming},
 * {@code Ki4SignedArming}, {@code Ki11SigningContract}) on 2026-08-05.
 *
 * <p>Consequence of the surviving mutation: delete or damage {@code root-metadata.sig} and all
 * three shipped patches still arm, under a root document nothing verified, while {@link RootTrust}
 * has already printed "no patch will arm (fail-closed)". The byte-equality defence downstream
 * ("the declared root key must equal the baked one") passes meaninglessly there, because both
 * references are the same key.
 *
 * <p><b>Why a classloader and not an injected path.</b> The resource names are private statics read
 * through {@code RootTrust.class.getResourceAsStream}, and widening them for a test is the wrong
 * direction (handoff-2026-08-05 section 5(3)). Instead {@link HidingLoader} defines {@code Compat}
 * and {@code RootTrust} itself, so their resource lookups route through a loader that can hide one
 * entry, and every other class -- including {@link TrustAnchors} -- is delegated to the parent. That
 * keeps the RETURN type identical to this test's own, so the assertions are direct rather than
 * reflective on the value.
 *
 * <p><b>Which cases actually have teeth, measured rather than assumed.</b> Blocking the signature or
 * the baked root key leaves {@code root-metadata.json} readable, which is precisely what the
 * unverified-recovery mutation needs -- those two catch it. Blocking the metadata does NOT catch it
 * (the mutation reads the same missing file and also yields empty), and it is kept anyway because it
 * pins a distinct fail-closed branch; it is documented here as a guard rather than a capture so no
 * later reader mistakes it for coverage it does not provide.
 *
 * <p>The positive case is not decoration either: every assertion below is satisfied by an
 * implementation that returns empty unconditionally, and that implementation would break arming
 * entirely instead of loosening it -- the same reason the four trust-chain tests added in 547ea81
 * each carry a positive half.
 */
public class ABrokenRootChainDisarmsTheProductionCompositionTest {

    private static final String PKG = "net/marcloud/mcp/core/compat/";
    private static final String ROOT_PUB = PKG + "root-ed25519.pub";
    private static final String ROOT_META = PKG + "root-metadata.json";
    private static final String ROOT_SIG = PKG + "root-metadata.sig";

    /** The classes that must be re-defined so their resource reads route through the loader. */
    private static final String[] ISOLATED = {
        "net.marcloud.mcp.core.compat.Compat",
        "net.marcloud.mcp.core.compat.RootTrust",
    };

    @Test
    public void theShippedChainStillArmsSoTheNegativesBelowMeanSomething() throws Exception {
        TrustAnchors anchors = defaultTrustAnchorsWith(null);
        assertFalse("with nothing hidden the production composition must still derive anchors -- "
                + "otherwise every assertion in this file is also satisfied by a method that "
                + "returns empty unconditionally, which breaks arming rather than loosening it",
            anchors.isEmpty());
        assertNotNull("and they must contain the kernel keyId the shipped patches are signed under",
            anchors.lookup(KernelTrustAnchor.KEY_ID));
    }

    @Test
    public void aDamagedRootSignatureDisarmsRatherThanFallingBack() throws Exception {
        TrustAnchors anchors = defaultTrustAnchorsWith(ROOT_SIG);
        assertTrue("with root-metadata.sig unreadable the chain cannot be verified, so the "
                + "production composition must return NO anchors. Returning any here means a "
                + "patch arms under a document that nothing authenticated -- and note the "
                + "document is still readable, so any 'recover the targets keys from it' "
                + "fallback lands exactly here",
            anchors.isEmpty());
        assertEquals("no anchor may survive an unverifiable chain", 0, anchors.size());
    }

    @Test
    public void aMissingBakedRootKeyDisarmsRatherThanFallingBack() throws Exception {
        TrustAnchors anchors = defaultTrustAnchorsWith(ROOT_PUB);
        assertTrue("without the baked root key there is nothing to chain UP to, so derivation is "
                + "impossible and the answer must be empty. root-metadata.json is still readable "
                + "here, so this is the second place an unverified recovery would show itself",
            anchors.isEmpty());
    }

    @Test
    public void aMissingRootDocumentDisarms() throws Exception {
        TrustAnchors anchors = defaultTrustAnchorsWith(ROOT_META);
        assertTrue("no document means no authorized targets key", anchors.isEmpty());
        // Honest limit, kept so nobody reads this method as coverage it does not give: this case
        // does NOT catch the unverified-recovery mutation, because that mutation reads this same
        // missing document and therefore also ends up empty. It pins a distinct fail-closed branch.
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Call the production {@code Compat.defaultTrustAnchors()} with one resource hidden.
     *
     * @param hidden resource path to make unreadable, or null to hide nothing
     */
    private static TrustAnchors defaultTrustAnchorsWith(String hidden) throws Exception {
        ClassLoader parent = ABrokenRootChainDisarmsTheProductionCompositionTest.class
                .getClassLoader();
        HidingLoader loader = new HidingLoader(parent, hidden);
        Class<?> compat = loader.loadClass("net.marcloud.mcp.core.compat.Compat");
        assertEquals("Compat must come from the hiding loader, or the resource block does not "
                + "apply and this test silently measures the real chain every time",
            loader, compat.getClassLoader());

        Method m = compat.getMethod("defaultTrustAnchors");
        Object result = m.invoke(null);
        assertTrue("TrustAnchors must stay parent-loaded so this cast is legal; if it does not, "
                + "ISOLATED is over-broad", result instanceof TrustAnchors);
        return (TrustAnchors) result;
    }

    /**
     * Defines {@link #ISOLATED} itself and delegates the rest, hiding at most one resource.
     *
     * <p>Redefining the classes is what makes the hiding effective: {@code X.class.getResource*}
     * resolves through the loader that defined X, so a parent-loaded RootTrust would read the real
     * resources no matter what this loader says.
     */
    private static final class HidingLoader extends ClassLoader {
        private final String hidden;

        HidingLoader(ClassLoader parent, String hidden) {
            super(parent);
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String iso : ISOLATED) {
                if (iso.equals(name)) {
                    Class<?> already = findLoadedClass(name);
                    if (already != null) {
                        return already;
                    }
                    byte[] bytes = readClassBytes(name);
                    Class<?> c = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }

        private byte[] readClassBytes(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (hidden != null && hidden.equals(name)) {
                return null;
            }
            return super.getResourceAsStream(name);
        }

        @Override
        public java.net.URL getResource(String name) {
            if (hidden != null && hidden.equals(name)) {
                return null;
            }
            return super.getResource(name);
        }
    }
}
