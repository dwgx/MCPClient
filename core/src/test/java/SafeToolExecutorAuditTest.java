import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import net.marcloud.mcp.core.registry.SafeToolExecutor;
import net.marcloud.mcp.core.registry.ToolStats;
import org.junit.Test;

public class SafeToolExecutorAuditTest {

    @Test
    public void interruptIgnoringRunawaysTripLimitAndRecoveryLaneStillRuns() throws Exception {
        SafeToolExecutor executor = new SafeToolExecutor(2, 100L);
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch exited = new CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) {
                CallToolResult timedOut = executor.run(new ToolStats("runaway-" + i),
                        (exchange, request) -> {
                            try {
                                while (!release.get()) {
                                    Thread.interrupted();
                                    Thread.onSpinWait();
                                }
                                return success();
                            } finally {
                                exited.countDown();
                            }
                        }, null, null, 100L);
                assertTrue("runaway call times out", Boolean.TRUE.equals(timedOut.isError()));
            }

            AtomicBoolean ordinaryRan = new AtomicBoolean();
            CallToolResult rejected = executor.run(new ToolStats("ordinary"),
                    (exchange, request) -> {
                        ordinaryRan.set(true);
                        return success();
                    }, null, null, 500L);
            assertTrue("ordinary call is rejected at runaway limit",
                    Boolean.TRUE.equals(rejected.isError()));
            assertFalse("rejected handler never enters the pool", ordinaryRan.get());

            AtomicBoolean recoveryRan = new AtomicBoolean();
            CallToolResult recovery = executor.run(new ToolStats("list_capabilities"),
                    (exchange, request) -> {
                        recoveryRan.set(true);
                        return success();
                    }, null, null, 500L);
            assertFalse("recovery lane remains available", Boolean.TRUE.equals(recovery.isError()));
            assertTrue(recoveryRan.get());

            release.set(true);
            assertTrue("runaway handlers eventually exit", exited.await(2, TimeUnit.SECONDS));

            AtomicBoolean resumed = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!resumed.get() && System.nanoTime() < deadline) {
                executor.run(new ToolStats("resumed"), (exchange, request) -> {
                    resumed.set(true);
                    return success();
                }, null, null, 500L);
                Thread.yield();
            }
            assertTrue("abandoned count decrements when handlers really finish", resumed.get());
        } finally {
            release.set(true);
            executor.shutdown();
        }
    }

    private static CallToolResult success() {
        return CallToolResult.builder().addTextContent("ok").build();
    }
}
