package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.CodeSource;
import java.util.List;
import java.util.TreeSet;

import net.marcloud.mcp.core.McpCore;
import net.marcloud.mcp.core.drivers.act.ActRuntime;
import net.marcloud.mcp.core.drivers.observe.ShelterTools;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.FltManager;
import net.marcloud.mcp.core.flt.seam.SeamController;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.io.transport.ToolContext;
import net.marcloud.mcp.core.io.transport.ToolRegistry;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ldr.LdrEngine;
import net.marcloud.mcp.core.ob.ObManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeToken;

import org.junit.Test;

/**
 * The shelter counter is REACHABLE, and both halves of "reachable" are asserted separately
 * because they fail for different reasons.
 *
 * <p><b>What went wrong, and why this file exists.</b> The wave-22 audit wrote the accumulator,
 * the tool, the layer row, the ring row and the capability row — five files — and the whole
 * suite stayed at 2025 tests with five failures that all said the same thing:
 * {@code night_shelter} was DECLARED in three tables and CONSTRUCTED in none.
 * {@code ShelterTools} and {@code NightShelter} existed in {@code core/src/main} and were
 * referenced by nothing in it. A declared-but-unbuilt capability is the exact failure shape this
 * project keeps cataloguing — it is why {@code McpCore} factored every registration into one
 * {@code registerBuiltins} — and it survived because no test asked the only question that
 * matters: <em>can a caller that is not this test's neighbour reach it?</em>
 *
 * <p><b>Three assertions, and their red sets are DISJOINT — which is the point.</b>
 *
 * <ul>
 *   <li>{@link #theAccumulatorAndItsToolAreShippedNotTestTreeClasses()} fails when the classes
 *       move DOWN into the test tree. Wiring them perfectly does not make it green again: a class
 *       nobody in {@code src/main} can name is a capability only tests can reach.</li>
 *   <li>{@link #theModelSurfaceCarriesTheShelterCounter()} fails when {@code McpCore} stops
 *       registering the provider. It is strictly stronger than the placement check, because the
 *       placement check passes just as happily on a class that exists and is never constructed —
 *       which is precisely the state this slice was handed. Five pre-existing tests caught the
 *       declaration but none caught the missing wiring; this one is the catch.</li>
 *   <li>{@link #thePlacementInstrumentCanTellTheTestTreeFromTheShippedTree()} is the
 *       non-vacuity half: it fails if the loader lookup the first assertion depends on stops
 *       distinguishing the two trees. Without it, a helper that had quietly stopped locating
 *       classes would leave the first assertion reporting "shipped, not test tree" about a class
 *       it never found.</li>
 * </ul>
 *
 * <p>The first two are guards over one property, and either can go red with the other still
 * green. That is why both are here rather than the placement assertion alone: placement cannot
 * tell you a {@code night_shelter} tool was BUILT, and the build is the half that was missing
 * when this slice was handed.
 *
 * <p><b>Both production states are driven.</b> L6 off is the shipped default and L6 on is the
 * other real state; a wiring that only fires in one of them is a wiring that does not ship.
 */
public final class TheShippedShelterCounterIsReachableAndNotATestTreeClassTest {

    /**
     * The three classes are LOADED out of the shipped artifact, not out of the test tree.
     *
     * <p><b>What this reads, and what it used to read.</b> It used to read
     * {@code getPackageName()}, and its javadoc claimed it read "the package the class was LOADED
     * from" — which is a different measurement, and the gap between the two is precisely the
     * event this guard exists to catch. Moving a file down from {@code core/src/main} into
     * {@code core/src/test} changes not one character of its package name, so the package
     * criterion was blind by construction: it stayed GREEN on the one mutation it was written to
     * detect. That is why it asks the classloader where the BYTES came from — the same shape
     * {@code TheSuiteGoesRedThroughTheSeamTest.theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree}
     * settled on — so a file that merely sits in the right package can no longer satisfy it.
     *
     * <p><b>Two criteria, two disjoint failure classes, both kept.</b> "Wrong tree" and "wrong
     * package" are separate defects and collapsing them into one leaves one of them invisible: a
     * class can sit in {@code target/classes} under a {@code .../test/} package (renamed
     * package, still shipped — the package criterion goes red, the tree criterion stays green),
     * or sit in {@code target/test-classes} under a perfectly main-looking package (moved tree,
     * unchanged package — the tree criterion goes red, the package criterion stays green). Each
     * is asserted, and each has been measured going red with the other still green.
     */
    @Test
    public void theAccumulatorAndItsToolAreShippedNotTestTreeClasses() {
        assertLoadedFromShippedTree(NightShelter.class, "net.marcloud.mcp.core.eval");
        assertLoadedFromShippedTree(ShelterTools.class, "net.marcloud.mcp.core.drivers.observe");
        assertLoadedFromShippedTree(ClientBody.class, "net.marcloud.mcp.core.eval");
    }

