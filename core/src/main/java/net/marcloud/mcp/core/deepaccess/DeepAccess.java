package net.marcloud.mcp.core.deepaccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.security.AccessGate;
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.security.Privilege;
import net.marcloud.mcp.core.security.ProtectedClasses;

/**
 * C5 MUTATE-STATE engine: read/write arbitrary JVM fields (incl private & final),
 * invoke private methods, open modules. VarHandle primary (fast, type-checked),
 * Unsafe fallback for final writes. Refuses {@link ProtectedClasses} on mutating
 * ops. Caches per-class Lookup and per-field VarHandle for performance.
 *
 * <p><b>MODULE STORY:</b> The game (Java-8 bytecode) and core (Java-25) run in
 * the SAME unnamed module of the app classloader (one -cp), so
 * {@code privateLookupIn(game-class, coreLookup)} succeeds with no --add-opens
 * against game classes (unnamed-to-unnamed is implicitly open). --add-opens
 * matters only for named platform modules (java.base internals), already granted
 * for Unsafe in jvm-args-mcp.txt.
 *
 * <p><b>HONEST UNSAFE LIMIT (JDK 25):</b> Unsafe writes the STORAGE of a final
 * field but cannot un-inline compile-time constants (static final
 * primitives/Strings with constant initializers) that javac/JIT already folded.
 * Those reads keep the old value at call sites. Only non-constant finals (final
 * Object, computed finals) mutate observably.
 *
 * <p><b>JMM LIMIT:</b> Final-field writes have NO happens-before guarantee; other
 * threads may never observe the change. Use only for single-threaded / dev-debug.
 */
public final class DeepAccess {

    /** Full-power lookup for privateLookupIn. */
    private static final MethodHandles.Lookup FULL = MethodHandles.lookup();

    private final GameAccess game;
    private final AccessGate gate;
    private final Supplier<java.lang.instrument.Instrumentation> instr;
    private final UnsafeAccess unsafe;

    // Cached per-class Lookup (privateLookupIn is expensive, cache it)
    private final ConcurrentHashMap<Class<?>, MethodHandles.Lookup> lookupCache = new ConcurrentHashMap<>();

    // Cached VarHandle per (class, fieldName, isStatic)
    private final ConcurrentHashMap<FieldKey, VarHandle> vhCache = new ConcurrentHashMap<>();

    // Cached Field (resolved+accessible) for Unsafe path
    private final ConcurrentHashMap<FieldKey, Field> reflectCache = new ConcurrentHashMap<>();

    private record FieldKey(Class<?> owner, String name, boolean isStatic) {
    }

    public DeepAccess(GameAccess game, AccessGate gate,
                      Supplier<java.lang.instrument.Instrumentation> instr) {
        this.game = game;
        this.gate = gate;
        this.instr = instr;
        this.unsafe = new UnsafeAccess();
    }

    // ===== FIELD READ =====

