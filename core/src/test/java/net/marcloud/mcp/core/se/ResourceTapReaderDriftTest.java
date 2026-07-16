package net.marcloud.mcp.core.se;

import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Drift guard for the "a tool that READS a gated resource must hold that resource's
 * capability" invariant — the symmetric counterpart to {@link PolicySideTableDriftTest},
 * which only guarded the WRITE/privilege side (L3→ring, L4→ring, SE_NET_RAW both-ways).
 *
 * <p>The gap this closes: {@code packets_tail}/{@code packet_get}/{@code packet_view}
 * read the same {@code PacketJournal} as {@code recent_packets} (and {@code packet_view}
 * exposes MORE — typed position/health/world-edit fields), yet shipped with no
 * {@link CapabilitySid#CAP_NETWORK_RECV_TAP} requirement, so
 * {@code revoke_capability(CAP_NETWORK_RECV_TAP)} could not shut them off. No existing
 * green test caught it — exactly the silent-drift failure mode the send-tool prefix
 * heuristic showed. This pins the resource-reader side <b>bidirectionally</b>, keyed off
 * an explicit declared table, so a new reader that skips the cap (or a tool granted the
 * cap without being a declared reader) fails here.
 *
 * <p>Note on the {@code Timeline}/{@code timeline_tail} leak (a THIRD reader of packet
 * data via the {@code GameEvent} base subscription): that is pinned behaviorally in
 * {@code TimelineTest} (Option C — packet events are no longer folded into the timeline),
 * not here, because it is a data-flow exclusion rather than a capability declaration.
 */
public class ResourceTapReaderDriftTest {

    /**
     * Per gated resource class: the capability SID and the tools declared to read that
     * resource. The invariant is bidirectional against {@link CapabilityCatalog}:
     * a member must require the SID, and any builtin requiring the SID must be a member.
     */
    private static final Map<CapabilitySid, Set<String>> RESOURCE_TAP = Map.of(
            CapabilitySid.CAP_NETWORK_RECV_TAP, Set.of(
                    "recent_packets", "disconnect_report",
                    "packets_tail", "packet_get", "packet_view"),
            CapabilitySid.CAP_SCREEN_CAP, Set.of(
                    "capture_screen", "gui_snapshot_image"));

    /** FORWARD: every declared reader of a gated resource must require that resource's cap. */
    @Test
    public void everyDeclaredResourceReaderRequiresItsCapability() {
        Set<String> offenders = new TreeSet<>();
        for (Map.Entry<CapabilitySid, Set<String>> row : RESOURCE_TAP.entrySet()) {
            CapabilitySid sid = row.getKey();
            for (String tool : row.getValue()) {
                if (!SeToolRequirement.forTool(tool, true).requiredCaps().contains(sid)) {
                    offenders.add(tool + " missing " + sid);
                }
            }
        }
        assertTrue("declared resource-tap readers without their capability — revoking the cap "
                + "would NOT stop them reading the gated resource: " + offenders, offenders.isEmpty());
    }

    /**
     * REVERSE: any builtin that requires a resource-tap cap must be a DECLARED reader of
     * that resource. This is the direction that closes the trap: a new tool granted
     * CAP_NETWORK_RECV_TAP (or CAP_SCREEN_CAP) without being declared here fails, forcing
     * the maintainer to acknowledge it as a reader of that resource class.
     */
    @Test
    public void everyCapabilityHolderIsADeclaredReader() {
        Set<String> offenders = new TreeSet<>();
        for (Map.Entry<CapabilitySid, Set<String>> row : RESOURCE_TAP.entrySet()) {
            CapabilitySid sid = row.getKey();
            Set<String> declared = row.getValue();
            for (String tool : Ring.declaredBuiltinNames()) {
                if (SeToolRequirement.forTool(tool, true).requiredCaps().contains(sid)
                        && !declared.contains(tool)) {
                    offenders.add(tool + " holds " + sid + " but is not a declared reader");
                }
            }
        }
        assertTrue("tools hold a resource-tap capability but are not declared readers of that "
                + "resource — declare them so the reader invariant covers them: " + offenders,
                offenders.isEmpty());
    }
}
