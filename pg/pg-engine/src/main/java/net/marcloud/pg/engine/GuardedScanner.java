package net.marcloud.pg.engine;

import net.marcloud.pg.Guarded;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Reads the {@link Guarded} marker out of raw class bytecode without loading the
 * class. The annotation is {@code CLASS}-retained, so it is present in the
 * {@code .class} file's {@code RuntimeInvisibleAnnotations} attribute and ASM
 * exposes it via {@code invisibleAnnotations}.
 *
 * <p>Detection is deliberately tolerant: a {@code @Guarded} on the type OR on any
 * member marks the whole class for hardening (method-level granularity is a
 * per-pass concern, not a scan concern). The level is the strongest level found.
 */
final class GuardedScanner {

    private static final String DESC = "Lnet/marcloud/pg/Guarded;";

    private GuardedScanner() {
    }

    /** The selected level if the class is {@code @Guarded} anywhere, else null. */
    static Guarded.Level scan(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

        Guarded.Level best = null;
        best = strongest(best, levelOf(cn.invisibleAnnotations));
        best = strongest(best, levelOf(cn.visibleAnnotations));
        if (cn.methods != null) {
            for (MethodNode m : cn.methods) {
                best = strongest(best, levelOf(m.invisibleAnnotations));
                best = strongest(best, levelOf(m.visibleAnnotations));
            }
        }
        return best;
    }

    private static Guarded.Level levelOf(List<AnnotationNode> anns) {
        if (anns == null) {
            return null;
        }
        for (AnnotationNode a : anns) {
            if (!DESC.equals(a.desc)) {
                continue;
            }
            // @Guarded present. Default level is STANDARD unless a value= is given.
            Guarded.Level lvl = Guarded.Level.STANDARD;
            if (a.values != null) {
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if ("value".equals(a.values.get(i))) {
                        Object v = a.values.get(i + 1);
                        // enum value is encoded as String[]{desc, constName}
                        if (v instanceof String[]) {
                            String constName = ((String[]) v)[1];
                            try {
                                lvl = Guarded.Level.valueOf(constName);
                            } catch (IllegalArgumentException ignored) {
                                // unknown enum const (version skew): keep STANDARD
                            }
                        }
                    }
                }
            }
            return lvl;
        }
        return null;
    }

    private static Guarded.Level strongest(Guarded.Level a, Guarded.Level b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (b.ordinal() > a.ordinal()) ? b : a;
    }
}
