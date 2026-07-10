package net.marcloud.mcp.core.introspect;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.marcloud.mcp.core.agent.AgentAccess;
import net.marcloud.mcp.core.hook.HookInfo;
import net.marcloud.mcp.core.hook.HookSource;
import net.marcloud.mcp.core.security.ProtectedClasses;

/**
 * Read-only introspection service: lists loaded classes, describes their
 * structure via reflection, and finds methods across the JVM. Delegates to
 * {@link AgentAccess#instrumentation()} for the loaded-class snapshot (with a
 * fallback when the agent is absent), and consults {@link ProtectedClasses} to
 * flag security-core classes. Never exposes the raw Instrumentation, so it does
 * not widen the ungated-accessor hole.
 */
public final class IntrospectionService {

    /** L5 capability name: read-only introspection. */
    public static final String CAP = "CAP_INTROSPECT_READ";

    private static final int DEFAULT_LIST_LIMIT = 200;
    private static final int MAX_LIST_LIMIT = 2000;
    private static final int DEFAULT_FIND_LIMIT = 100;
    private static final int MAX_FIND_LIMIT = 1000;

    private final ClassLoader gameLoader;
    private final List<HookSource> hookSources;

    public IntrospectionService(ClassLoader gameLoader, List<HookSource> hookSources) {
        this.gameLoader = gameLoader;
        this.hookSources = hookSources;
    }

