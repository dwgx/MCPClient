package net.marcloud.mcp.core.se;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.kd.DebugTools;

/**
 * One implementation of the built-in gate-coverage check, called from BOTH production
 * startup and the test suite — so the runtime diagnostic and the CI gate can never
 * disagree about what "covered" means.
 *
 * <p><b>The gap this exists to close.</b> A tool's requirement is composed by NAME from three
 * tables: {@link Ring} BUILTIN_RINGS (L2), {@link SeToolRequirement} L3_WRITES (L3) and L4_PRIVILEGE
 * (L4). A name in none of them is enforced at the R3 fallback with no L3/L4 gate, while every
 * visible surface reports the ring it was REGISTERED with. Before
 * {@code McpCore.registerBuiltins(...)} became the single production registration site, the
 * inventory was hand-copied into the test's own setup — so a builtin wired only into that
 * copy left the suite green on a build where the tool did not exist at runtime. That is not
 * hypothetical: {@code chat_read} sat in exactly that state.
 *
 * <p>Both directions are checked, and neither pushes toward weakening a declaration:
 * <ol>
 *   <li>every registered name declares at least one row;</li>
 *   <li>the ring a tool is REGISTERED with equals the ring the gate ENFORCES;</li>
 *   <li>every declared row names a tool that is actually registered.</li>
 * </ol>
 * Declaring a STRICTER ring than the fallback stays legal — {@link Ring#forBuiltin} is what
 * BOTH sides read, so only a registration bypassing the table can disagree with the gate.
 */
public final class BuiltinGateAudit {

    private BuiltinGateAudit() {
    }

    /** The outcome of an audit: human-readable problems, empty when clean. */
    public record Report(Set<String> ungated, Set<String> ringDivergent, Set<String> staleRows) {

        public Report {
            ungated = Set.copyOf(ungated);
            ringDivergent = Set.copyOf(ringDivergent);
            staleRows = Set.copyOf(staleRows);
        }

        /** True when nothing is wrong — the green case. */
        public boolean clean() {
            return ungated.isEmpty() && ringDivergent.isEmpty() && staleRows.isEmpty();
        }

        /** Every problem as one message, naming the specific tools involved. */
        public String message() {
            StringBuilder sb = new StringBuilder();
            if (!ungated.isEmpty()) {
                sb.append("registered built-ins in NONE of the three gate tables (Ring BUILTIN_RINGS, "
                        + "L3_WRITES, L4_PRIVILEGE) — each is enforced at the R3 fallback with no "
                        + "L3/L4 gate while every visible surface reports its registered ring: ")
                        .append(new TreeSet<>(ungated));
            }
            if (!ringDivergent.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append("tools whose REGISTERED ring differs from the ring the gate ENFORCES — "
                        + "the displayed ring is then a lie: ")
                        .append(new TreeSet<>(ringDivergent));
            }
            if (!staleRows.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append("gate rows naming tools nothing registers (stale after a rename/removal?): ")
                        .append(new TreeSet<>(staleRows));
            }
            return sb.toString();
        }
    }

    /**
     * Audit a live registry against the gate tables.
     *
     * <p>Driven, not read: the names come from {@link IoManager#names()} and the "declared?"
     * answer comes from the gate's OWN resolver — {@link Ring#forBuiltin(String, Ring)} asked
     * with a {@code null} fallback, the only way to tell a declared R3 from the fallback R3
     * that {@link SeToolRequirement#forTool} would otherwise apply invisibly.
     */
    public static Report audit(IoManager registry) {
        Set<String> registered = new TreeSet<>(registry.names());

        Set<String> ungated = new TreeSet<>();
        for (String name : registered) {
            SeToolRequirement req = SeToolRequirement.forTool(name, true);
            boolean declaresARow = Ring.forBuiltin(name, null) != null
                    || req.writesResourceAt() != null
                    || req.requiredPrivilege() != null;
            if (!declaresARow) {
                ungated.add(name);
            }
        }

        Set<String> ringDivergent = new TreeSet<>();
        for (Capability c : registry.capabilities()) {
            Ring enforced = SeToolRequirement.forTool(c.name(), true).requiredRing();
            if (c.ring() != enforced) {
                ringDivergent.add(c.name() + " registered=" + c.ring().tag()
                        + " enforced=" + enforced.tag());
            }
        }

        // A gate row may name a name the production registry only holds in ONE of its two
        // states. DebugTools registers debug_manage always and debug_handle only when the L6
        // object-handle layer is wired (-Dmcp.core.handles=true); ADR-0004 further folded the
        // eleven concrete JVMTI ops behind those two manifest entries, so those concrete names
        // are gated but never registered at all.
        //
        // The widening is by DebugTools' OWN authoritative lists and nothing else, so a
        // mistyped or genuinely stale name still fails: the check stays tight rather than
        // becoming "any row that is not a registered tool is fine".
        Set<String> gateable = new LinkedHashSet<>(registered);
        gateable.addAll(DebugTools.FOLDED_TOOL_NAMES);
        gateable.addAll(DebugTools.MANAGE_ACTIONS);
        gateable.addAll(DebugTools.HANDLE_ACTIONS);
        Set<String> staleRows = new TreeSet<>(Ring.declaredBuiltinNames());
        staleRows.removeAll(gateable);
        staleRows.addAll(staleOf(SeToolRequirement.l3WriteNames(), gateable));
        staleRows.addAll(staleOf(SeToolRequirement.l4PrivilegeNames(), gateable));

        return new Report(ungated, ringDivergent, staleRows);
    }

    /** Stale names from ONE side table. Asserted over its values, not presence, so it cannot
     *  be satisfied by weakening a requirement — only the ring table's key set is widened. */
    private static Set<String> staleOf(Set<String> table, Set<String> gateable) {
        Set<String> stale = new TreeSet<>(table);
        stale.removeAll(gateable);
        return stale;
    }

    /**
     * The action lists must be non-empty or the {@link #audit} widening proves nothing.
     * Separate so a caller can assert the audit is not vacuous.
     */
    public static boolean actionListsPopulated() {
        return !DebugTools.MANAGE_ACTIONS.isEmpty() && !DebugTools.HANDLE_ACTIONS.isEmpty();
    }
}