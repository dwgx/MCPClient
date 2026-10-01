package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Audit H5 — the L1 P-SECURE wall's DECISION CODEC must be protected from in-JVM redefinition.
 *
 * <p>{@code SeRemoteMonitor} asks a separate process for every decision and parses the answer
 * with {@code net.marcloud.mcp.core.io.http.Json.readObject}, then acts on
 * {@code Boolean.TRUE.equals(verdict.get(AlpcProtocol.K_ALLOW))}. The guard protected
 * {@code HttpFacade} by exact name but not {@code Json}, so {@code isProtected} answered false
 * for the class the wall trusts to tell it whether a tool may run: {@code redefine_class} could
 * hot-swap {@code readObject} to always return {@code {"allow":true}} and disarm the remote
 * reference monitor without touching any protected class. That is the same coverage gap the
 * {@code alpc} package prefix closed one layer down (see the class comment on
 * {@link SeProtectedObjects#isProtected(String)}), and the fix is the same shape: protect the
 * package that carries the verdict, not the one class that happened to be noticed.
 *
 * <p>These assertions FAIL on the pre-fix code — {@code isProtected("...io.http.Json")} was
 * false, which is exactly what made the wall forgeable.
 */
public final class SeProtectedVerdictCodecTest {

    @Test
    public void theVerdictCodecTheRemoteMonitorTrustsIsProtected() {
        assertTrue("Json is the codec SeRemoteMonitor parses the authority's verdict with — a "
                        + "hot-swapped readObject returning {\"allow\":true} disarms the whole "
                        + "remote reference monitor",
                SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.http.Json"));
    }

    @Test
    public void theWholeHttpFrontDoorIsCoveredByThePrefix() {
        // HttpFacade used to need its own exact-name pin; the prefix now covers it, and
        // SseStream (the event feed beside it) with it. A class added to the package later is
        // covered automatically — the reason a prefix is right for a decision-bearing package.
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.http.HttpFacade"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.http.SseStream"));
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.http.SomeCodecAddedLater"));
    }

    @Test
    public void innerAndArrayFormsOfTheCodecAreCovered() {
        assertTrue(SeProtectedObjects.isProtected("net.marcloud.mcp.core.io.http.Json$1"));
        assertTrue(SeProtectedObjects.isProtected("[Lnet.marcloud.mcp.core.io.http.Json;"));
    }

    @Test
    public void theLegitimateRedefineTargetsStayRedefinable() {
        // The guard must not swallow the reason it exists: vanilla game classes (and unrelated
        // core classes outside the protected layers) remain redefinable.
        assertFalse(SeProtectedObjects.isProtected("net.minecraft.client.Minecraft"));
        assertFalse(SeProtectedObjects.isProtected("net.marcloud.mcp.core.drivers.world.WorldScanner"));
        assertFalse(SeProtectedObjects.isProtected("com.example.http.Json"));
    }
}
