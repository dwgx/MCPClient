package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

import io.github.timer_err.qml4j.render.QmlView;

/**
 * Pins qml4j as the DWM substrate at the published Central release we actually resolved.
 *
 * <p>A pom property can drift from the jar on the test classpath, and a QML property can
 * compile against an engine that silently ignores it. Both happened: {@code FluentWindow}
 * already asked for {@code topRightRadius} on the close button, but qml4j 0.2.24 stored
 * the four corner radii and only the MultiEffect mask path read them — the plate stayed
 * square. 0.2.27 (upstream PR #15) is the first release that paints them. Reverting the
 * pin to 0.2.24 makes {@link #runtimeJarIsThePinnedRelease()} fail; dropping the QML
 * property makes {@link #fluentWindowAsksForIndependentCornerRadii()} fail.
 */
public class Qml4jSubstrateVersionTest {

    static final String REQUIRED = "0.2.27";

    /**
     * The jar Maven actually put on the classpath, not the property we wish we had.
     *
     * <p>Fails on 0.2.24 because that artifact's filename does not contain {@code 0.2.27}.
     */
    @Test
    public void runtimeJarIsThePinnedRelease() {
        URL loc = QmlView.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull("QmlView must resolve from a jar or classes directory", loc);
        String path = loc.toString();
        assertTrue("dwm consumes qml4j-core " + REQUIRED
                + " from Maven Central (FluentWindow.topRightRadius is a silent no-op before "
                + "that). Classpath was: " + path,
                path.contains("qml4j-core-" + REQUIRED));
    }

    /**
     * The close button sits on the window's top-right corner and must round only that corner.
     * A uniform {@code radius} would also round the inner edge, which is the usual tell of a
     * hand-built caption bar.
     */
    @Test
    public void fluentWindowAsksForIndependentCornerRadii() {
        String src = ClasspathResources.readText("dwm/FluentWindow.qml");
        assertNotNull("FluentWindow.qml must ship on the classpath", src);
        assertTrue("close button must set topRightRadius so the red plate follows the "
                + "window corner; a uniform radius would round the inner edge too",
                src.contains("topRightRadius:"));
    }

    /**
     * The pom property is the one-line follow-upstream knob. If it disagrees with
     * {@link #REQUIRED}, someone bumped one and not the other.
     */
    @Test
    public void pomPinsTheSameRelease() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
        assertTrue("dwm/pom.xml must pin <qml4j.version>" + REQUIRED + "</qml4j.version>",
                pom.contains("<qml4j.version>" + REQUIRED + "</qml4j.version>"));
    }
}
