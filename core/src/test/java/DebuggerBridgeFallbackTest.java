import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.debug.DebuggerBridge;
import net.marcloud.mcp.core.debug.DebuggerUnavailableException;
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
                DebuggerBridge.isAvailable());
        assertNotNull(DebuggerBridge.unavailableReason());
        assertTrue("reason names the missing launch flag",
                DebuggerBridge.unavailableReason().contains("-agentpath:core-jvmti.dll"));
    }

    @Test
    public void publicOpsThrowDomainException_neverLinkageError() {
        Thread self = Thread.currentThread();
        // Each wrapper must fail with the domain exception, not a raw ULE.
        assertDomainFailure(() -> DebuggerBridge.suspendThread(self));
        assertDomainFailure(() -> DebuggerBridge.resumeThread(self));
        assertDomainFailure(() -> DebuggerBridge.popFrame(self));
        assertDomainFailure(() -> DebuggerBridge.forceReturnVoid(self));
        assertDomainFailure(() -> DebuggerBridge.forceReturnInt(self, 0));
        assertDomainFailure(() -> DebuggerBridge.forceReturnObject(self, null));
        assertDomainFailure(() -> DebuggerBridge.setBreakpoint(String.class, "length", "()I", 0));
        assertDomainFailure(() -> DebuggerBridge.setSingleStep(self, true));
        assertDomainFailure(() -> DebuggerBridge.readLocalInt(self, 0, 0));
        assertDomainFailure(() -> DebuggerBridge.readLocalObject(self, 0, 0));
        assertDomainFailure(() -> DebuggerBridge.writeLocalInt(self, 0, 0, 0));
        assertDomainFailure(() -> DebuggerBridge.watchFieldModification(String.class, "value", "[B"));
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
