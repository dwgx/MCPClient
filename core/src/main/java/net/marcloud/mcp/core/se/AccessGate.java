package net.marcloud.mcp.core.se;

import net.marcloud.mcp.core.mm.MmAccess;

/**
 * L4/L5 security contract: capability-SID + privilege gate for MmAccess
 * operations. Each mutating MmAccess method calls {@code require()} before
 * acting; an implementation can enforce custom L4/L5 policy by throwing an
 * exception when a required SID or privilege is missing.
 *
 * <p>This is a minimal SPI: window-A (P-SECURE) will build the real L4/L5 engine
 * that queries a subject's granted SID set and enabled privilege mask and denies
 * if the requirement isn't met. Today's {@link AllowAllGate} no-ops to keep
 * MmAccess unblocked while that lands.
 *
 * <p>Implementations throw {@link SecurityException} to deny.
 */
public interface AccessGate {

    /**
     * Assert that the calling subject has {@code cap} granted AND all specified
     * privileges enabled. Throws {@link SecurityException} if denied.
     *
     * @param cap   the capability SID the operation requires (e.g. CAP_MEMORY_WRITE)
     * @param privs the privileges the operation requires (e.g. SE_DEBUG_CLASS)
     * @throws SecurityException if the subject lacks the required cap or any privilege
     */
    void require(CapabilitySid cap, Privilege... privs);
}
