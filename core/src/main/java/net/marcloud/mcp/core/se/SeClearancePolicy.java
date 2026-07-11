package net.marcloud.mcp.core.se;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The system's current privilege clearance and the rule for what may run.
 *
 * <p>A tool at {@link Ring} {@code r} may run only when the current clearance is
 * at least as privileged as {@code r} — i.e. {@code clearance.level() <= r.level()}.
 * Example: clearance R2 permits R2 and R3 tools, but denies R1/R0/R-1.
 *
 * <p>Privilege can be <b>dropped</b> freely (self-sandboxing — the agent can
 * voluntarily give up power) but <b>restored</b> only by presenting the restore
 * token configured at startup, so a lowered agent cannot re-escalate itself. This
 * makes "pin the clearance low" a real kill-switch for the dangerous rings.
 *
 * <p>Thread-safe: clearance is an {@link AtomicReference} read on every tool call
 * from MCP worker threads.
 */
public final class SeClearancePolicy {

    private final AtomicReference<Ring> clearance;
    private final String restoreToken; // null => restore disabled entirely

    /**
     * @param initialClearance the highest ring allowed at startup (R_MINUS_1 =
     *                          wide open, the dev default)
     * @param restoreToken     secret required to raise privilege again; null
     *                          disables restore (a drop is then permanent)
     */
    public SeClearancePolicy(Ring initialClearance, String restoreToken) {
        this.clearance = new AtomicReference<>(initialClearance == null ? Ring.R_MINUS_1 : initialClearance);
        this.restoreToken = restoreToken;
    }

    /** Current clearance (highest ring currently permitted). */
    public Ring clearance() {
        return clearance.get();
    }

    /** True if a tool at {@code toolRing} may run under the current clearance. */
    public boolean allows(Ring toolRing) {
        // more-privileged clearance has the SMALLER level number
        return clearance.get().level() <= toolRing.level();
    }

    /**
     * Voluntarily lower clearance to {@code target} (self-sandbox). Only ever
     * reduces privilege; a request to raise via drop is ignored. Returns the new
     * clearance.
     */
    public Ring dropTo(Ring target) {
        return clearance.updateAndGet(cur ->
                target.level() > cur.level() ? target : cur);
    }

    /**
     * Raise clearance to {@code target}, gated by the restore token. Returns true
     * on success; false if the token is wrong/absent or restore is disabled.
     */
    public boolean tryRestore(Ring target, String token) {
        if (restoreToken == null || token == null || !tokensMatch(restoreToken, token)) {
            return false;
        }
        clearance.set(target);
        return true;
    }

    /**
     * Constant-time comparison of the presented token against the configured one.
     * {@link MessageDigest#isEqual} does not short-circuit on the first differing
     * byte, so it leaks neither the token length-prefix match nor position of the
     * first mismatch through timing — closing the timing side-channel on this
     * privilege-restore kill-switch. (String.equals would return early.)
     */
    private static boolean tokensMatch(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether privilege restoration is even possible (a token was configured). */
    public boolean restorable() {
        return restoreToken != null;
    }
}
