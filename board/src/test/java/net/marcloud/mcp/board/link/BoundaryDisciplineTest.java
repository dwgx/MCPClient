package net.marcloud.mcp.board.link;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Manager;
import net.marcloud.mcp.board.chips.TickCounterChip;

import org.junit.Test;

/**
 * Fixes the "cross-subsystem seams only pass JDK types + micro-interfaces"
 * discipline (the Baritone {@code api}/{@code impl} interface-only layering
 * idea, applied to Board's reflection bridge).
 *
 * <p>The {@code link} sub-package ({@link McpLink}, {@link BoardPort}) plus the
 * neutral registry {@link Backplane} are the ONLY seam between Board and
 * mcp-core, and they talk purely by reflection (zero compile-time coupling). So
 * every type that shows up in their PUBLIC method signatures (and public fields)
 * must be safe to hand across that seam: a JDK type, a Board abstract/interface
 * type, a Board frozen-contract type, or the seam's own port types — never a
 * concrete {@code impl} class and never a type owned by another subsystem
 * (mcp-core, the client mapping packages, …). Leaking e.g. a
 * {@code net.marcloud.mcp.core.*} class or a concrete {@link Chip} subclass here
 * would silently reintroduce the coupling the peer design forbids.
 *
 * <p>This is a genuine regression guard, not a placeholder: {@link
 * #disciplinePredicateHasTeeth()} proves the classifier REJECTS a concrete
 * Board impl and a foreign-package type, so if a future signature starts
 * exposing such a type {@link #bridgeClassesExposeOnlyDisciplinedTypes()} turns
 * red with the exact offending {@code Class#member : type} listed.
 */
public class BoundaryDisciplineTest {

    /** The three classes that form the Board <-> mcp-core reflection seam. */
    private static final Class<?>[] BRIDGE = {McpLink.class, BoardPort.class, Backplane.class};

    private static final String BOARD_ROOT = "net.marcloud.mcp.board.";
    private static final String LINK_PKG = "net.marcloud.mcp.board.link.";

    /** Board's frozen framework contract (design doc 06 §7) — allowed to be final. */
    private static final Set<String> FROZEN = new HashSet<String>(Arrays.asList(
            "net.marcloud.mcp.board.Backplane",
            "net.marcloud.mcp.board.Board",
            "net.marcloud.mcp.board.Chip",
            "net.marcloud.mcp.board.Clock",
            "net.marcloud.mcp.board.Manager",
            "net.marcloud.mcp.board.Matrix",
            "net.marcloud.mcp.board.Signal",
            "net.marcloud.mcp.board.Trace"));

    /**
     * A type is allowed in a seam signature iff it is one of:
     * <ol>
     *   <li>a primitive / {@code void};</li>
     *   <li>a JDK type ({@code java.*} / {@code javax.*});</li>
     *   <li>a Board frozen-contract type;</li>
     *   <li>the seam's own port types (anything in {@code board.link});</li>
     *   <li>a Board {@code interface} or {@code abstract class} (a micro-interface,
     *       never a concrete impl).</li>
     * </ol>
     * Anything else — a concrete Board impl outside the seam, or ANY foreign
     * subsystem type — is a boundary violation.
     */
    private static boolean isDisciplined(Class<?> t) {
        while (t.isArray()) {
            t = t.getComponentType();
        }
        if (t.isPrimitive()) {
            return true; // includes void.class
        }
        String name = t.getName();
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            return true;
        }
        if (FROZEN.contains(name)) {
            return true;
        }
        if (name.startsWith(BOARD_ROOT)) {
            if (name.startsWith(LINK_PKG)) {
                return true; // the seam's own neutral port types
            }
            return t.isInterface() || Modifier.isAbstract(t.getModifiers());
        }
        return false; // foreign subsystem (core / client / …) or concrete Board impl
    }

    @Test
    public void bridgeClassesExposeOnlyDisciplinedTypes() {
        List<String> violations = new ArrayList<String>();
        for (Class<?> c : BRIDGE) {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod) || m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                if (!isDisciplined(m.getReturnType())) {
                    violations.add(c.getName() + "#" + m.getName() + " : return "
                            + m.getReturnType().getName());
                }
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (!isDisciplined(params[i])) {
                        violations.add(c.getName() + "#" + m.getName() + " : param" + i
                                + " " + params[i].getName());
                    }
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()
                        && !isDisciplined(f.getType())) {
                    violations.add(c.getName() + "#" + f.getName() + " : field "
                            + f.getType().getName());
                }
            }
        }
        assertTrue("Boundary discipline violated — these seam signatures leak a "
                + "concrete impl or a foreign-subsystem type: " + violations,
                violations.isEmpty());
    }

    @Test
    public void disciplinePredicateHasTeeth() {
        // Positive controls: the four allowed shapes must pass.
        assertTrue("primitive", isDisciplined(boolean.class));
        assertTrue("void", isDisciplined(void.class));
        assertTrue("jdk", isDisciplined(String.class));
        assertTrue("jdk Object", isDisciplined(Object.class));
        assertTrue("jdk List", isDisciplined(List.class));
        assertTrue("frozen contract (final is OK when frozen)", isDisciplined(Board.class));
        assertTrue("board abstract", isDisciplined(Chip.class));
        assertTrue("board interface", isDisciplined(Manager.class));
        assertTrue("seam's own port type", isDisciplined(BoardPort.class));

        // Negative controls: the classifier must REJECT the two leak shapes.
        assertFalse("concrete Board impl must be rejected (expose Chip, not the impl)",
                isDisciplined(TickCounterChip.class));
        assertFalse("foreign-subsystem type must be rejected",
                isDisciplined(org.junit.Test.class));
    }
}
