package net.marcloud.mcp.core.flt;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Generic inlined advice for arbitrary method hooks installed by
 * {@link FltDynamicManager}. Because this is INLINED into the target MC
 * class's bytecode (not delegated), it may only call public statics visible to
 * the target's classloader. The per-hook routeKey is carried as a bound
 * constant via {@code @HookId}, set by
 * {@code Advice.withCustomMapping().bind(HookId.class, key)}.
 *
 * <p>This captures the method name and all arguments (read-only snapshot) and
 * forwards them to {@link HookBridge#dispatch}, which publishes a
 * {@link net.marcloud.mcp.core.ke.event.events.HookFiredEvent} on the
 * {@link net.marcloud.mcp.core.ke.event.EventBus}. The advice is defensive: it
 * never throws into the game thread, mirroring the contract of the built-in
 * NetworkAdvice (see {@link HookBridge}).
 */
public final class GenericEntryAdvice {

    private GenericEntryAdvice() {
    }

    /**
     * Inlined at method entry. Captures the routeKey (bound constant), method
     * name, and all arguments (read-only), then dispatches to HookBridge.
     *
     * @param routeKey the per-hook identifier bound at install time
     * @param method   the target method name (via @Origin "#m")
     * @param args     read-only snapshot of all arguments (never null, empty if
     *                 the method takes no arguments)
     */
    @Advice.OnMethodEnter
    static void enter(@HookId int routeKey,
                      @Advice.Origin("#m") String method,
                      @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC, nullIfEmpty = false) Object[] args) {
        HookBridge.dispatch(routeKey, method, args);
    }
}
