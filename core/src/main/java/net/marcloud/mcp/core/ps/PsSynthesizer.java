package net.marcloud.mcp.core.ps;

import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;

import net.marcloud.mcp.core.ldr.CompileResult;
import net.marcloud.mcp.core.ldr.InMemoryCompiler;

/**
 * C7 SYNTHESIZE: compile AI Java and define it as a <b>hidden</b> class via
 * {@code Lookup.defineHiddenClass(bytes, true)} (GC-able, no STRONG), reflectively
 * invoke its {@code public String handle(Map<String,Object>)}, return the result,
 * and drop all references so the class is unloaded. Throwaway counterpart to
 * {@code create_tool}: nothing is registered, nothing is archived, invisible to
 * C1 INTROSPECT, can never be redefined (hidden classes are unmodifiable).
 *
 * <p>The Lookup must be in THIS package because {@code defineHiddenClass} mandates
 * the defined class share the lookup class's package (verified). AI callers must
 * declare {@code package net.marcloud.mcp.core.synth;} in their source.
 *
 * <p><b>Containment ≠ sandbox:</b> hidden+GC-able only removes persistence,
 * name-visibility, and redefinability. The code inside handle() has the exact
 * power of eval_java — it can crash the game, call Unsafe, redefine classes.
 * R-1 gating is the only real control.
 */
public final class PsSynthesizer {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final String REQUIRED_PACKAGE = "net.marcloud.mcp.core.ps";

    private final InMemoryCompiler compiler;

    public PsSynthesizer() {
        this.compiler = new InMemoryCompiler();
    }

    /** Result of an eval attempt: success/message, plus a weak ref to the hidden class. */
    public record EvalResult(boolean success, String message, String output,
                             WeakReference<Class<?>> hiddenClass) {
    }

    /**
     * Compile {@code source} (must be in package net.marcloud.mcp.core.synth and
     * have a public String handle(Map<String,Object>)), define it as a hidden class,
     * invoke handle(args), and return the result. The class becomes GC-able once
     * this method returns.
     *
     * @param className fully-qualified name (must start with net.marcloud.mcp.core.synth.)
     * @param source    full Java source
     * @param args      arguments passed to handle()
     * @return success/error result + weak ref to the hidden class (for tests)
     */
    public EvalResult eval(String className, String source, Map<String, Object> args) {
        // 1. Package constraint check
        if (!className.startsWith(REQUIRED_PACKAGE + ".")) {
            return new EvalResult(false,
                    "className must be in package " + REQUIRED_PACKAGE + " (got " + className + ")",
                    null, null);
        }

        // 2. Compile
        CompileResult cr = compiler.compile(className, source);
        if (!cr.success()) {
            return new EvalResult(false, "compile failed:\n" + cr.diagnosticsText(), null, null);
        }

        byte[] bytes = cr.bytecode().get(className);
        if (bytes == null) {
            return new EvalResult(false,
                    "compiled output did not contain " + className, null, null);
        }

        // 3. Define hidden class (initialize=true, so <clinit> runs now)
        Class<?> hiddenClass;
        try {
            MethodHandles.Lookup.ClassOption[] options = {
                    // NO STRONG option -> class is GC-able once all refs drop
            };
            hiddenClass = LOOKUP.defineHiddenClass(bytes, true, options).lookupClass();
        } catch (IllegalAccessException | LinkageError e) {
            return new EvalResult(false, "defineHiddenClass failed: " + e.getMessage(), null, null);
        }

        // 4. Reflectively invoke handle(Map)
        try {
            Method handleMethod = hiddenClass.getMethod("handle", Map.class);
            Object instance = hiddenClass.getDeclaredConstructor().newInstance();
            String output = (String) handleMethod.invoke(instance, args);
            return new EvalResult(true, "eval succeeded", output,
                    new WeakReference<>(hiddenClass));
        } catch (NoSuchMethodException e) {
            return new EvalResult(false,
                    "class must have public String handle(java.util.Map<String,Object>)", null,
                    new WeakReference<>(hiddenClass));
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap the actual thrown exception
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.toString() : e.toString();
            return new EvalResult(false, "eval threw: " + msg, null,
                    new WeakReference<>(hiddenClass));
        } catch (Throwable e) {
            return new EvalResult(false, "eval threw: " + e.getMessage(), null,
                    new WeakReference<>(hiddenClass));
        }
    }
}