    /**
     * Lists loaded classes, optionally filtered by package prefix and name
     * substring. Results are capped at {@code limit} (default 200, hard cap
     * 2000). Returns total classes in snapshot, matched count, the shown list,
     * and the source (instrumentation or reflection-fallback).
     */
    public ClassListing listClasses(String packageFilter, String nameFilter, int limit) {
        Class<?>[] snapshot = loadedClasses();
        int total = snapshot.length;

        Stream<Class<?>> stream = Arrays.stream(snapshot);
        if (packageFilter != null && !packageFilter.isBlank()) {
            stream = stream.filter(c -> c.getName().startsWith(packageFilter));
        }
        if (nameFilter != null && !nameFilter.isBlank()) {
            String lowerName = nameFilter.toLowerCase();
            stream = stream.filter(c -> c.getName().toLowerCase().contains(lowerName));
        }

        List<Class<?>> matched = stream.sorted(Comparator.comparing(Class::getName)).toList();
        int matchedCount = matched.size();

        int cap = Math.min(limit > 0 ? limit : DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        List<ClassInfo> shown = matched.stream().limit(cap).map(this::toInfo).toList();

        String source = AgentAccess.isLoaded() ? "instrumentation" : "reflection-fallback";
        return new ClassListing(total, matchedCount, shown, source);
    }

    /**
     * Describes a class's structure: kind, modifiers, superclass, interfaces,
     * fields, methods, constructors, and flags. If the class is not already
     * loaded, attempts to resolve it without initialization (side-effect: the
     * class becomes loaded). On resolution failure, returns an unresolved
     * ClassDetail with the exception message.
     */
    public ClassDetail describeClass(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return unresolved(fqn, "class name is null or blank");
        }

        // Prefer the already-loaded class (no side effect).
        Class<?> c = findLoaded(fqn);
        boolean wasLoaded = c != null;
        String note = null;

        if (c == null) {
            // Not loaded yet; resolve without initialization (but still LOADS+LINKS).
            try {
                c = Class.forName(fqn, false, gameLoader);
                note = "was not loaded before this call; describe resolved it";
            } catch (ClassNotFoundException e) {
                return unresolved(fqn, "ClassNotFoundException: " + e.getMessage());
            } catch (LinkageError e) {
                return unresolved(fqn, "LinkageError: " + e.getMessage());
            } catch (Throwable t) {
                return unresolved(fqn, "unexpected: " + t);
            }
        }

        try {
            String kind = classKind(c);
            String modifiers = Modifier.toString(c.getModifiers());
            String superclass = c.getSuperclass() != null ? c.getSuperclass().getName() : null;
            List<String> interfaces = Arrays.stream(c.getInterfaces())
                    .map(Class::getName)
                    .toList();

            List<FieldInfo> fields = new ArrayList<>();
            List<MethodInfo> methods = new ArrayList<>();
            List<MethodInfo> constructors = new ArrayList<>();

            // Reflection can blow up on partially-linked classes; wrap each walk.
            try {
                for (Field f : c.getDeclaredFields()) {
                    fields.add(new FieldInfo(f.getName(), typeName(f.getType()),
                            Modifier.toString(f.getModifiers())));
                }
            } catch (Throwable t) {
                // partial: show what we got so far
            }

            try {
                for (Method m : c.getDeclaredMethods()) {
                    methods.add(toMethodInfo(m));
                }
            } catch (Throwable t) {
                // partial
            }

            try {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    constructors.add(toConstructorInfo(ctor));
                }
            } catch (Throwable t) {
                // partial
            }

            boolean jvmMod = jvmModifiable(c);
            boolean prot = ProtectedClasses.isProtected(fqn);

            return new ClassDetail(fqn, kind, modifiers, superclass, interfaces, fields,
                    methods, constructors, wasLoaded, jvmMod, prot, note);
        } catch (Throwable t) {
            return unresolved(fqn, "describe failed: " + t);
        }
    }

    /**
     * Finds methods across loaded classes by name, optionally filtering by
     * owner class name and parameter signature. Returns up to {@code limit}
     * results (default 100, cap 1000).
     */
    public List<MethodInfo> findMethod(String methodFilter, String ownerFilter,
                                        String paramFilter, int limit) {
        if (methodFilter == null || methodFilter.isBlank()) {
            return List.of();
        }

        String lowerMethod = methodFilter.toLowerCase();
        String lowerOwner = ownerFilter != null ? ownerFilter.toLowerCase() : null;
        String lowerParam = paramFilter != null ? paramFilter.toLowerCase() : null;

        int cap = Math.min(limit > 0 ? limit : DEFAULT_FIND_LIMIT, MAX_FIND_LIMIT);
        List<MethodInfo> results = new ArrayList<>();

        for (Class<?> c : loadedClasses()) {
            if (results.size() >= cap) {
                break;
            }
            String cname = c.getName();
            if (lowerOwner != null && !cname.toLowerCase().contains(lowerOwner)) {
                continue;
            }

            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().toLowerCase().contains(lowerMethod)) {
                        continue;
                    }
                    if (lowerParam != null) {
                        String simpleParams = Arrays.stream(m.getParameterTypes())
                                .map(Class::getSimpleName)
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");
                        String desc = descriptorOf(m);
                        if (!simpleParams.toLowerCase().contains(lowerParam)
                                && !desc.toLowerCase().contains(lowerParam)) {
                            continue;
                        }
                    }
                    results.add(toMethodInfo(m));
                    if (results.size() >= cap) {
                        break;
                    }
                }
            } catch (Throwable t) {
                // skip this class (NoClassDefFoundError on partially-linked classes is common)
            }
        }

        return results;
    }

    /** Lists all hooks from all hook sources. */
    public List<HookInfo> listHooks() {
        List<HookInfo> all = new ArrayList<>();
        for (HookSource src : hookSources) {
            try {
                all.addAll(src.hooks());
            } catch (Throwable t) {
                // skip failing source
            }
        }
        return all;
    }

    // --- helpers ---

    private Class<?>[] loadedClasses() {
        Instrumentation inst = AgentAccess.instrumentation();
        return inst != null ? inst.getAllLoadedClasses() : fallbackClasses();
    }

    private Class<?>[] fallbackClasses() {
        // Honest best-effort: JDK25 with strong encapsulation makes full
        // agent-less enumeration impossible. Return a curated seed.
        try {
            List<Class<?>> seed = new ArrayList<>();
            seed.add(Object.class);
            seed.add(String.class);
            seed.add(getClass());
            if (gameLoader != null) {
                seed.add(gameLoader.getClass());
            }
            return seed.toArray(new Class<?>[0]);
        } catch (Throwable t) {
            return new Class<?>[]{Object.class, String.class, getClass()};
        }
    }

    private Class<?> findLoaded(String fqn) {
        for (Class<?> c : loadedClasses()) {
            if (c.getName().equals(fqn)) {
                return c;
            }
        }
        return null;
    }

    private boolean jvmModifiable(Class<?> c) {
        Instrumentation inst = AgentAccess.instrumentation();
        return inst != null && inst.isModifiableClass(c);
    }

    private ClassInfo toInfo(Class<?> c) {
        String loader = c.getClassLoader() == null ? "<bootstrap>"
                : c.getClassLoader().getClass().getName();
        String module = c.getModule() != null ? c.getModule().getName() : null;
        return new ClassInfo(c.getName(), loader, module, true, jvmModifiable(c),
                ProtectedClasses.isProtected(c.getName()));
    }

    private MethodInfo toMethodInfo(Method m) {
        return new MethodInfo(m.getDeclaringClass().getName(), m.getName(),
                typeName(m.getReturnType()),
                Arrays.stream(m.getParameterTypes()).map(IntrospectionService::typeName).toList(),
                descriptorOf(m), Modifier.toString(m.getModifiers()));
    }

    private MethodInfo toConstructorInfo(Constructor<?> ctor) {
        return new MethodInfo(ctor.getDeclaringClass().getName(), "<init>", "void",
                Arrays.stream(ctor.getParameterTypes()).map(IntrospectionService::typeName).toList(),
                descriptorOfConstructor(ctor), Modifier.toString(ctor.getModifiers()));
    }

    private static String classKind(Class<?> c) {
        if (c.isAnnotation()) return "annotation";
        if (c.isInterface()) return "interface";
        if (c.isEnum()) return "enum";
        if (Modifier.isAbstract(c.getModifiers())) return "abstract-class";
        return "class";
    }

    private static ClassDetail unresolved(String name, String msg) {
        return new ClassDetail(name, "<unresolved>", "", null, List.of(), List.of(),
                List.of(), List.of(), false, false, false, msg);
    }

    public static String typeName(Class<?> c) {
        if (c.isArray()) {
            return typeName(c.getComponentType()) + "[]";
        }
        return c.getName();
    }

    public static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(desc(p));
        }
        sb.append(")");
        sb.append(desc(m.getReturnType()));
        return sb.toString();
    }

    private static String descriptorOfConstructor(Constructor<?> ctor) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : ctor.getParameterTypes()) {
            sb.append(desc(p));
        }
        sb.append(")V");
        return sb.toString();
    }

    private static String desc(Class<?> c) {
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c == void.class) return "V";
        if (c.isArray()) return "[" + desc(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