    /**
     * Read instance field {@code name} from target {@code t}. Walks hierarchy to
     * find the declaring class. VarHandle get works fine on finals.
     *
     * @throws DeepAccessException if field not found
     */
    public Object getField(Object t, String name) {
        gate.require(CapabilitySid.CAP_MEMORY_READ);
        if (t == null) {
            throw new DeepAccessException("target is null");
        }
        guardProtected(t.getClass());
        // Hierarchy walk may resolve to a superclass; guard the DECLARING class too,
        // symmetric with the write/invoke paths. Without this a non-protected
        // subclass could READ a scalar/String field declared on a protected security
        // class (MutateStateTools.isProtectedValue only inspects the returned value's
        // runtime type and would miss it).
        Field f = resolveField(t.getClass(), name);
        guardProtected(f.getDeclaringClass());
        try {
            VarHandle vh = findVarHandle(t.getClass(), name, false);
            return vh.get(t);
        } catch (Exception e) {
            throw new DeepAccessException("getField(" + t.getClass().getName() + "." + name + ") failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read static field {@code name} from class {@code owner}.
     */
    public Object getStaticField(Class<?> owner, String name) {
        gate.require(CapabilitySid.CAP_MEMORY_READ);
        guardProtected(owner);
        // Hierarchy walk may resolve to a superclass; guard the DECLARING class too,
        // symmetric with the write/invoke paths.
        Field f = resolveField(owner, name);
        guardProtected(f.getDeclaringClass());
        try {
            VarHandle vh = findVarHandle(owner, name, true);
            return vh.get();
        } catch (Exception e) {
            throw new DeepAccessException("getStaticField(" + owner.getName() + "." + name + ") failed: " + e.getMessage(), e);
        }
    }

    // ===== FIELD WRITE =====

    /**
     * Write instance field {@code name} on target {@code t} to {@code value}.
     * Non-final fields via VarHandle; final fields via Unsafe. Refuses protected
     * classes.
     *
     * @param roots RootResolver for coercion (if value contains $path)
     */
    public void setField(Object t, String name, Object value, RootResolver roots) {
        gate.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);
        if (t == null) {
            throw new DeepAccessException("target is null");
        }
        guardProtected(t.getClass());

        Field f = resolveField(t.getClass(), name);
        // Hierarchy walk may resolve to a superclass; guard the DECLARING class too
        // so a non-protected subclass cannot reach a protected super's field.
        guardProtected(f.getDeclaringClass());
        Object coerced = ValueCodec.coerce(f.getType(), value, roots);

        if (Modifier.isFinal(f.getModifiers())) {
            // final: use Unsafe (declaring class already guarded above)
            unsafe.putInstance(t, f, coerced);
        } else {
            // non-final: VarHandle
            try {
                VarHandle vh = findVarHandle(t.getClass(), name, false);
                vh.set(t, coerced);
            } catch (Exception e) {
                throw new DeepAccessException("setField VarHandle failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Write static field {@code name} on class {@code owner} to {@code value}.
     * All static writes go via Unsafe: VarHandle refuses static final, and routing
     * non-final statics through Unsafe too keeps one uniform choke point.
     */
    public void setStaticField(Class<?> owner, String name, Object value, RootResolver roots) {
        gate.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);
        guardProtected(owner);

        Field f = resolveField(owner, name);
        // Hierarchy walk may resolve to a superclass; guard the DECLARING class too.
        guardProtected(f.getDeclaringClass());
        Object coerced = ValueCodec.coerce(f.getType(), value, roots);

        // Static field write always uses Unsafe. (A prior VarHandle branch here was
        // dead: for any static field Modifier.isStatic(mods) is true, so the guard
        // condition was always satisfied and the VarHandle else-branch unreachable.)
        unsafe.putStatic(f, coerced);
    }

    // ===== METHOD INVOKE =====

    /**
     * Invoke instance method {@code methodName} on target {@code t} with given
     * param types and args. Walks hierarchy; matches name+arity if paramTypes null.
     * Refuses protected classes.
     */
    public Object invoke(Object t, String methodName, Class<?>[] paramTypes, Object[] args,
                         RootResolver roots) throws Throwable {
        gate.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);
        if (t == null) {
            throw new DeepAccessException("target is null");
        }
        guardProtected(t.getClass());

        Method m = resolveMethod(t.getClass(), methodName, paramTypes);
        // Hierarchy walk may resolve to a superclass method; guard the DECLARING class.
        guardProtected(m.getDeclaringClass());
        MethodHandles.Lookup lookup = lookupFor(t.getClass());
        MethodHandle mh = lookup.unreflect(m);

        Object[] coercedArgs = coerceArgs(m.getParameterTypes(), args, roots);
        // invokeWithArguments prepends receiver for instance methods
        return mh.invokeWithArguments(prependReceiver(t, coercedArgs));
    }

    /**
     * Invoke static method {@code methodName} on class {@code owner}.
     */
    public Object invokeStatic(Class<?> owner, String methodName, Class<?>[] paramTypes,
                               Object[] args, RootResolver roots) throws Throwable {
        gate.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);
        guardProtected(owner);

        Method m = resolveMethod(owner, methodName, paramTypes);
        // Hierarchy walk may resolve to a superclass method; guard the DECLARING class.
        guardProtected(m.getDeclaringClass());
        MethodHandles.Lookup lookup = lookupFor(owner);
        MethodHandle mh = lookup.unreflect(m);

        Object[] coercedArgs = coerceArgs(m.getParameterTypes(), args, roots);
        return mh.invokeWithArguments(coercedArgs);
    }

    // ===== MODULE OPEN =====

    /**
     * Open {@code pkg} of {@code target} module to core's module, via
     * Instrumentation.redefineModule. Enables privateLookupIn / reflection into
     * named platform modules.
     */
    public void openModule(Module target, String pkg) {
        gate.require(CapabilitySid.CAP_MEMORY_WRITE, Privilege.SE_DEBUG_CLASS);
        java.lang.instrument.Instrumentation inst = instr.get();
        if (inst == null) {
            throw new DeepAccessException("Instrumentation unavailable");
        }
        Module coreModule = getClass().getModule();
        try {
            inst.redefineModule(target,
                    java.util.Set.of(),
                    java.util.Map.of(),
                    java.util.Map.of(pkg, java.util.Set.of(coreModule)),
                    java.util.Set.of(),
                    java.util.Map.of());
        } catch (Exception e) {
            throw new DeepAccessException("openModule failed: " + e.getMessage(), e);
        }
    }

    // ===== CACHE INVALIDATION (for DCEVM redefine) =====

    /**
     * Invalidate cached Lookup/VarHandle/Field for class {@code c}. CRITICAL after
     * DCEVM structural redefine changes field offsets, so stale handles don't
     * write wrong addresses.
     */
    public void invalidate(Class<?> c) {
        lookupCache.remove(c);
        vhCache.keySet().removeIf(k -> k.owner.equals(c));
        reflectCache.keySet().removeIf(k -> k.owner.equals(c));
    }

    public void invalidateAll() {
        lookupCache.clear();
        vhCache.clear();
        reflectCache.clear();
    }

    // ===== INTERNAL HELPERS =====

    private MethodHandles.Lookup lookupFor(Class<?> c) {
        return lookupCache.computeIfAbsent(c, k -> {
            try {
                return MethodHandles.privateLookupIn(k, FULL);
            } catch (IllegalAccessException e) {
                throw new DeepAccessException("privateLookupIn(" + k.getName() + ") failed: " + e.getMessage(), e);
            }
        });
    }

    private VarHandle findVarHandle(Class<?> owner, String name, boolean isStatic) {
        FieldKey key = new FieldKey(owner, name, isStatic);
        return vhCache.computeIfAbsent(key, k -> {
            Field f = resolveField(owner, name);
            MethodHandles.Lookup lookup = lookupFor(owner);
            try {
                if (isStatic) {
                    return lookup.findStaticVarHandle(f.getDeclaringClass(), f.getName(), f.getType());
                } else {
                    return lookup.findVarHandle(f.getDeclaringClass(), f.getName(), f.getType());
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new DeepAccessException("findVarHandle(" + owner.getName() + "." + name + ") failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Resolve field {@code name} on {@code start}, walking up the superclass
     * hierarchy. Game fields often declared on superclasses (e.g. Entity.posX for
     * EntityPlayerSP). Returns the Field, made accessible.
     */
    private Field resolveField(Class<?> start, String name) {
        Class<?> current = start;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new DeepAccessException("field " + name + " not found in " + start.getName() + " or superclasses");
    }

    /**
     * Resolve method {@code name} with given param types (or match name+arity if
     * paramTypes null). Walks hierarchy.
     */
    private Method resolveMethod(Class<?> start, String name, Class<?>[] paramTypes) {
        Class<?> current = start;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (paramTypes == null) {
                    // match by arity only (first match wins)
                    m.setAccessible(true);
                    return m;
                }
                if (java.util.Arrays.equals(m.getParameterTypes(), paramTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        throw new DeepAccessException("method " + name + " not found in " + start.getName());
    }

    private void guardProtected(Class<?> c) {
        if (ProtectedClasses.isProtected(c.getName())) {
            throw new DeepAccessException("refusing to mutate protected class " + c.getName());
        }
    }

    private Object[] coerceArgs(Class<?>[] paramTypes, Object[] args, RootResolver roots) {
        if (args == null || args.length == 0) {
            return new Object[0];
        }
        Object[] coerced = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            coerced[i] = ValueCodec.coerce(paramTypes[i], args[i], roots);
        }
        return coerced;
    }

    private Object[] prependReceiver(Object receiver, Object[] args) {
        Object[] full = new Object[args.length + 1];
        full[0] = receiver;
        System.arraycopy(args, 0, full, 1, args.length);
        return full;
    }
}
