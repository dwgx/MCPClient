package net.marcloud.mcp.dwm.qml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.timer_err.qml4j.render.ResourceLoader;

/**
 * qml4j {@link ResourceLoader} over dwm's own classpath resources.
 *
 * <p>qml4j resolves a document's imports and assets through this, so scenes can be split across
 * files and reference images while everything still ships inside the dwm jar — no filesystem
 * layout to install and nothing to find at runtime.
 *
 * <p>Loading deliberately returns null rather than throwing on a miss: qml4j probes for optional
 * resources (a {@code qmldir} beside a document, for instance), so absence is routine.
 */
final class ClasspathResources implements ResourceLoader {

    /** Prefix under which dwm's scenes and assets live in the jar. */
    private static final String ROOT = "";

    @Override
    public byte[] load(String path) {
        return read(path);
    }

    /** Resource text as UTF-8, or null when absent. */
    static String readText(String path) {
        byte[] bytes = read(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The directory part of a resource path, which qml4j needs as {@code baseDir} so a
     * document's relative imports resolve against its own location.
     *
     * @return e.g. {@code "dwm"} for {@code "dwm/Main.qml"}, or {@code ""} at the root
     */
    static String baseDirOf(String path) {
        if (path == null) {
            return "";
        }
        int cut = path.lastIndexOf('/');
        return cut <= 0 ? "" : path.substring(0, cut);
    }

    private static byte[] read(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String key = ROOT + (path.startsWith("/") ? path.substring(1) : path);
        ClassLoader cl = ClasspathResources.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(key)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            System.err.println("[dwm] could not read resource " + key + ": " + e);
            return null;
        }
    }
}
