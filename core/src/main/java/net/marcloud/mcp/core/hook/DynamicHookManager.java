package net.marcloud.mcp.core.hook;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.marcloud.mcp.core.event.EventBus;

/**
 * Runtime install/uninstall/list of Byte Buddy retransformation hooks, beating
 * Mixin's load-once ceiling. Each hook is its OWN {@link AgentBuilder} →
 * {@link ResettableClassFileTransformer}, so {@code uninstall(hookId)} can call
 * {@code transformer.reset(inst, RETRANSFORMATION)} to surgically revert ONLY
 * that hook while preserving other hooks on the same class. This is the
 * capability Mixin lacks: Mixin weaves once at class-load and cannot un-weave.
 *
 * <p>Hooks route through {@link HookBridge#dispatch} to publish
 * {@link net.marcloud.mcp.core.event.events.HookFiredEvent} on the
 * {@link EventBus}, so new hooks integrate with the same observer pattern as
 * the built-in NetworkManager packet hooks.
 *
 * <p><b>Security hardenings:</b>
 * <ul>
 *   <li>DENYLIST: refuses to hook any class in {@link #PROTECTED_TARGETS} (the
 *       security/agent/hook kernel itself), closing the C3 analog of the known
 *       redefine-class self-lobotomy hole.</li>
 *   <li>INJECTED INSTRUMENTATION: receives the {@link Instrumentation} handle
 *       at construction instead of calling {@code CoreAgent.instrumentation()}
 *       statically (starves that static call of a new caller and gives a test
 *       seam).</li>
 *   <li>L5 capability check: callers (HookTools) require
 *       {@code CAP_CLASS_RETRANSFORM} before install/uninstall, AND-composed
 *       with the ring gate.</li>
 * </ul>
 *
 * <p><b>Caveats:</b>
 * <ul>
 *   <li>@Sharable / re-add: retransformation modifies the target CLASS, so a
 *       hook on {@code NetworkManager.channelRead0} persists across reconnects
 *       and applies to every instance automatically (unlike C8 SEAM per-Channel
 *       handlers, which must be re-added on each new connection).</li>
 *   <li>Hidden classes: classes defined via {@code Lookup.defineHiddenClass} or
 *       dynamically-generated inner classes that never appear in the
 *       loaded-classes list cannot be hooked (limitation of
 *       {@code Instrumentation.retransformClasses}).</li>
 *   <li>Advice visibility: inlined advice runs inside the target's classloader
 *       and may only call public statics reachable there. {@link HookBridge}
 *       lives on the system loader (verified by the working built-in hooks).</li>
 * </ul>
 *
 * <p>All public methods are {@code synchronized} to serialize hook bookkeeping
 * and prevent races between concurrent install/uninstall on the same class.
 */
public final class DynamicHookManager implements HookSource {

    private final Instrumentation inst;
    private final EventBus bus;
    private final AtomicInteger routeSeq = new AtomicInteger(1);
    private final ConcurrentHashMap<String, HookRecord> hooks = new ConcurrentHashMap<>();

    /**
     * A live hook: bookkeeping for one installed transformer. The transformer
     * handle is what {@code uninstall} passes to
     * {@code .reset(inst, RETRANSFORMATION)} to revert the hook.
     */
    public record HookRecord(String hookId, String targetClass, String method,
                             int routeKey, ResettableClassFileTransformer transformer,
                             long installedAtNanos) {
    }

    /**
     * Construct a DynamicHookManager. Wires {@link HookBridge} to the given bus
     * (idempotent: {@code HookBridge.setBus} is safe to call multiple times).
     *
     * @param inst the Instrumentation handle (from AgentAccess), or null if the
     *             agent was never loaded
     * @param bus  the EventBus that hook events publish to
     */
    public DynamicHookManager(Instrumentation inst, EventBus bus) {
        this.inst = inst;
        this.bus = bus;
        HookBridge.setBus(bus);
    }

    /**
     * True if hooks can be installed: Instrumentation is present and supports
     * retransformation (requires {@code -javaagent:core-agent.jar} at launch).
     */
    public boolean canInstall() {
        return inst != null && inst.isRetransformClassesSupported();
    }

    /**
     * Install a generic hook on the given class+method. Returns a stable hookId
     * that {@link #uninstall} accepts. The hook fires on every invocation of
     * the target method, publishing a
     * {@link net.marcloud.mcp.core.event.events.HookFiredEvent} to the bus.
     *
     * @param targetClassName fully-qualified target class (e.g.
     *                        "net.minecraft.network.NetworkManager")
     * @param methodName      the method to hook (e.g. "channelRead0")
     * @return the hookId for this hook (format: "className#method@routeKey")
     * @throws IllegalStateException if Instrumentation is unavailable or
     *                               retransform is unsupported
     * @throws SecurityException     if targetClassName is in the protected set
     * @throws RuntimeException      if the target class is not loaded, not
     *                               modifiable, or the retransform fails
     */
    public synchronized String install(String targetClassName, String methodName) {
        return install(targetClassName, methodName, null);
    }

