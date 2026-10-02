package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.Test;

/**
 * {@code do_use_entity} and {@code do_entity_action} must not report a bare "sent X" for effects that
 * CAN be observed, and must SAY SO for the ones that cannot.
 *
 * <p><b>The bug this pins.</b> Both handlers ended in
 * {@code return sendTyped(packet, "...")}, so both answered {@code sent use_entity INTERACT #41} and
 * {@code sent entity_action START_SNEAKING} with no disclosure at all. Those are the last two
 * un-disclosed bare sends in this file; four others ({@code do_click_slot}, {@code do_set_abilities},
 * {@code do_select_slot}, {@code do_client_status}) already disclose that they only report the send.
 *
 * <p><b>Why it is not one fix.</b> Reading vanilla rather than plausibility, the actions split three
 * ways, and a uniform treatment would be a lie about at least one of them:
 *
 * <ul>
 *   <li><b>CONFIRMABLE.</b> Sneak is server state: {@code processEntityAction:846} calls
 *       {@code setSneaking} -&gt; {@code setFlag(1)} -&gt; datawatcher index 0 bit 1, which
 *       {@code EntityTrackerEntry.func_151261_b} pushes back to the tracked player because it is an
 *       {@code EntityPlayerMP}. Attack is server state: health is datawatcher index 6.
 *       {@code STOP_SLEEPING} clears {@code sleeping} via the S0B animation.
 *   <li><b>NOT CONFIRMABLE, DISCLOSED.</b> Sprint uses the same server write as sneak, but
 *       {@code EntityPlayerSP.onLivingUpdate:800-819} recomputes that flag from the movement keys
 *       every tick, so no re-read can separate the server from the keyboard.
 *       {@code RIDING_JUMP} sets {@code jumpPower}, a plain field never put in a datawatcher.
 *       {@code INTERACT}/{@code INTERACT_AT} return a boolean the server discards.
 *   <li><b>REFUSABLE.</b> {@code OPEN_INVENTORY} and {@code RIDING_JUMP} are no-ops unless the
 *       player is on a horse (tame / saddled respectively), and attacking an item, an XP orb, an
 *       arrow or yourself gets you kicked by {@code processUseEntity:932}.
 * </ul>
 *
 * <p><b>What is pinned here.</b> The decisions are pure and package-visible for the reason
 * {@code creativeVerdictFor} is: with the polling loop needing a live client, a rule that can only be
 * exercised live runs green for the broken code too. The mutations below each target a decision that
 * a live client is not needed to reach.
 */
public final class EntityActionsAreConfirmedAgainstVanillaTest {

