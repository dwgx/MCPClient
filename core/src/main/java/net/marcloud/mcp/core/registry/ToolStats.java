package net.marcloud.mcp.core.registry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-tool health + usage stats, and the circuit-breaker state. Feeds both the
 * self-heal logic (auto-quarantine a repeatedly-failing tool) and the
 * introspection tools (so the AI can see which of its capabilities are healthy).
 *
 * <p>Circuit states (Erlang/Resilience4j model): CLOSED = normal; OPEN = tool
 * quarantined after too many consecutive failures, calls fail fast; HALF_OPEN =
 * probing recovery (one trial call allowed).
 */
public final class ToolStats {

    public enum Circuit { CLOSED, OPEN, HALF_OPEN }

    /** Consecutive failures that trip the breaker open. */
    private static final int TRIP_THRESHOLD = 3;
    /** Cool-down before an OPEN breaker allows a probe (ms). */
    private static final long COOLDOWN_MS = 30_000;

    private final String toolName;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Circuit> circuit = new AtomicReference<>(Circuit.CLOSED);
    private volatile long openedAtMillis;
    private volatile String lastError;

    public ToolStats(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    /**
     * Whether a call may proceed now. CLOSED always passes. OPEN passes exactly
     * ONE probe once the cool-down elapses (transitioning to HALF_OPEN); further
     * calls are blocked until that probe resolves via recordSuccess/recordFailure.
     */
    public boolean allowCall() {
        Circuit c = circuit.get();
        if (c == Circuit.CLOSED) {
            return true;
        }
        if (c == Circuit.HALF_OPEN) {
            // A probe is already in flight; block everyone else.
            return false;
        }
        // OPEN: admit a single probe once the cool-down elapsed. Only the thread
        // that wins the CAS gets through; others see HALF_OPEN and are blocked.
        if (System.currentTimeMillis() - openedAtMillis >= COOLDOWN_MS) {
            return circuit.compareAndSet(Circuit.OPEN, Circuit.HALF_OPEN);
        }
        return false;
    }

    public void recordSuccess() {
        calls.incrementAndGet();
        consecutiveFailures.set(0);
        circuit.set(Circuit.CLOSED);
    }

    public void recordFailure(String error, boolean timeout) {
        calls.incrementAndGet();
        failures.incrementAndGet();
        if (timeout) {
            timeouts.incrementAndGet();
        }
        lastError = error;
        int cf = consecutiveFailures.incrementAndGet();
        // A failed HALF_OPEN probe must re-open immediately (else the circuit
        // would stay HALF_OPEN and block all calls forever). A CLOSED circuit
        // opens once the consecutive-failure threshold is reached.
        boolean shouldOpen = circuit.get() == Circuit.HALF_OPEN || cf >= TRIP_THRESHOLD;
        if (shouldOpen && circuit.getAndSet(Circuit.OPEN) != Circuit.OPEN) {
            openedAtMillis = System.currentTimeMillis();
        }
    }

    /** Manually reset the breaker (e.g. after the tool is fixed/redefined). */
    public void reset() {
        consecutiveFailures.set(0);
        circuit.set(Circuit.CLOSED);
        lastError = null;
    }

    public Circuit circuit() {
        return circuit.get();
    }

    public long calls() {
        return calls.get();
    }

    public long failures() {
        return failures.get();
    }

    public long timeouts() {
        return timeouts.get();
    }

    public String lastError() {
        return lastError;
    }

    /** One-line health summary for the capability manifest. */
    public String summary() {
        return String.format("%s: circuit=%s calls=%d failures=%d timeouts=%d%s",
                toolName, circuit.get(), calls.get(), failures.get(), timeouts.get(),
                lastError == null ? "" : " lastError=" + lastError);
    }
}
