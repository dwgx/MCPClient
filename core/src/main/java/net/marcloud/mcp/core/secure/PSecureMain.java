package net.marcloud.mcp.core.secure;

import net.marcloud.mcp.core.security.InProcessPolicyEngine;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.PolicyEngine;
import net.marcloud.mcp.core.security.Ring;

/**
 * Standalone entry point for the P-SECURE decision process (L1 VTL). Runs in its
 * OWN JVM — a separate address space the game JVM cannot reach except over the
 * loopback socket, which is the whole point: a compromised in-game hook cannot
 * forge a grant here.
 *
 * <p>Launch:
 * <pre>
 *   java -cp core-all.jar -Dmcp.core.psecureToken=&lt;secret&gt; \
 *        [-Dmcp.core.clearance=R-1] [-Dmcp.core.psecurePort=25601] \
 *        net.marcloud.mcp.core.secure.PSecureMain
 * </pre>
 * The game JVM connects with {@code -Dmcp.core.psecure=true} and the SAME
 * {@code -Dmcp.core.psecureToken}. If no token is set, one is generated and
 * printed (both sides must then use it).
 */
public final class PSecureMain {

    private PSecureMain() {
    }

    public static void main(String[] args) throws Exception {
        String token = System.getProperty(PSecureProtocol.TOKEN_PROPERTY);
        if (token == null || token.isBlank()) {
            token = Long.toHexString(new java.security.SecureRandom().nextLong());
            System.err.println("[P-SECURE] no " + PSecureProtocol.TOKEN_PROPERTY
                    + " set; generated one — set the SAME value in the game JVM: " + token);
        }

        Ring clearance = Ring.R_MINUS_1;
        String c = System.getProperty("mcp.core.clearance");
        if (c != null) {
            for (Ring r : Ring.values()) {
                if (c.trim().equalsIgnoreCase("R" + r.level()) || c.trim().equalsIgnoreCase(r.label())) {
                    clearance = r;
                    break;
                }
            }
        }
        // The authority carries a restore token too (its own), so the game JVM's
        // restore_privilege call is gated here, in the separate process.
        String restore = System.getProperty("mcp.core.restoreToken");
        if (restore == null || restore.isBlank()) {
            restore = Long.toHexString(new java.security.SecureRandom().nextLong());
            System.err.println("[P-SECURE] restore token: " + restore);
        }
        PolicyEngine authority = new InProcessPolicyEngine(new PermissionPolicy(clearance, restore));

        int port = Integer.getInteger("mcp.core.psecurePort", PSecureProtocol.DEFAULT_PORT);
        PSecureServer server = new PSecureServer(authority, port, token);
        server.start();
        System.err.println("[P-SECURE] authority up (clearance " + clearance.tag()
                + "); Ctrl-C to stop.");
        // Keep the process alive; the daemon accept thread does the work.
        Thread.currentThread().join();
    }
}
