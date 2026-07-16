package net.marcloud.mcp.core.alpc;

import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeReferenceMonitor;
import net.marcloud.mcp.core.se.Ring;

import java.util.EnumSet;

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
 *        net.marcloud.mcp.core.alpc.AlpcMain
 * </pre>
 * The game JVM connects with {@code -Dmcp.core.psecure=true} and the SAME
 * {@code -Dmcp.core.psecureToken}. If no token is set, one is generated and
 * printed (both sides must then use it).
 */
public final class AlpcMain {

    private AlpcMain() {
    }

    public static void main(String[] args) throws Exception {
        String token = System.getProperty(AlpcProtocol.TOKEN_PROPERTY);
        if (token == null || token.isBlank()) {
            token = Long.toHexString(new java.security.SecureRandom().nextLong());
            System.err.println("[P-SECURE] no " + AlpcProtocol.TOKEN_PROPERTY
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
        SeReferenceMonitor authority = buildAuthority(new SeClearancePolicy(clearance, restore));
        String posture = postureOf();

        int port = Integer.getInteger("mcp.core.psecurePort", AlpcProtocol.DEFAULT_PORT);
        AlpcServer server = new AlpcServer(authority, port, token, posture);
        server.start();
        System.err.println("[P-SECURE] authority up (clearance " + clearance.tag()
                + "); Ctrl-C to stop.");
        // Keep the process alive; the daemon accept thread does the work.
        Thread.currentThread().join();
    }

    /**
     * Build the authoritative in-process reference monitor this P-SECURE process
     * carries, selecting the subject posture from the SAME opt-in flags the game
     * JVM understands (additive — the default with no flag is wide-open, so the
     * existing P-SECURE tests keep the wide-open authority):
     * <ul>
     *   <li>{@code -Dmcp.core.hardened=true} → {@link SeLocalMonitor#hardenedSubject()}:
     *       a biting subject that denies dangerous verbs at L4/L5 IN THIS PROCESS,
     *       so the game JVM gets the deny + layer name back across the wall.</li>
     *   <li>{@code -Dmcp.core.caps=strict} → {@link SeLocalMonitor#strictSubject}
     *       with an empty granted set (L5 default-deny, L4/L3 wide).</li>
     *   <li>otherwise → {@link net.marcloud.mcp.core.se.SeToken#wideOpen()} (the shipped default).</li>
     * </ul>
     * The wire contract, fail-closed semantics, and {@link AlpcServer} are unchanged;
     * this only selects which subject the authority evaluates against.
     */
    public static SeReferenceMonitor buildAuthority(SeClearancePolicy policy) {
        if ("true".equalsIgnoreCase(System.getProperty("mcp.core.hardened", "false"))) {
            System.err.println("[P-SECURE] authority posture: HARDENED "
                    + "(L4 privileges granted-but-disabled, L5 capabilities empty default-deny).");
            return new SeLocalMonitor(policy, SeLocalMonitor.hardenedSubject());
        }
        String caps = System.getProperty("mcp.core.caps", "wildcard");
        if ("strict".equalsIgnoreCase(caps.trim())) {
            System.err.println("[P-SECURE] authority posture: STRICT L5 default-deny.");
            return new SeLocalMonitor(policy,
                    SeLocalMonitor.strictSubject(EnumSet.noneOf(CapabilitySid.class)));
        }
        System.err.println("[P-SECURE] authority posture: wide-open (dev default).");
        return new SeLocalMonitor(policy);
    }

    /**
     * The posture string this process reports over {@link AlpcProtocol#M_POSTURE},
     * selected from the SAME flags {@link #buildAuthority} branches on (kept in
     * lock-step so the reported posture always matches the subject actually built).
     * The game JVM compares this against its own {@code -Dmcp.core.hardened} at
     * startup to catch a posture split (game hardened, authority wide-open).
     */
    public static String postureOf() {
        if ("true".equalsIgnoreCase(System.getProperty("mcp.core.hardened", "false"))) {
            return AlpcProtocol.POSTURE_HARDENED;
        }
        String caps = System.getProperty("mcp.core.caps", "wildcard");
        if ("strict".equalsIgnoreCase(caps.trim())) {
            return AlpcProtocol.POSTURE_STRICT;
        }
        return AlpcProtocol.POSTURE_WIDE_OPEN;
    }
}
