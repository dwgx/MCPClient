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
 *
 * <p><b>Image paths arrive unresolved.</b> qml4j resolves a QML <em>document's</em> imports against
 * its {@code baseDir} before asking, but an {@code Image.source} is passed through verbatim: a
 * scene at {@code dwm/Main.qml} referencing {@code icons/gamma.svg} produces a request for exactly
 * {@code icons/gamma.svg}, which is not where the resource lives. Measured, not assumed — the
 * first version of the icon path rendered nothing for this reason. So a relative miss is retried
 * against {@link #sceneBase}.
 */
final class ClasspathResources implements ResourceLoader {

    /** Prefix under which dwm's scenes and assets live in the jar. */
    private static final String ROOT = "";

    /**
     * Directory of the scene this loader serves, used to resolve relative asset paths.
     *
     * <p>Empty means "resolve nothing", which is the correct behaviour for a loader constructed
     * without a scene — it then only finds absolute paths, rather than guessing at a prefix.
     */
    private final String sceneBase;

    /** A loader that resolves relative assets against {@code sceneBase}, e.g. {@code "dwm"}. */
    ClasspathResources(String sceneBase) {
        this.sceneBase = sceneBase == null ? "" : sceneBase;
    }

    /** A loader with no scene context; only absolute resource paths resolve. */
    ClasspathResources() {
        this("");
    }

    /**
     * Separator introducing an SVG's tint, e.g. {@code icons/gamma.svg?ffffff}.
     *
     * <p>Carried in the path because that is the only channel qml4j gives a {@link ResourceLoader}
     * — it asks for a string and receives bytes, with no place for an {@code Image} to pass its
     * intended colour. A scene therefore encodes the colour in {@code source}, which also makes
     * each (icon, colour) pair its own cache entry for free.
     */
    static final char TINT_SEPARATOR = '?';

    @Override
    public byte[] load(String path) {
        if (path == null) {
            return null;
        }
        int cut = path.indexOf(TINT_SEPARATOR);
        String resource = cut < 0 ? path : path.substring(0, cut);
        String tint = cut < 0 ? null : path.substring(cut + 1);

        if (!SvgRaster.handles(resource)) {
            return resolve(resource);
        }
        // An SVG is answered as PNG bytes: qml4j's image decoder is Skia's BITMAP decoder and does
        // not understand SVG. See SvgRaster for why the interception lives here.
        byte[] svg = resolve(resource);
        if (svg == null) {
            return null;
        }
        return SvgRaster.toPng(SvgRaster.tint(svg, tint), path, SvgRaster.DEFAULT_EDGE);
    }

    /**
     * Read {@code resource} as given, falling back to the scene's directory.
     *
     * <p>Order matters: as-given first, so a path qml4j already resolved (every QML document)
     * is unaffected, and a scene-relative asset only pays a second lookup when the first misses.
     */
    private byte[] resolve(String resource) {
        byte[] direct = read(resource);
        if (direct != null || sceneBase.isEmpty() || resource == null || resource.startsWith("/")) {
            return direct;
        }
        return read(sceneBase + "/" + resource);
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
