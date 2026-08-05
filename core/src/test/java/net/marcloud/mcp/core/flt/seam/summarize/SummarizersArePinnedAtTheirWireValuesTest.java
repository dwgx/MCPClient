package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;

import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C0CPacketInput;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S41PacketServerDifficulty;
import net.minecraft.world.EnumDifficulty;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Teeth for the values the seam summarizers carry, not merely for the fields they
 * emit. The existing suite samples each of these summarizers on exactly one side of
 * its domain -- angles only below 128, stacks only null, onGround only true, locked
 * only true, the input axes only through the typed half, and S0E not at all -- so a
 * summarizer could hard-code the sampled answer and stay green.
 *
 * <p>Each test here feeds the UNSAMPLED side and asserts the specific value a caller
 * would misread, plus, where the seam has two halves, that the String summary and the
 * typed projection tell the same story rather than contradicting each other.
 */
public class SummarizersArePinnedAtTheirWireValuesTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    @BeforeClass
    public static void bootItemRegistry() {
        // A really registered Item, because the claim below is about what a real stack
        // renders as; a hand-rolled Item would only prove the test agrees with itself.
        Bootstrap.register();
    }

    // ---- Summ.angle: the wire byte is unsigned ------------------------------

    /**
     * 1.8.9 packs an angle as {@code degrees*256/360} into a SIGNED byte, so every
     * bearing at or past 180 degrees arrives with its high bit set: raw -128 IS 180,
     * raw -64 IS 270. Widen it signed and the caller reads a negative bearing, i.e.
     * concludes the entity faces the opposite way; nothing downstream can recover the
     * lost half turn, because -90 and 270 are indistinguishable once printed.
     */
    @Test
    public void angleWidensTheWireByteUnsignedSoTheSecondHalfTurnIsNotNegative() {
        assertEquals("raw 0 is the domain floor", 0.0, Summ.angle((byte) 0), 0.0001);
        assertEquals("raw 64 is a quarter turn", 90.0, Summ.angle((byte) 64), 0.0001);
        assertEquals("raw 127 is the largest bearing a signed byte spells positively",
                178.59375, Summ.angle((byte) 127), 0.0001);
        assertEquals("raw -128 is the half turn, not a backwards half turn",
                180.0, Summ.angle((byte) -128), 0.0001);
        assertEquals("raw -64 is three quarters of a turn, not a quarter turn backwards",
                270.0, Summ.angle((byte) -64), 0.0001);
        assertEquals("raw -1 is one step short of a full turn",
                358.59375, Summ.angle((byte) -1), 0.0001);
    }

    /**
     * The whole byte domain must land inside one turn and rise with the UNSIGNED wire
     * value. Sampling only 0..127 cannot see this: that half is where a signed widening
     * happens to agree, which is precisely why the convention went unverified.
     */
    @Test
    public void angleCoversOneWholeTurnAcrossEveryWireByte() {
        double previous = -1.0;
        for (int unsignedWireValue = 0; unsignedWireValue <= 255; unsignedWireValue++) {
            double degrees = Summ.angle((byte) unsignedWireValue);
            assertTrue("wire byte " + unsignedWireValue + " must name a bearing inside one turn,"
                    + " else the caller reads a direction that does not exist: " + degrees,
                    degrees >= 0.0 && degrees < 360.0);
            assertTrue("bearings must rise with the unsigned wire value, or two distinct wire"
                    + " values name the same direction at " + unsignedWireValue,
                    degrees > previous);
            previous = degrees;
        }
    }

    /**
     * The same convention through the caller-facing path: S18 is what re-anchors an
     * entity's facing after a teleport, and both halves of the seam must agree.
     */
    @Test
    public void entityTeleportPastTheHalfTurnReportsAForwardBearing() {
        S18PacketEntityTeleport p = new S18PacketEntityTeleport(
                42, 320, 2048, -640, (byte) -64, (byte) -128, true);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("the round-trip must really have decoded position too, or the bearing"
                + " assertions below are read off a packet nobody populated",
                10.0, ((Number) m.get("x")).doubleValue(), 0.001);
        assertEquals("yaw -64 on the wire means the entity faces 270, not -90",
                270.0, ((Number) m.get("yaw")).doubleValue(), 0.05);
        assertEquals("pitch -128 on the wire means 180, not -180",
                180.0, ((Number) m.get("pitch")).doubleValue(), 0.05);
        String s = reg.summarize(p);
        assertTrue("the String view a human reads must carry the same bearing as the typed"
                + " view a tool reads: " + s, s.contains("yaw=270.0") && s.contains("pitch=180.0"));
    }

    // ---- InventorySummarizers.item(): a stocked slot is not an empty one ----

    /**
     * {@code item()} is the single formatter behind every ItemStack this subsystem
     * surfaces. If a real stack renders as the absent-stack word, the caller cannot
     * tell a stocked slot from an empty one -- the exact conflation the honesty
     * contract exists to prevent.
     */
    @Test
    public void setSlotRendersARealStackAsItsContentsNotAsEmpty() {
        Item stick = Item.getByNameOrId("stick");
        assertNotNull("the item registry must be booted, or this test proves nothing", stick);
        S2FPacketSetSlot p = new S2FPacketSetSlot(2, 36, new ItemStack(stick, 3, 0));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("a slot holding three sticks must read as that stack",
                "3xitem.stick@0", m.get("item"));
        assertNotEquals("a slot with contents must never borrow the word that means absent",
                "empty", m.get("item"));
    }

    /**
     * The click's stack is WHAT the player moved; collapsing it hides the whole action.
     * The meta segment is pinned too, since damage/variant is what distinguishes two
     * otherwise identical stacks.
     */
    @Test
    public void clickWindowNamesTheClickedStackIncludingItsMeta() {
        C0EPacketClickWindow p = new C0EPacketClickWindow(
                1, 9, 0, 0, new ItemStack(Item.getByNameOrId("stick"), 1, 3), (short) 5);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("the moved stack, meta included, or the caller cannot say which stack"
                + " the player picked up", "1xitem.stick@3", m.get("item"));
        String s = reg.summarize(p);
        assertTrue("and the String view must name the same stack: " + s,
                s.contains("item=1xitem.stick@3"));
    }

    /**
     * The positive counterpart to the two tests above: "empty" is a true answer, but
     * only for a genuinely absent stack. Both readings must be reachable or the word
     * carries no information at all.
     */
    @Test
    public void onlyAnAbsentStackReadsAsEmpty() {
        S2FPacketSetSlot p = new S2FPacketSetSlot(2, 36, null);
        assertEquals("a null stack is the one case that honestly means empty",
                "empty", reg.projectStructured(p).get("item"));
    }

    // ---- FxMoveSummarizers: onGround has two polarities ---------------------

    /**
     * onGround is the wire's own boolean on every S14 subclass. Reported as always
     * true, every falling, jumping and knocked-back entity reads as standing on the
     * ground -- on the highest-frequency packet family in the game.
     */
    @Test
    public void moveFamilyReportsTheAirborneSideOfOnGroundToo() {
        S14PacketEntity.S15PacketEntityRelMove airborneMove =
                new S14PacketEntity.S15PacketEntityRelMove(99, (byte) 16, (byte) 8, (byte) 0, false);
        assertEquals("a rel-move sent while airborne must not read as grounded",
                Boolean.FALSE, reg.projectStructured(airborneMove).get("onGround"));
        String s = reg.summarize(airborneMove);
        assertTrue("and the String view must agree with the typed view rather than"
                + " contradict it: " + s, s.contains("ground=false"));

        S14PacketEntity.S16PacketEntityLook airborneLook =
                new S14PacketEntity.S16PacketEntityLook(3, (byte) 64, (byte) 0, false);
        assertEquals("a look-only packet sent while airborne must not read as grounded",
                Boolean.FALSE, reg.projectStructured(airborneLook).get("onGround"));

        S14PacketEntity.S17PacketEntityLookMove airborneLookMove =
                new S14PacketEntity.S17PacketEntityLookMove(
                        7, (byte) 32, (byte) 0, (byte) 0, (byte) 64, (byte) 0, false);
        assertEquals("a look+move sent while airborne must not read as grounded",
                Boolean.FALSE, reg.projectStructured(airborneLookMove).get("onGround"));

        // the grounded counterpart: the field is read off the wire, not defaulted either way
        S14PacketEntity.S15PacketEntityRelMove grounded =
                new S14PacketEntity.S15PacketEntityRelMove(99, (byte) 16, (byte) 8, (byte) 0, true);
        assertEquals("while a grounded rel-move must still read as grounded",
                Boolean.TRUE, reg.projectStructured(grounded).get("onGround"));
    }

    // ---- MovementSummarizers: the two input axes are not interchangeable ----

    /**
     * C0CPacketInput carries two distinct axes (ctor order is strafe, forward; strafe
     * is positive-left, forward positive-forward). Mislabelled, the tail a human or an
     * LLM reads turns "walking backward" into "strafing left" while the typed view
     * still says the truth -- one summarizer's two halves contradicting each other.
     */
    @Test
    public void inputSummaryLabelsEachAxisWithTheValueThatCameFromIt() {
        C0CPacketInput p = new C0CPacketInput(0.25f, -0.75f, true, false);
        String s = reg.summarize(p);
        assertTrue("backing up at -0.75 must be reported on the forward axis: " + s,
                s.contains("fwd=-0.75"));
        assertTrue("strafing at 0.25 must be reported on the strafe axis: " + s,
                s.contains("strafe=0.25"));
        assertFalse("the strafe magnitude must never be printed as the forward axis, or the"
                + " reader infers a movement the player never made: " + s, s.contains("fwd=0.25"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("the typed view must agree with the String view on forward",
                -0.75, ((Number) m.get("forward")).doubleValue(), 0.001);
        assertEquals("and on strafe", 0.25, ((Number) m.get("strafe")).doubleValue(), 0.001);
    }

    // ---- EntitySummarizers: velocity only exists when objectData > 0 --------

    /**
     * {@code S0EPacketSpawnObject.readPacketData} reads speedX/Y/Z ONLY when objectData
     * is above zero. For objectData == 0 -- dropped items, arrows with no shooter, most
     * spawned objects -- those shorts were never on the wire, so emitting vel*=0 tells
     * the caller "this object is stationary", a claim the server never made.
     */
    @Test
    public void spawnObjectWithoutObjectDataMustNotClaimTheObjectIsStationary() {
        Map<String, Object> m = reg.projectStructured(decodedFromWire(0, 8000, -4000, 2000));
        assertEquals("the round-trip must really have decoded the packet, or absence below"
                + " proves nothing", 10.0, ((Number) m.get("x")).doubleValue(), 0.001);
        assertEquals("objectData is itself on the wire and must be reported",
                0, ((Number) m.get("data")).intValue());
        assertFalse("objectData=0 sent no velocity: velX must be absent, not zero",
                m.containsKey("velX"));
        assertFalse("objectData=0 sent no velocity: velY must be absent, not zero",
                m.containsKey("velY"));
        assertFalse("objectData=0 sent no velocity: velZ must be absent, not zero",
                m.containsKey("velZ"));
    }

    /** The counterpart: when the wire did carry velocity, the exact values must surface. */
    @Test
    public void spawnObjectWithObjectDataReportsTheVelocityThatWasOnTheWire() {
        Map<String, Object> m = reg.projectStructured(decodedFromWire(1, 8000, -4000, 2000));
        assertEquals("objectData=1 sent velocity, so velX must carry it",
                1.0, ((Number) m.get("velX")).doubleValue(), 0.001);
        assertEquals("and velY", -0.5, ((Number) m.get("velY")).doubleValue(), 0.001);
        assertEquals("and velZ", 0.25, ((Number) m.get("velZ")).doubleValue(), 0.001);
    }

    /**
     * Encode then decode through the real PacketBuffer path, so the projection sees
     * exactly what a server could have sent -- including the fields the writer SKIPS
     * when objectData is 0. Setting the speeds before writing is deliberate: it proves
     * the wire format itself drops them, rather than the test merely leaving them unset.
     */
    private static S0EPacketSpawnObject decodedFromWire(
            int objectData, int speedX, int speedY, int speedZ) {
        S0EPacketSpawnObject sent = new S0EPacketSpawnObject();
        sent.func_149002_g(objectData);
        sent.setX(320);
        sent.setY(2048);
        sent.setZ(-640);
        sent.setSpeedX(speedX);
        sent.setSpeedY(speedY);
        sent.setSpeedZ(speedZ);
        S0EPacketSpawnObject received = new S0EPacketSpawnObject();
        try {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            sent.writePacketData(buf);
            received.readPacketData(buf);
        } catch (IOException e) {
            throw new AssertionError("the S0E wire round-trip must not fail", e);
        }
        return received;
    }

    // ---- WorldSummarizers: difficulty lock has two polarities ---------------

    /**
     * S41's locked flag tells the caller whether it MAY change difficulty, and on real
     * servers it arrives false in the overwhelming majority of cases. Reported as always
     * locked, every server looks unchangeable and the typed view contradicts the String
     * view, which prints the real getter.
     */
    @Test
    public void difficultyReportsTheUnlockedSideToo() {
        S41PacketServerDifficulty unlocked =
                new S41PacketServerDifficulty(EnumDifficulty.NORMAL, false);
        Map<String, Object> m = reg.projectStructured(unlocked);
        assertEquals("the difficulty itself must survive alongside the lock flag",
                "NORMAL", m.get("difficulty"));
        assertEquals("an unlocked server must not be reported as locked, or the caller"
                + " believes it cannot change difficulty", Boolean.FALSE, m.get("locked"));
        String s = reg.summarize(unlocked);
        assertTrue("and the String view must agree with the typed view: " + s,
                s.contains("locked=false"));

        S41PacketServerDifficulty locked =
                new S41PacketServerDifficulty(EnumDifficulty.HARD, true);
        assertEquals("while a genuinely locked server must still read as locked",
                Boolean.TRUE, reg.projectStructured(locked).get("locked"));
    }
}
