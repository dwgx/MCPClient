package net.marcloud.mcp.core.hotload;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles Java source strings to {@code .class} bytecode entirely in memory,
 * using the JDK's built-in {@link JavaCompiler}. No files hit disk.
 *
 * <p>This is the front half of the hot-load pipeline: source in, bytecode out.
 * The bytecode is then either loaded as a brand-new class ({@link DynamicClassLoader})
 * or used to redefine an existing class ({@link Redefiner}).
 *
 * <p>Requires running on a JDK (not a bare JRE) so {@code jdk.compiler} is present;
 * the game runs on JBR 25, which includes it. Verified working on this runtime.
 */
public final class InMemoryCompiler {

    /** Compiler options; classpath is inherited from the running JVM by default. */
    private final List<String> options;

    public InMemoryCompiler() {
        // Compile against whatever the game JVM already has on its classpath, so
        // AI-authored code can reference Minecraft classes, guava, netty, etc.
        this.options = List.of("-classpath", System.getProperty("java.class.path"));
    }

    public InMemoryCompiler(List<String> options) {
        this.options = List.copyOf(options);
    }

    /** In-memory source unit. */
    private static final class SourceObject extends SimpleJavaFileObject {
        private final String code;
        SourceObject(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** In-memory class-file sink. */
    private static final class ClassObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ClassObject(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
        }
        @Override public OutputStream openOutputStream() {
            return bytes;
        }
        byte[] toBytes() {
            return bytes.toByteArray();
        }
    }

    /**
     * Compile a single named class.
     *
     * @param className fully-qualified name (must match the public type in {@code source})
     * @param source    the Java source text
     */
    public CompileResult compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, Map.of(),
                    List.of("No system Java compiler available (running on a JRE, not a JDK?)"));
        }

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager std = compiler.getStandardFileManager(diags, null, null);

        Map<String, ClassObject> outputs = new HashMap<>();
        JavaFileManager fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String name,
                                                       JavaFileObject.Kind kind, FileObject sibling) {
                ClassObject obj = new ClassObject(name);
                outputs.put(name, obj);
                return obj;
            }
        };

        List<JavaFileObject> units = List.of(new SourceObject(className, source));
        boolean ok = compiler.getTask(null, fm, diags, options, null, units).call();

        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            messages.add(formatDiagnostic(d));
        }

        Map<String, byte[]> bytecode = new HashMap<>();
        if (ok) {
            for (Map.Entry<String, ClassObject> e : outputs.entrySet()) {
                bytecode.put(e.getKey(), e.getValue().toBytes());
            }
        }
        return new CompileResult(ok, bytecode, messages);
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        String where = (d.getSource() != null)
                ? d.getSource().getName() + ":" + d.getLineNumber()
                : "<no source>";
        return d.getKind() + " " + where + " - " + d.getMessage(Locale.ROOT);
    }
}
