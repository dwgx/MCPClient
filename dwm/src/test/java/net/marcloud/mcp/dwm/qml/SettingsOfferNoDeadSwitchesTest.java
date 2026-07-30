package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The settings page must not offer a switch that changes nothing.
 *
 * <p>This exists because it happened. The page shipped three checkboxes — 平滑滚动, 菜单动画 and
 * 菜单渐隐 — each writing a {@code Motion} flag that no control in the loaded scene ever read:
 * scrolling goes through qml4j's own smoothing, which does not consult the policy, and this shell
 * has no menu at all (MenuPanel/MenuItem live in a scene the shell does not load). Flipping any of
 * them did nothing observable.
 *
 * <p>That is worse than a disabled control, and it is the very defect PageSettings already guards
 * against in the other direction: its rows carry {@code enabled:} bindings specifically so the UI
 * does not OFFER a choice that cannot take effect. Windows draws the same line — Performance
 * Options lists the effects the machine can actually apply. A switch that is present, enabled, and
 * inert tells the user a lie that no amount of correct metrics makes up for.
 *
 * <p><b>Source-level on purpose.</b> The live suite loads {@code MotionPolicy.qml}, a test fixture,
 * so it asserts the policy graph COMPUTES correctly and can say nothing about what the shipped page
 * offers. This runs headless on every build instead, which is what the regression needs: the
 * failure mode is a well-meaning author adding a row for a flag before anything consumes it.
 *
 * <p>Deliberately NOT a check on {@code Motion.qml} itself, which keeps every flag on purpose: it
 * models {@code SystemParametersInfo}, not this page's current contents. The rule is only that the
 * PAGE may not expose a flag that has no consumer.
 */
public class SettingsOfferNoDeadSwitchesTest {

    private static final Path SCENES = Paths.get("src/main/resources/dwm");
    private static final Path SETTINGS_PAGE = SCENES.resolve("pages/PageSettings.qml");
    private static final Path POLICY = SCENES.resolve("Motion.qml");

    /** {@code Motion.<name> = <something>} — a write, i.e. the page offering control of a flag. */
    private static final Pattern WRITES =
            Pattern.compile("Motion\\.([A-Za-z][A-Za-z0-9]*)\\s*=");
    /** Any {@code Motion.<name>} mention, used to find who READS a flag. */
    private static final Pattern MENTIONS =
            Pattern.compile("Motion\\.([A-Za-z][A-Za-z0-9]*)");
    /** {@code readonly property bool animateX: uiEffects && ...} — the derived gates. */
    private static final Pattern EFFECTIVE = Pattern.compile(
            "readonly\\s+property\\s+bool\\s+([A-Za-z][A-Za-z0-9]*)\\s*:([^\\n]*)");

    /**
     * Every flag the settings page writes must reach a control that reads it.
     *
     * <p>"Reaches" allows one hop through the policy's own derived gates, since that indirection is
     * the whole point of the layer: writing {@code comboBoxAnimation} is honoured by a control
     * reading {@code animateExpand}, and demanding a direct read would reject the correct design.
     */
    @Test
    public void everySwitchOnTheSettingsPageReachesAControlThatReadsIt() throws Exception {
        String page = read(SETTINGS_PAGE);

        Set<String> offered = new LinkedHashSet<String>();
        Matcher m = WRITES.matcher(page);
        while (m.find()) {
            offered.add(m.group(1));
        }
        assertTrue("the settings page must still offer some animation switches, or this test has "
            + "stopped guarding anything", !offered.isEmpty());

        // Who reads what, across every shipped scene EXCEPT the page itself: a row that only greys
        // out its own child does not make the flag effective, which is exactly how 菜单动画 looked
        // honoured while nothing consumed it.
        Set<String> readByControls = mentionsOutsideTheSettingsPage();
        // One permitted hop: a derived gate that is itself read counts as a reader of its inputs.
        Set<String> reachable = new LinkedHashSet<String>(readByControls);
        for (String gate : readByControls) {
            reachable.addAll(inputsOfEffectiveGate(gate));
        }

        List<String> dead = new ArrayList<String>();
        for (String flag : offered) {
            if (!reachable.contains(flag)) {
                dead.add(flag);
            }
        }

        assertTrue("the settings page offers switch(es) that no control reads, so flipping them "
            + "does nothing the user can see: " + dead + ". Either wire a control to the flag (read "
            + "it, or read a Motion gate derived from it) or take the row out until something does "
            + "-- a present-but-inert switch is worse than an absent one, and PageSettings' own "
            + "`enabled:` bindings exist to avoid exactly this. Flags actually reachable: "
            + reachable, dead.isEmpty());
    }

    /**
     * The master switch must stay effective, so the group it heads is not decorative either.
     *
     * <p>Asserted separately because {@code uiEffects} is reachable in a way no other flag is: it is
     * an input to every derived gate. If it ever stopped being one, the whole page would keep
     * looking correct while the master did nothing.
     */
    @Test
    public void theMasterSwitchStillGatesTheEffectsThatAreConsumed() throws Exception {
        String policy = read(POLICY);
        Set<String> consumed = mentionsOutsideTheSettingsPage();

        List<String> gatesRead = new ArrayList<String>();
        Matcher m = EFFECTIVE.matcher(policy);
        while (m.find()) {
            if (consumed.contains(m.group(1))) {
                gatesRead.add(m.group(1));
                assertTrue("Motion." + m.group(1) + " is read by a control, so it must remain "
                    + "subordinate to the uiEffects master -- otherwise turning effects off leaves "
                    + "it animating and the master is a lie: " + m.group(2).trim(),
                    m.group(2).contains("uiEffects"));
            }
        }
        assertTrue("at least one derived Motion gate must be consumed by a control, or the policy "
            + "layer is entirely decorative", !gatesRead.isEmpty());
    }

    /** Names mentioned as {@code Motion.<name>} anywhere under dwm/ except the settings page. */
    private static Set<String> mentionsOutsideTheSettingsPage() throws IOException {
        final Set<String> found = new LinkedHashSet<String>();
        Files.walkFileTree(SCENES, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".qml")
                        || file.endsWith(SETTINGS_PAGE.getFileName())
                        || file.endsWith(POLICY.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                Matcher m = MENTIONS.matcher(stripComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                while (m.find()) {
                    found.add(m.group(1));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    /** The flag names a derived gate is built from, e.g. animateExpand -> uiEffects, comboBox... */
    private static Set<String> inputsOfEffectiveGate(String gate) throws IOException {
        Set<String> inputs = new LinkedHashSet<String>();
        Matcher m = EFFECTIVE.matcher(read(POLICY));
        while (m.find()) {
            if (!m.group(1).equals(gate)) {
                continue;
            }
            Matcher w = Pattern.compile("[A-Za-z][A-Za-z0-9]*").matcher(m.group(2));
            while (w.find()) {
                inputs.add(w.group());
            }
        }
        return inputs;
    }

    /**
     * Strip {@code //} comments before looking for readers.
     *
     * <p>Load-bearing: the removal of the dead switches left a comment naming all three flags, and
     * counting a mention in prose as a consumer would make this test pass on the very state it
     * exists to reject.
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            int slashes = line.indexOf("//");
            out.append(slashes >= 0 ? line.substring(0, slashes) : line).append('\n');
        }
        return out.toString();
    }

    private static String read(Path path) throws IOException {
        assertTrue(path + " must exist", Files.isRegularFile(path));
        return stripComments(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
}
