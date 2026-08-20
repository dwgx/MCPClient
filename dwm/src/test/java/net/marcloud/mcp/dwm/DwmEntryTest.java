package net.marcloud.mcp.dwm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * Guards the two structural promises {@link DwmEntry} makes, both of which are easy to break by
 * accident with a single convenient import.
 */
public class DwmEntryTest {

    /**
     * dwm must import no {@code core} class — it is an auxiliary layer with zero security
     * power, and a compile-time edge to the kernel would silently end that.
     *
     * <p>Checked over the whole module's sources rather than just this class, since the rule is
     * module-wide and any file could violate it.
     */
    @Test
    public void noSourceImportsCore() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java");
        assertTrue("dwm sources must exist at " + root.toAbsolutePath(),
            java.nio.file.Files.isDirectory(root));

        final StringBuilder offenders = new StringBuilder();
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                if (!file.toString().endsWith(".java")) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                for (String line : java.nio.file.Files.readAllLines(file,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (line.startsWith("import net.marcloud.mcp.core")) {
                        offenders.append("\n  ").append(file).append(": ").append(line.trim());
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });

        assertTrue("dwm must import no core class (it is a detachable auxiliary with zero "
            + "security power); found:" + offenders, offenders.length() == 0);
    }

    /**
     * The backend must be named as a string, not linked.
     *
     * <p>If DwmEntry referenced {@code QmlGuiScreen} directly, loading DwmEntry would require
     * qml4j and Skija on the classpath, and asking "is there a UI?" would fail with
     * NoClassDefFoundError instead of answering false. Keeping the reference a String constant
     * is what makes absence a normal condition for a detachable module.
     */
    @Test
    public void backendIsReferencedByNameOnly() throws Exception {
        Field f = DwmEntry.class.getDeclaredField("QML_SCREEN");
        f.setAccessible(true);
        assertTrue("QML_SCREEN must be static", Modifier.isStatic(f.getModifiers()));
        assertNotNull("QML_SCREEN must name the backend", f.get(null));
        assertTrue("QML_SCREEN must be a String, so DwmEntry does not link the backend",
            f.get(null) instanceof String);

        for (Field field : DwmEntry.class.getDeclaredFields()) {
            assertFalse("DwmEntry must not hold a qml4j/Skija typed field, or it links the "
                + "backend: " + field, field.getType().getName().startsWith("io.github."));
        }
    }

    /**
     * Asking for availability must never throw, whatever is or is not on the classpath. This is
     * the call Board makes through the Backplane, and it has to be safe to make blind.
     */
    @Test
    public void isAvailableNeverThrows() {
        DwmEntry.isAvailable();
    }

    /**
     * The default scene path must point at a resource that actually ships, or the UI opens to a
     * load failure at runtime rather than at build time.
     */
    @Test
    public void defaultSceneResourceExists() throws Exception {
        Field f = DwmEntry.class.getDeclaredField("DEFAULT_SCENE");
        f.setAccessible(true);
        String path = (String) f.get(null);
        assertTrue("default scene " + path + " must exist under src/main/resources",
            java.nio.file.Files.isRegularFile(
                java.nio.file.Paths.get("src/main/resources").resolve(path)));
    }
}
