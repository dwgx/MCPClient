import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assume;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * CONTRACT for how core's {@code *LiveIT} scaffolds are collected.
 *
 * <p>They used to be collected by no default goal at all: core had no failsafe
 * plugin, and surefire's default includes do not match {@code *IT.java}. Six files
 * and eight {@code @Test} methods sat unreachable, and the only way in was naming one
 * with {@code -Dtest=}, at which point each assume-skipped to BUILD SUCCESS. This
 * asserts the wiring that fixes that, reading the POM as XML (same
 * read-the-build-file technique as {@code net.marcloud.mcp.core.kd.BuildScriptContractTest}).
 *
 * <p>The load-bearing assertion is {@link #failsafeDeclaresNoLiteralArgLine()}. A
 * literal {@code <argLine>} element in the plugin configuration takes precedence over
 * the CLI {@code -DargLine}, so adding one — which looks like a harmless default, and
 * is exactly what {@code dwm/pom.xml} does for its GLFW concerns — would silently drop
 * the {@code -agentpath:} from {@code NativeDebugOpLiveIT}'s own documented command.
 * That is the one IT here that genuinely passes; the cost of the mistake is turning it
 * into an inexplicable failure while the POM still looks correct.
 */
public class FailsafeWiringContractTest {

    /** Surefire's cwd is the module dir (core/), so the POM is right here. */
    private static final Path POM = Path.of("pom.xml");

    private static final String FAILSAFE = "maven-failsafe-plugin";

    private static Element parsePom() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Namespace-unaware so getElementsByTagName matches the plain POM tag names.
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(POM.toFile()).getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    private static Element pomRoot() {
        Assume.assumeTrue("pom.xml not readable from " + POM.toAbsolutePath()
                + " (run from the core module dir)", Files.isReadable(POM));
        Element root = parsePom();
        assertNotNull("core/pom.xml must parse as XML", root);
        return root;
    }

    /** The {@code <plugin>} element declaring failsafe, or null when absent. */
    private static Element failsafePlugin(Element root) {
        NodeList plugins = root.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if (FAILSAFE.equals(firstText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    /** Text of the first descendant with this tag, or null. */
    private static String firstText(Element scope, String tag) {
        NodeList found = scope.getElementsByTagName(tag);
        return found.getLength() == 0 ? null : found.item(0).getTextContent().trim();
    }

    private static List<String> texts(Element scope, String tag) {
        List<String> out = new ArrayList<>();
        NodeList found = scope.getElementsByTagName(tag);
        for (int i = 0; i < found.getLength(); i++) {
            out.add(found.item(i).getTextContent().trim());
        }
        return out;
    }

    @Test
    public void failsafeIsDeclaredSoLiveItsAreCollectedByAGoal() {
        Element failsafe = failsafePlugin(pomRoot());
        assertNotNull("core must declare " + FAILSAFE + "; without it *IT.java matches no "
                + "default include and the LiveIT scaffolds are dead files", failsafe);
        assertEquals("pin the same failsafe version dwm already uses, so both modules "
                        + "collect ITs identically",
                "3.2.5", firstText(failsafe, "version"));
    }

    @Test
    public void failsafeBindsBothIntegrationTestAndVerify() {
        Element failsafe = failsafePlugin(pomRoot());
        assertNotNull(failsafe);
        List<String> goals = texts(failsafe, "goal");
        assertTrue("integration-test must be bound or nothing runs the ITs; got " + goals,
                goals.contains("integration-test"));
        assertTrue("verify must be bound too: integration-test alone RECORDS failures without "
                        + "breaking the build, which is the same silent-success trap in a new "
                        + "costume; got " + goals,
                goals.contains("verify"));
    }

    @Test
    public void skipItsComesFromAnOverridableProperty() {
        Element root = pomRoot();
        Element failsafe = failsafePlugin(root);
        assertNotNull(failsafe);
        String skip = firstText(failsafe, "skipITs");
        assertNotNull("failsafe must set skipITs; core's ITs need a live game and must not "
                + "run in an ordinary build", skip);
        assertTrue("skipITs must be a property reference so it can be flipped from the CLI, "
                        + "not a hardcoded true that no operator can override; got " + skip,
                skip.startsWith("${") && skip.endsWith("}"));

        String property = skip.substring(2, skip.length() - 1);
        assertEquals("the property must default to true so `mvn verify` stays quiet for anyone "
                        + "who did not ask for a live run",
                "true", firstText(root, property));
    }

    @Test
    public void failsafeDeclaresNoLiteralArgLine() {
        Element failsafe = failsafePlugin(pomRoot());
        assertNotNull(failsafe);
        assertEquals("failsafe must NOT hardcode <argLine>: a literal element overrides the CLI "
                        + "-DargLine, which would drop the -agentpath from "
                        + "NativeDebugOpLiveIT's documented command — the one IT here that can "
                        + "actually pass. Leave it unset so ${argLine} from the CLI lands, and "
                        + "use property indirection if a default ever becomes necessary.",
                0, failsafe.getElementsByTagName("argLine").getLength());
    }

    @Test
    public void failsafeDoesNotInheritDwmsGlfwFlags() {
        // dwm's failsafe config is the shape this copied, and it carries
        // -XstartOnFirstThread / -Djava.awt.headless for Skia on the AppKit main thread.
        // core's ITs open no window; importing those flags would be cargo cult, and
        // -XstartOnFirstThread in particular changes main-thread semantics for no reason.
        //
        // Asserted against the parsed plugin element, NOT a substring of the file:
        // getTextContent() concatenates only text nodes, so it skips XML comments. The
        // first cut of this test grepped the raw POM and went red on core's own comment
        // saying these flags are deliberately excluded — an assertion that fired at the
        // documentation of the decision it was guarding, which is worse than useless
        // because the obvious "fix" is to delete the explanation.
        Element failsafe = failsafePlugin(pomRoot());
        assertNotNull(failsafe);
        String config = failsafe.getTextContent();
        assertTrue("core's ITs open no window; -XstartOnFirstThread belongs to dwm's GLFW ITs, "
                        + "and it silently changes main-thread semantics; got " + config,
                !config.contains("-XstartOnFirstThread"));
        assertTrue("core's ITs start no AWT; java.awt.headless belongs to dwm's config; got "
                        + config,
                !config.contains("java.awt.headless"));
    }
}