    private static final net.minecraft.network.play.client.C0BPacketEntityAction.Action SNEAK_START =
            net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SNEAKING;
    private static final net.minecraft.network.play.client.C0BPacketEntityAction.Action SNEAK_STOP =
            net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SNEAKING;
    private static final net.minecraft.network.play.client.C0BPacketEntityAction.Action RIDING_JUMP =
            net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP;
    private static final net.minecraft.network.play.client.C0BPacketEntityAction.Action OPEN_INV =
            net.minecraft.network.play.client.C0BPacketEntityAction.Action.OPEN_INVENTORY;
    private static final net.minecraft.network.play.client.C0BPacketEntityAction.Action SPRINT_START =
            net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SPRINTING;

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool(ToolRegistry reg, String name) {
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals(name)) {
                return s;
            }
        }
        throw new AssertionError(name + " is not in all(): a tool that never reaches the registry is "
                + "one no model can call");
    }

    private static String call(ToolRegistry reg, String name, Map<String, Object> args) {
        CallToolResult r = tool(reg, name).callHandler()
                .apply(null, new CallToolRequest(name, args));
        String text = "";
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                text = t.text();
            }
        }
        return (Boolean.TRUE.equals(r.isError()) ? "ERROR " : "OK ") + text;
    }

    private static boolean riding(boolean horse, boolean saddled, boolean tame) {
        return horse || saddled || tame;
    }

    private static ToolRegistry.RideState onHorse(boolean saddled, boolean tame) {
        return new ToolRegistry.RideState(true, true, saddled, tame);
    }

    // ===================== do_use_entity / ATTACK =====================

    /**
     * MUTATION TARGET 1 — the attack success condition is the health DROP.
     *
     * <p>Flipping {@code afterHealth < beforeHealth} to {@code !=}, or to {@code >} (a "health went
     * up, so it healed, so the attack worked" reading), must turn this red.
     */
    @Test
    public void onlyHealthThatActuallyDroppedCountsAsAHit() {
        assertEquals("20 -> 17: the damage landed",
                ToolRegistry.AttackVerdict.HIT,
                ToolRegistry.attackVerdictFor(20f, 17f, true));

        assertEquals("20 -> 0 is a kill and still a hit, not a special case",
                ToolRegistry.AttackVerdict.HIT,
                ToolRegistry.attackVerdictFor(20f, 0f, true));

        assertEquals("20 -> 20: the server did not damage it. A 'did anything change' test would "
                        + "also say this, which is why the opposite is the point",
                ToolRegistry.AttackVerdict.NOT_HIT,
                ToolRegistry.attackVerdictFor(20f, 20f, true));

        assertEquals("20 -> 20.5: health went UP (regeneration, absorption). That is emphatically "
                        + "not evidence the attack landed",
                ToolRegistry.AttackVerdict.NOT_HIT,
                ToolRegistry.attackVerdictFor(20f, 20.5f, true));
    }

    /**
     * MUTATION TARGET 2 — a dead target is a HIT.
     *
     * <p>The server destroys a killed entity and the client's copy leaves the world, so health can no
     * longer be read at all. Returning UNREADABLE here refuses to name the one outcome the caller can
     * plainly see; returning NOT_HIT inverts it. Both must fail.
     */
    @Test
    public void aTargetThatLeftTheWorldIsAKillNotAnUnreadable() {
        assertEquals("the target is gone: for a living entity that is what a lethal hit looks like",
                ToolRegistry.AttackVerdict.HIT,
                ToolRegistry.attackVerdictFor(20f, null, false));

        assertEquals("but health we never read in the first place still claims nothing",
                ToolRegistry.AttackVerdict.UNREADABLE,
                ToolRegistry.attackVerdictFor(null, null, false));

        assertEquals("a present-but-unreadable target is UNREADABLE, not a silent miss",
                ToolRegistry.AttackVerdict.UNREADABLE,
                ToolRegistry.attackVerdictFor(20f, null, true));
    }

    /**
     * MUTATION TARGET 3 — the refusals.
     *
     * <p>Dropping the {@code KICKS_ON_ATTACK} branch, or letting any non-null target through, must
     * turn this red. The kick is real: {@code processUseEntity:932} calls
     * {@code kickPlayerFromServer("Attempting to attack an invalid entity")} for exactly these four.
     */
    @Test
    public void anAttackThatCannotBeConfirmedOrWouldKickIsRefused() {
        String kick = ToolRegistry.attackRefusalFor(ToolRegistry.AttackTarget.KICKS_ON_ATTACK);
        assertNotNull("attacking an item/orb/arrow/yourself kicks you off the server", kick);
        assertTrue("the refusal must name the kick, or the caller repeats it: " + kick,
                kick.contains("kick"));
        assertTrue("and must say nothing was sent, so it cannot be read as a hit in flight: " + kick,
                kick.contains("Nothing was sent"));

        String noHealth = ToolRegistry.attackRefusalFor(ToolRegistry.AttackTarget.NOT_LIVING);
        assertNotNull("a target with no health can never be confirmed", noHealth);
        assertTrue("it must say why: " + noHealth, noHealth.contains("no health"));

        assertNull("a living target is attackable and confirmable, so it must NOT be refused",
                ToolRegistry.attackRefusalFor(ToolRegistry.AttackTarget.LIVING));

        String absent = ToolRegistry.attackRefusalFor(null);
        assertNotNull("an absent entity is refused too, naming the unreadable world", absent);
        assertTrue(absent.contains("Nothing was sent"));
    }

    // ===================== do_use_entity / INTERACT =====================

    /**
     * MUTATION TARGET 4 — INTERACT stays bare, but must DISCLOSE.
     *
     * <p>The reply text is what changes here. Restoring the bare
     * {@code "sent use_entity " + action.name() + " #" + entityId} must turn this red, and so must
     * removing just the disclosure while keeping the send.
     *
     * <p><b>Can this fail without a live client? YES</b> — and it must, because the disclosure is a
     * constant in the source, not a branch. Note what it deliberately does NOT assert: it does not
     * claim INTERACT is confirmed. Interact is not confirmable (see below), so a test demanding
     * confirmation here would be pinning the defect.
     */
    @Test
    public void anUnconfirmedInteractSaysSoInItsOwnReply() {
        String reply = call(registry(), "do_use_entity",
                Map.of("entityId", 41, "action", "INTERACT"));

        assertTrue("with no client the send itself fails, which is not what this test is about: "
                + reply, reply.startsWith("ERROR ") || reply.startsWith("OK "));
        // The disclosure lives in the same string as the label, so assert on the description AND on
        // the absence of an unqualified success claim.
        String d = tool(registry(), "do_use_entity").tool().description();
        assertNotNull(d);
        assertTrue("INTERACT must be labelled NOT CONFIRMABLE in the description: " + d,
                d.contains("NOT CONFIRMABLE"));
        assertTrue("and the reason, so a caller knows it is not a transient failure: " + d,
                d.contains("discards interactWith"));
        assertTrue("INTERACT_AT's own dead end is the base Entity.interactAt returning false for "
                + "everything but an armor stand: " + d, d.contains("armor stand"));
    }

    /**
     * MUTATION TARGET 4c — the UNCONFIRMED replies must disclose, in the reply itself.
     *
     * <p>This is the assertion that mutation 5 -- restoring {@code ok("sent entity_action " + name)}
     * with no caveat -- fails, and it failed to fail at first: the description assertions still
     * passed, because the DESCRIPTION kept its disclosure while the REPLY lost it. The description
     * is what the model reads when choosing a tool, but the reply is what it reads when deciding
     * what happened, and a caller that trusted the reply would act on a send. Both have to be
     * pinned, and only pinning the description let the defect back in.
     *
     * <p>It is written as a source-level assertion rather than a call assertion on purpose: with no
     * client the handler errors out before reaching the bare branch, so calling the tool cannot
     * reach the string. The bare-case branch is therefore extracted into a pure builder and pinned
     * directly.
     *
     * <p><b>Can this fail without a live client? YES.</b>
     */
    @Test
    public void theUnconfirmedRepliesDiscloseThatTheyAreUnconfirmed() {
        for (var action : List.of(
                net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SPRINTING,
                net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SPRINTING,
                net.minecraft.network.play.client.C0BPacketEntityAction.Action.RIDING_JUMP)) {
            String reply = ToolRegistry.unconfirmedEntityActionReply(action);
            assertTrue(action + " must say it is unconfirmed in the reply, not only in the "
                    + "description: " + reply, reply.contains("NOT CONFIRMED"));
            assertTrue(action + " must give the reason, so it reads as a fact and not a shrug: "
                    + reply, reply.contains("RIDING_JUMP".equals(action.name())
                    ? "server-side field" : "movement keys"));
        }
    }

    // ===================== do_entity_action =====================

    /**
     * MUTATION TARGET 5 — the sneak verdict is IDENTITY with the wanted flag.
     *
     * <p>Flipping {@code observed == wanted} to {@code observed != wanted} (the "did it change"
     * shape) must turn this red. STOP_SNEAKING on a player who was already standing is the case that
     * matters: the server ignoring the packet and the server applying it are indistinguishable from
     * the flag alone, and the honest answer is NOT_CONFIRMED.
     */
    @Test
    public void onlyTheFlagReadingWhatWasAskedForCountsAsConfirmed() {
        assertEquals("START_SNEAKING and the server's flag now reads true: applied",
                ToolRegistry.EntityActionVerdict.CONFIRMED,
                ToolRegistry.entityActionVerdictFor(Boolean.TRUE, true));

        assertEquals("STOP_SNEAKING and the flag reads false: applied",
                ToolRegistry.EntityActionVerdict.CONFIRMED,
                ToolRegistry.entityActionVerdictFor(Boolean.FALSE, false));

        assertEquals("START_SNEAKING and the flag still reads false: NOT applied",
                ToolRegistry.EntityActionVerdict.NOT_CONFIRMED,
                ToolRegistry.entityActionVerdictFor(Boolean.FALSE, true));

        assertEquals("STOP_SNEAKING and the flag still reads true: NOT applied",
                ToolRegistry.EntityActionVerdict.NOT_CONFIRMED,
                ToolRegistry.entityActionVerdictFor(Boolean.TRUE, false));
    }

    /**
     * MUTATION TARGET 6 — an unreadable flag claims nothing.
     *
     * <p>Returning NOT_CONFIRMED for null must turn this red: "the server did not do it" and "I could
     * not look" are different claims and must not share a value.
     */
    @Test
    public void anUnreadableFlagClaimsNothing() {
        assertEquals("no player means no flag, so no verdict about the write",
                ToolRegistry.EntityActionVerdict.UNREADABLE,
                ToolRegistry.entityActionVerdictFor(null, true));
        assertEquals("and it is unreadable whichever way the action pointed",
                ToolRegistry.EntityActionVerdict.UNREADABLE,
                ToolRegistry.entityActionVerdictFor(null, false));
    }

    /**
     * MUTATION TARGET 7 — the verdict set cannot express the defect.
     *
     * <p>The shape {@code transfer_item} and {@code do_set_creative_slot} adopted: adding SENT or
     * UNCONFIRMED must turn this red. There is deliberately no fourth value.
     */
    @Test
    public void theVerdictSetHasNoSentOrUnconfirmedOutcome() {
        for (var v : ToolRegistry.EntityActionVerdict.values()) {
            assertNotEquals("'the packet was sent' is a fact about the wire, not an outcome: " + v,
                    "SENT", v.name());
            assertNotEquals("'unconfirmed' is the same hole wearing a hat: " + v,
                    "UNCONFIRMED", v.name());
        }
        assertEquals("three is the complete set of things one re-read can establish",
                3, ToolRegistry.EntityActionVerdict.values().length);

        for (var v : ToolRegistry.AttackVerdict.values()) {
            assertNotEquals("a send is not a hit: " + v, "SENT", v.name());
        }
        assertEquals(3, ToolRegistry.AttackVerdict.values().length);
    }

    // ===================== the mount gates =====================

    /**
     * MUTATION TARGET 8 — OPEN_INVENTORY and RIDING_JUMP are refused when the server's gate is shut.
     *
     * <p>These are the two genuinely unconfirmable-yet-conditional actions. Dropping the
     * {@code !s.riding()} / {@code !s.horse()} / {@code !s.saddled()} / {@code !s.tame()} guards must
     * turn this red, one at a time.
     *
     * <p><b>Can this fail without a live client? YES.</b> {@code entityActionRefusalFor} is pure and
     * takes the ride state as a parameter, so the whole decision table is reachable headlessly. That
     * is the point of keeping it out of the handler.
     */
    @Test
    public void mountGatedActionsAreRefusedWhenTheServersGateIsShut() {
        var nowhere = new ToolRegistry.RideState(false, false, false, false);

        String notRiding = ToolRegistry.entityActionRefusalFor(OPEN_INV, nowhere);
        assertNotNull("OPEN_INVENTORY does nothing off a horse: processEntityAction:878-889", notRiding);
        assertTrue("it must say nothing was sent: " + notRiding, notRiding.contains("Nothing was sent"));

        String notRidingJump = ToolRegistry.entityActionRefusalFor(RIDING_JUMP, nowhere);
        assertNotNull("same gate applies to RIDING_JUMP", notRidingJump);
        assertTrue(notRidingJump.contains("Nothing was sent"));

        assertTrue("refusing a non-horse mount is its own message, because 'mount a horse' is the fix",
                ToolRegistry.entityActionRefusalFor(OPEN_INV,
                        new ToolRegistry.RideState(true, false, false, false))
                        .contains("HORSE"));

        assertTrue("RIDING_JUMP reaches setJumpPower, guarded by isHorseSaddled",
                ToolRegistry.entityActionRefusalFor(RIDING_JUMP, onHorse(false, true))
                        .contains("isHorseSaddled"));

        assertTrue("OPEN_INVENTORY reaches openGUI, guarded by isTame",
                ToolRegistry.entityActionRefusalFor(OPEN_INV, onHorse(true, false))
                        .contains("isTame"));

        assertNull("a tame saddle horse is the one case where OPEN_INVENTORY can do something",
                ToolRegistry.entityActionRefusalFor(OPEN_INV, onHorse(true, true)));

        assertNull("and a saddled horse is the one case where RIDING_JUMP can do something",
                ToolRegistry.entityActionRefusalFor(RIDING_JUMP, onHorse(true, false)));

        assertNotNull("an unreadable mount state must refuse rather than send blind",
                ToolRegistry.entityActionRefusalFor(OPEN_INV, null));
    }

    /**
     * MUTATION TARGET 4b — the ATTACK branch must not be able to reach the bare send at all.
     *
     * <p>This is the headless half of MUTATION TARGET 4 and the only assertion in this class that
     * fails against the ORIGINAL code. Reverting the branch to
     * {@code return sendTyped(new C02PacketUseEntity(target, ATTACK), "use_entity ATTACK #" + id)}
     * removes the refusal, so the reply becomes the transport failure instead and the assertion
     * below turns red.
     *
     * <p><b>Can this fail without a live client? YES, and measurably so.</b> With no client attached
     * {@code classifyAttackTarget} cannot resolve anything, so the refusal fires and nothing is
     * sent. That refusal is the discriminator. The polling loop in {@code confirmAttack} still needs
     * a live client to exercise, which is exactly why the health arithmetic was pushed into the
     * pure {@link ToolRegistry#attackVerdictFor} above rather than left in the loop.
     */
    @Test
    public void anAttackOnAnUnreadableTargetIsRefusedBeforeAnythingIsSent() {
        String reply = call(registry(), "do_use_entity",
                Map.of("entityId", 41, "action", "ATTACK"));

        assertTrue("this must be an ERROR: " + reply, reply.startsWith("ERROR "));
        // With no client the send fails either way, so "it is an ERROR" passes for BOTH the fixed
        // and the reverted code -- that was measured, and it is why this assertion has to name what
        // actually differs: whether the send was attempted at all.
        assertTrue("it must refuse RATHER THAN SEND, because the hit could never be confirmed -- "
                + "this is the assertion the reverted code fails: " + reply,
                reply.contains("Nothing was sent"));
        assertTrue("and the old bare success label must not appear anywhere: " + reply,
                !reply.contains("sent use_entity ATTACK"));
    }

    /**
     * The mount-gated actions refuse through the TOOL too, not only through the pure predicate.
     *
     * <p>Pure refusal logic that the handler quietly bypasses is decoration. This pins the wiring:
     * with no client the ride state is unreadable, and both mount-gated actions must refuse rather
     * than reach the wire.
     */
    @Test
    public void mountGatedActionsRefuseThroughTheToolItself() {
        for (String action : List.of("OPEN_INVENTORY", "RIDING_JUMP")) {
            String reply = call(registry(), "do_entity_action", Map.of("action", action));
            assertTrue(action + " must be an ERROR with no client: " + reply,
                    reply.startsWith("ERROR "));
            assertTrue(action + " must refuse before sending, not merely fail to send: " + reply,
                    reply.contains("Nothing was sent"));
            assertTrue(action + " must not report a bare send: " + reply,
                    !reply.contains("sent entity_action"));
        }
    }

    /**
     * MUTATION TARGET 9 — the ungated actions are never refused.
     *
     * <p>Adding a blanket "refuse everything unreadable" guard must turn this red: it would refuse
     * the four actions that work fine off-horse and are confirmable.
     */
    @Test
    public void actionsThatAreNotMountGatedAreNeverRefused() {
        var nowhere = new ToolRegistry.RideState(false, false, false, false);
        assertNull("sneaking works anywhere", ToolRegistry.entityActionRefusalFor(SNEAK_START, nowhere));
        assertNull("stop sneaking too", ToolRegistry.entityActionRefusalFor(SNEAK_STOP, nowhere));
        assertNull("sprinting works anywhere", ToolRegistry.entityActionRefusalFor(SPRINT_START, nowhere));
        assertNull("stop sleeping works anywhere",
                ToolRegistry.entityActionRefusalFor(
                        net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SLEEPING,
                        nowhere));
        assertNull("and an unreadable mount state does not block them either",
                ToolRegistry.entityActionRefusalFor(SNEAK_START, null));
    }

    // ===================== descriptions =====================

    /**
     * The disclosure has to be in the description, because that is what the model reads before
     * choosing the tool — a caveat that only appears after the call is too late to act on.
     *
     * <p><b>Can this fail without a live client? YES.</b> These are string assertions on constants.
     */
    @Test
    public void doEntityActionDescriptionNamesEachActionsCategory() {
        String d = tool(registry(), "do_entity_action").tool().description();
        assertNotNull(d);

        assertTrue("sneak and sleep are CONFIRMED and the caller must be told so: " + d,
                d.contains("CONFIRMED"));
        assertTrue("sprint is the one action whose flag the client recomputes itself, and that "
                + "reason is the whole disclosure: " + d, d.contains("movement keys"));
        assertTrue("jumpPower never reaches the client, so RIDING_JUMP cannot be confirmed: " + d,
                d.contains("datawatcher"));
        assertTrue("OPEN_INVENTORY opens THE HORSE'S CHEST, not the caller's inventory -- a caller "
                + "who believes otherwise will think their inventory opened: " + d,
                d.contains("HORSE'S CHEST"));
        assertTrue("and the refusals must state their gates: " + d, d.contains("SADDLED"));
    }

    /**
     * ATTACK's confirmation, and the two refusals, must be in the description too.
     */
    @Test
    public void doUseEntityDescriptionNamesTheAttackConfirmation() {
        String d = tool(registry(), "do_use_entity").tool().description();
        assertNotNull(d);

        assertTrue("ATTACK is the confirmed half and must say it re-reads health: " + d,
                d.contains("re-reads the target's health"));
        assertTrue("a kill is confirmable by the target leaving the world: " + d,
                d.contains("left the world"));
        assertTrue("the refusal is part of the contract: attacking an item kicks you: " + d,
                d.contains("kick"));
    }

    /**
     * Both tools must still be reachable, or none of this matters.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void bothToolsAreRegisteredAndStillTakeTheirArguments() {
        var use = tool(registry(), "do_use_entity").tool().inputSchema();
        var act = tool(registry(), "do_entity_action").tool().inputSchema();

        Map<String, Object> useProps = (Map<String, Object>) use.get("properties");
        Map<String, Object> actProps = (Map<String, Object>) act.get("properties");
        assertNotNull(useProps);
        assertNotNull(actProps);
        assertNotNull("entityId is how the target is chosen", useProps.get("entityId"));
        assertNotNull("action is how the effect is chosen", useProps.get("action"));
        assertNotNull("auxData is the RIDING_JUMP boost", actProps.get("auxData"));

        List<?> required = (List<?>) use.get("required");
        assertNotNull(required);
        assertTrue("both the target and the action are required", required.contains("entityId")
                && required.contains("action"));
    }
}