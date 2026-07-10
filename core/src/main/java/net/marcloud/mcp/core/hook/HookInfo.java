package net.marcloud.mcp.core.hook;

/**
 * Metadata for a single runtime hook: the target class and method it advises,
 * the advice class, the hook kind ("bytebuddy-advice-retransform" for the
 * fixed NetworkManager hooks, or any future kind), and an installed flag showing
 * current state. Used by {@code list_hooks} to report which hooks are wired.
 */
public record HookInfo(
        String targetClass,
        String targetMethod,
        String adviceClass,
        String kind,
        boolean installed) {
}
