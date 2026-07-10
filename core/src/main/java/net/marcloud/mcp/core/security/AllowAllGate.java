package net.marcloud.mcp.core.security;

/**
 * No-op {@link AccessGate}: allows every capability + privilege combination.
 * This is the dev-default so DeepAccess works out-of-the-box while window-A
 * builds the real L4/L5 policy engine.
 *
 * <p>Production deployments will replace this with an enforcement gate that
 * queries the calling subject's granted SID set and enabled privilege mask, and
 * throws {@link SecurityException} when a requirement isn't met.
 */
public final class AllowAllGate implements AccessGate {

    @Override
    public void require(CapabilitySid cap, Privilege... privs) {
        // allow unconditionally (dev default)
    }
}