    /**
     * Install a hook with an optional custom advice class. If adviceClass is
     * null, uses the generic {@link GenericEntryAdvice}; otherwise uses the
     * provided advice (e.g. {@link NetworkAdvice.ChannelRead0}).
     *
     * @param targetClassName fully-qualified target class
     * @param methodName      the method to hook
     * @param adviceClass     the advice class to inline, or null for generic
     * @return the hookId
     * @throws IllegalStateException if Instrumentation unavailable
     * @throws SecurityException     if targetClassName is protected
     * @throws RuntimeException      if install fails
     */
    public synchronized String install(String targetClassName, String methodName,
                                       Class<?> adviceClass) {
        // 1. DENYLIST: refuse protected classes FIRST (closes C3 self-lobotomy hole).
        // Delegates to the canonical ProtectedClasses set (one source of truth,
        // shared with the redefine guard). Runs before the inst==null gate so it
        // is verifiable in tests without a live agent.
        if (net.marcloud.mcp.core.security.ProtectedClasses.isProtected(targetClassName)) {
            throw new SecurityException(
                    "refusing to hook protected class " + targetClassName);
        }

        // 2. GATE: Instrumentation present + retransform supported
        if (!canInstall()) {
            throw new IllegalStateException(
                    "Cannot install hooks: Instrumentation/retransform unavailable. "
                    + "Start with -javaagent:core-agent.jar");
        }

        // 3. Resolve the target (without forcing load) and check modifiability
        Class<?> targetClass;
        try {
            targetClass = Class.forName(targetClassName, false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "target class not loaded or not found: " + targetClassName, e);
        }
        if (!inst.isModifiableClass(targetClass)) {
            throw new RuntimeException(
                    "target class is not modifiable: " + targetClassName);
        }

        // 4. Allocate routeKey + hookId, register route BEFORE installOn (advice
        // may fire immediately on a hot method)
        int routeKey = routeSeq.getAndIncrement();
        String hookId = targetClassName + "#" + methodName + "@" + routeKey;
        HookBridge.registerRoute(routeKey, bus, targetClassName, methodName);

        // 5. Build the AgentBuilder with the appropriate advice visitor
        ResettableClassFileTransformer transformer;
        try {
            AgentBuilder.Transformer adviceTransformer;
            if (adviceClass != null) {
                // Custom advice (e.g. NetworkAdvice.ChannelRead0)
                Advice advice = Advice.to(adviceClass, locator(adviceClass));
                adviceTransformer = (builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(advice.on(ElementMatchers.named(methodName)));
            } else {
                // Generic route: bind the routeKey as a constant
                Advice generic = Advice.withCustomMapping()
                        .bind(HookId.class, routeKey)
                        .to(GenericEntryAdvice.class, locator(GenericEntryAdvice.class));
                adviceTransformer = (builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(generic.on(ElementMatchers.named(methodName)));
            }

            transformer = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .disableClassFormatChanges()  // advice is format-preserving
                    .type(ElementMatchers.named(targetClassName))
                    .transform(adviceTransformer)
                    .installOn(inst);  // <-- KEEP the handle (the whole point)
        } catch (Throwable t) {
            // Install failed: clean up the route registration
            HookBridge.unregisterRoute(routeKey);
            throw new RuntimeException(
                    "failed to install hook on " + targetClassName + "." + methodName, t);
        }

        // 6. Record the hook
        hooks.put(hookId, new HookRecord(hookId, targetClassName, methodName,
                routeKey, transformer, System.nanoTime()));
        return hookId;
    }

    /**
     * Uninstall a hook by id, reverting the target class's bytecode. Calls
     * {@code transformer.reset(inst, RETRANSFORMATION)}, which re-runs
     * retransformClasses on the target; still-registered transformers (other
     * hooks) re-apply, so ONLY this hook's advice is removed. Also deregisters
     * the route from {@link HookBridge}.
     *
     * @param hookId the hookId returned by {@link #install}
     * @return true if the hook existed and was successfully reverted, false if
     *         the hookId was unknown or the transformer returned false (class
     *         no longer modifiable, etc.)
     */
    public synchronized boolean uninstall(String hookId) {
        HookRecord r = hooks.get(hookId);
        if (r == null) {
            return false;
        }
        boolean reverted = r.transformer().reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        if (reverted) {
            hooks.remove(hookId);
            HookBridge.unregisterRoute(r.routeKey());
        }
        return reverted;
    }

    /**
     * Snapshot of all currently-installed hooks, for list_hooks tool. The
     * returned list is a copy; mutating it does not affect the live hooks.
     */
    public List<HookRecord> list() {
        return List.copyOf(hooks.values());
    }

    /** Number of currently-installed hooks. */
    public int size() {
        return hooks.size();
    }

    // ---- HookSource: expose dynamic hooks to the aggregate list_hooks tool ----

    @Override
    public List<HookInfo> hooks() {
        List<HookInfo> out = new ArrayList<>();
        for (HookRecord r : hooks.values()) {
            out.add(new HookInfo(r.targetClass(), r.method(),
                    GenericEntryAdvice.class.getName(), "bytebuddy-advice-retransform", true));
        }
        return out;
    }

    @Override
    public String sourceName() {
        return "DynamicHookManager";
    }

    /**
     * ClassFileLocator for the given advice class's classloader. ByteBuddy
     * needs the advice bytecode to inline it into the target.
     */
    private static ClassFileLocator locator(Class<?> c) {
        return ClassFileLocator.ForClassLoader.of(c.getClassLoader());
    }
}