    /**
     * One class, two criteria: the tree it was loaded from, and the package it answers to.
     *
     * <p>The second assertion is not a restatement of the first. They were measured separately:
     * relocating the class file into {@code target/test-classes} under its own package reddens
     * the first and leaves the second green, and re-packaging it under a different package in a
     * shipped directory does the reverse. Keeping only one would have left half of that pair
     * uncaught, which is the mistake this file was corrected for.
     */
    private static void assertLoadedFromShippedTree(Class<?> type, String pkg) {
        String where = loadedFrom(type).replace('\\', '/');
        String simple = type.getSimpleName();

        assertFalse(simple + " was loaded out of the TEST tree (" + where + "): a capability only"
                + " tests can reach is the defect this file exists to close, and its package name"
                + " would not have changed by one character if it had been moved there",
                where.endsWith("test-classes") || where.contains("/test-classes/"));
        assertTrue(simple + " must be loaded out of a build output directory, or the line above is"
                + " measuring an absence of information rather than a location. The classloader"
                + " reported: " + where,
                where.contains("/classes/"));
        assertEquals(simple + " must also still answer to " + pkg + ", which is the package its"
                + " callers import; a move between packages is a different defect from a move"
                + " between trees and neither assertion subsumes the other",
                pkg, type.getPackageName());
    }

    /** Where the classloader actually got this class's bytes. */
    private static String loadedFrom(Class<?> type) {
        CodeSource cs = type.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) {
            return "<no code source: the classloader published none, so this guard cannot tell"
                    + " which tree it came from and must not be read as having proved it>";
        }
        return cs.getLocation().toString();
    }

    /**
     * The instrument has to be able to see the failure it is looking for.
     *
     * <p><b>Why a guard needs this and it is not paranoia.</b> {@link #loadedFrom} is a helper,
     * and a helper that quietly returned a constant, a trimmed path or a cached first answer
     * would leave every assertion above it GREEN while measuring nothing at all — the test would
     * report "shipped, not test tree" about a class it never located. So the same helper is
     * pointed at a class that provably IS in the test tree: this one. It loads out of
     * {@code target/test-classes} while {@link NightShelter} loads out of {@code target/classes},
     * so the two answers differ, and the string the helper returns is shown to contain the very
     * marker the guard above rejects.
     *
     * <p>This is also the in-suite half of the mutation measurement: the pair
     * (this class in {@code test-classes}, {@link NightShelter} in {@code classes}) is what makes
     * "the guard distinguishes the two trees" a fact about this run rather than a claim about the
     * helper's shape.
     */
    @Test
    public void thePlacementInstrumentCanTellTheTestTreeFromTheShippedTree() {
        String where = loadedFrom(getClass()).replace('\\', '/');
        String shipped = loadedFrom(NightShelter.class).replace('\\', '/');

        assertTrue("this guard is only meaningful if the classloader tells the two trees apart,"
                + " and this class is the test-tree half of that pair. It reported: " + where,
                where.contains("/test-classes/") || where.endsWith("test-classes"));
        assertTrue("and NightShelter is the shipped half, or there is no contrast to measure:"
                + " " + shipped,
                shipped.contains("/classes/") && !shipped.contains("test-classes"));
        assertFalse("and the two answers must differ, or the guard above cannot be distinguishing"
                + " anything: test-tree=" + where + " shipped=" + shipped,
                where.equals(shipped));
    }

    /**
     * Driving production's own {@code McpCore.registerBuiltins} puts {@code night_shelter} on
     * the MODEL-FACING surface.
     *
     * <p>{@code modelSurface()} and not the audited registry: the audited registry holds every
     * built-in including kernel-layered names, and the question a caller actually has is "what
     * can the model reach". {@code ToolRegistry.layerOf} classifies this one GAME, so it belongs
     * on the surface — and this assertion would also go red if someone declared it kernel-layered
     * by mistake, which is a different defect with the same surface symptom.
     *
     * <p>The non-vacuity guards are deliberate: a surface that had silently lost its other
     * providers would still be a surface, so two sentinels from different provider families are
     * named alongside it, and the kernel-only names are checked for absence so this cannot be
     * satisfied by a registry that simply holds everything.
     */
    @Test
    public void theModelSurfaceCarriesTheShelterCounter() {
        for (boolean l6 : new boolean[] {false, true}) {
            EventBus bus = new EventBus();
            IoSupervisor exec = new IoSupervisor(4, 2000L);
            try {
                SeLocalMonitor engine = new SeLocalMonitor(
                        new SeClearancePolicy(Ring.R_MINUS_1, "tok"), SeToken.wideOpen());
                IoManager audited = new IoManager(exec, engine);
                McpCore core = new McpCore();
                core.registerBuiltins(audited, engine,
                        l6 ? new ObManager(null, 8, 60_000L) : null,
                        new ToolContext(null, null, null, null, null),
                        new LdrEngine(getClass().getClassLoader()),
                        new FltManager(bus), new FltDynamicManager(null, bus),
                        new SeamController(bus, core.gameAccess()),
                        ActRuntime.INSTANCE);
                List<String> surface = core.modelSurface().names();
                String state = l6 ? "L6-wired" : "L6-off";
                assertTrue("night_shelter must be reachable from the MODEL-FACING surface ("
                                + state + "): a tool registered nowhere is a tool that does not"
                                + " exist. Registered: " + new TreeSet<>(surface),
                        surface.contains("night_shelter"));
                assertTrue("and the surface must still be real — a near-empty one would satisfy"
                                + " the line above: " + new TreeSet<>(surface),
                        surface.contains("chat_read") && surface.contains("world_view"));
                assertFalse("night_shelter is declared GAME, so the kernel-only names must not"
                                + " have leaked onto the surface: " + new TreeSet<>(surface),
                        ToolRegistry.kernelLayeredNames().stream().anyMatch(surface::contains));
                assertFalse("and it must be classified GAME, not defaulted: ToolRegistry's table"
                                + " is deny-by-default, so an unclassified name would be hidden",
                        ToolRegistry.isKernelLayered("night_shelter"));
            } finally {
                exec.shutdown();
            }
        }
    }
}
