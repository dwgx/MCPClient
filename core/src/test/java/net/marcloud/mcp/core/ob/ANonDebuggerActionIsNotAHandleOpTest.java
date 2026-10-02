package net.marcloud.mcp.core.ob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.io.IoRequestPacket;
import net.marcloud.mcp.core.kd.DebugTools;
import net.marcloud.mcp.core.se.SeAccessCheck;
import net.marcloud.mcp.core.se.SeToken;
import org.junit.Test;

/**
 * `action` means one thing only on a tool whose operations were folded into one name.
 *
 * <p><b>The defect this pins.</b> ADR-0004 folded eleven {@code debug_*} tools into
 * {@code debug_manage} and {@code debug_handle}, so a call has to name the concrete operation in an
 * {@code action} argument. {@code ObManager.checkRequest} worked out whether a call was folded by
 * asking whether the caller had passed an argument called {@code action} -- a different question
 * that happens to share a word. {@code do_use_entity} and {@code do_entity_action} both declare an
 * {@code action}, so on a handles-enabled run they arrived with {@code folded = true} and an
 * operation the handle table has never heard of, and were refused with
 * "action 'ATTACK' on tool 'do_use_entity' is not a recognised handle-op". Two shipped R1
 * actuation tools, unusable on every call, refusing in the vocabulary of a debugger the caller
 * never touched.
 *
 * <p><b>Why it survived a green suite.</b> The gate is fail-closed, so it never threw, never
 * crashed and never produced a red build -- it simply refused, correctly, to far too much. Only a
 * test that calls a NON-debugger tool through the gate can see it, and the existing L6 test
 * exercised nothing but the folded tools themselves.
 *
 * <p><b>Both directions are asserted, because one alone is satisfiable by the wrong thing.</b> A
 * fix that simply deleted the rule would pass "do_use_entity is allowed" and would reopen the hole
 * the rule was written for: a read-only handle suspending a thread. So the second test requires
 * that an unrecognised operation on an actual folded tool is still refused.
 */
public class ANonDebuggerActionIsNotAHandleOpTest {

    private static ObManager manager() {
        return new ObManager(ref -> new Object(), 8, 60_000L);
    }

    private static IoRequestPacket call(String tool, Map<String, Object> args) {
        return new IoRequestPacket(tool, args, true);
    }

    @Test
    public void aToolOutsideTheDebuggerWhoseArgumentIsAlsoCalledActionIsNotGatedAsOne() {
        ObManager om = manager();

        SeAccessCheck attack = om.checkRequest(SeToken.wideOpen(),
                call("do_use_entity", Map.of("entityId", 7, "action", "ATTACK")));
        assertTrue("do_use_entity must not be refused as an unrecognised handle-op: "
                + attack.reason(), attack.allow());

        SeAccessCheck entityAction = om.checkRequest(SeToken.wideOpen(),
                call("do_entity_action", Map.of("action", "START_SNEAKING")));
        assertTrue("do_entity_action must not be refused either: " + entityAction.reason(),
                entityAction.allow());

        // A tool the handle table has never heard of is NOT denied for that, and asserting
        // otherwise would be asserting a whitelist that does not exist and should not: the L6
        // branch gates handle-ops, and a tool that uses no handle needs none.
    }

    @Test
    public void anUnrecognisedOperationOnAFoldedDebuggerToolIsStillRefused() {
        ObManager om = manager();

        SeAccessCheck unknown = om.checkRequest(SeToken.wideOpen(),
                call("debug_manage", Map.of("action", "debug_do_something_imaginary")));
        assertFalse("an unknown operation must not reach the debugger", unknown.allow());
        assertTrue("and the refusal must say it was refused rather than downgraded: "
                + unknown.reason(), unknown.reason().contains("refus"));

        // No action at all on a folded tool is equally not-an-operation.
        assertFalse("debug_manage with no action names no operation",
                om.checkRequest(SeToken.wideOpen(), call("debug_manage", Map.of())).allow());
    }

    /**
     * The two lists that have to agree: {@code ObManager} mirrors the folded names rather than
     * importing them, because {@code ob} must not depend on {@code kd}. A mirror that drifts
     * denies the wrong half of the debugger and is invisible until someone tries to use it.
     */
    @Test
    public void theFoldedNameListMatchesTheOneThatIsActuallyRegistered() {
        assertEquals("ObManager's folded-name list has drifted from DebugTools",
                java.util.Set.copyOf(DebugTools.FOLDED_TOOL_NAMES),
                ObManager.FOLDED_DEBUG_TOOLS);
    }

    /**
     * A handle has to be MINTABLE, or the whole L6 layer is decorative.
     *
     * <p>{@code HANDLE_OPS} lists the six operations that CONSUME an existing handle.
     * {@code debug_open_thread} is not among them and cannot be: it is the operation that creates the
     * handle every one of them needs. Routing it through that table made the gate unpassable --
     * {@code debug_handle action=debug_open_thread} came back "not a recognised handle-op", so under
     * {@code -Dmcp.core.handles=true} no handle could ever be minted, the frozen-target TOCTOU
     * protection was inert in the only configuration that enables it, and under strict handles every
     * debugger op was unusable.
     *
     * <p>It survived because every existing L6 test mints its handle by calling {@code om.open(...)}
     * directly and never goes through the tool surface: the gate under test was bypassed by the
     * test that claims to test it. These assertions go through {@code checkRequest}, which is the
     * path a real caller takes.
     */
    @Test
    public void aHandleCanBeMintedAndClosedThroughTheGate() {
        ObManager om = manager();

        SeAccessCheck open = om.checkRequest(SeToken.wideOpen(),
                call("debug_handle", Map.of("action", "debug_open_thread")));
        assertTrue("minting a handle is the operation the whole layer exists to enable, and it must "
                + "not be refused as an unknown handle-op: " + open.reason(), open.allow());

        SeAccessCheck close = om.checkRequest(SeToken.wideOpen(),
                call("debug_handle", Map.of("action", "debug_close_handle")));
        assertTrue("closing one likewise: " + close.reason(), close.allow());
    }

    /** A lifecycle action must not become a way around the table: passing it a handle is refused. */
    @Test
    public void aLifecycleActionRefusesAHandleItCannotUse() {
        SeAccessCheck withHandle = manager().checkRequest(SeToken.wideOpen(),
                call("debug_handle", Map.of("action", "debug_open_thread", "handle", "1")));
        assertFalse("debug_open_thread creates the handle; handing it one is a caller mistake",
                withHandle.allow());
    }

    /** The mirror that keeps the exemption honest. */
    @Test
    public void theLifecycleExemptionMatchesTheActionsDebugToolsPublishes() {
        assertEquals("ObManager's lifecycle list has drifted from DebugTools.HANDLE_ACTIONS",
                java.util.Set.copyOf(DebugTools.HANDLE_ACTIONS),
                ObManager.HANDLE_LIFECYCLE_ACTIONS);
        assertFalse("a lifecycle action must not also be a handle-op needing a handle",
                ObManager.HANDLE_LIFECYCLE_ACTIONS.stream()
                        .anyMatch(ObManager.HANDLE_OPS::containsKey));
    }
}
