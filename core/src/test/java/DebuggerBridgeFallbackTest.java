import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.kd.KdBridge;
import net.marcloud.mcp.core.kd.DebuggerUnavailableException;
import org.junit.Test;

/**
 * The load-bearing safety proof for C6: with NO native DLL present (the default
 * headless state, MSVC not installed), the bridge degrades cleanly — every
 * public op throws {@link DebuggerUnavailableException}, NEVER a leaked
 * {@link UnsatisfiedLinkError} or {@link NoClassDefFoundError}.
 */
public class DebuggerBridgeFallbackTest {

    @Test
    public void bridgeIsUnavailableWithoutTheDll() {
        assertFalse("no -agentpath:core-jvmti.dll in the test JVM",
                KdBridge.isAvailable());
        assertNotNull(KdBridge.unavailableReason());
        assertTrue("reason names the missing launch flag",
                KdBridge.unavailableReason().contains("-agentpath:core-jvmti.dll"));
    }

    @Test
    public void publicOpsThrowDomainException_neverLinkageError() {
        Thread self = Thread.currentThread();
        // Each wrapper must fail with the domain exception, not a raw ULE.
        assertDomainFailure(() -> KdBridge.suspendThread(self));
        assertDomainFailure(() -> KdBridge.resumeThread(self));
        assertDomainFailure(() -> KdBridge.popFrame(self));
        assertDomainFailure(() -> KdBridge.forceReturnVoid(self));
        assertDomainFailure(() -> KdBridge.forceReturnInt(self, 0));
        assertDomainFailure(() -> KdBridge.forceReturnObject(self, null));
        assertDomainFailure(() -> KdBridge.setBreakpoint(String.class, "length", "()I", 0));
        assertDomainFailure(() -> KdBridge.setSingleStep(self, true));
        assertDomainFailure(() -> KdBridge.readLocalInt(self, 0, 0));
        assertDomainFailure(() -> KdBridge.readLocalObject(self, 0, 0));
        assertDomainFailure(() -> KdBridge.writeLocalInt(self, 0, 0, 0));
        assertDomainFailure(() -> KdBridge.watchFieldModification(String.class, "value", "[B"));
    }

    private static void assertDomainFailure(Runnable op) {
        try {
            op.run();
            fail("expected DebuggerUnavailableException");
        } catch (DebuggerUnavailableException expected) {
            // correct: clean domain error
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            fail("leaked a linkage error instead of the domain exception: " + e);
        }
    }
}
