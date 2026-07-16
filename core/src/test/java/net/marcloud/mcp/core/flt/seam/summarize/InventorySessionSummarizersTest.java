package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S1FPacketSetExperience;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S31PacketWindowProperty;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;

/**
 * Teeth for the W2.3 inventory + session A-tier summarizers, on the packets with
 * test-friendly public constructors. Scoreboard S3C/S3E and inventory packets
 * needing Score/List objects are verified at compile time (the summarizers
 * reference their getters); here we assert real typed round-trips.
 */
public class InventorySessionSummarizersTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    @Test
    public void setSlotSurfacesObfuscatedGetters() {
        S2FPacketSetSlot p = new S2FPacketSetSlot(2, 36, null);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(2, ((Number) m.get("windowId")).intValue());
        assertEquals(36, ((Number) m.get("slot")).intValue());
        assertEquals("empty", m.get("item"));
    }

    @Test
    public void windowPropertySurfacesTriple() {
        S31PacketWindowProperty p = new S31PacketWindowProperty(3, 0, 140);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(3, ((Number) m.get("windowId")).intValue());
        assertEquals(0, ((Number) m.get("property")).intValue());
        assertEquals(140, ((Number) m.get("value")).intValue());
    }

    @Test
    public void clickWindowSurfacesAllFields() {
        C0EPacketClickWindow p = new C0EPacketClickWindow(1, 9, 0, 0, null, (short) 5);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(1, ((Number) m.get("windowId")).intValue());
        assertEquals(9, ((Number) m.get("slot")).intValue());
        assertEquals(5, ((Number) m.get("actionNumber")).intValue());
        assertEquals("empty", m.get("item"));
    }

    @Test
    public void joinGameSurfacesWorldContext() {
        S01PacketJoinGame p = new S01PacketJoinGame(
                7, WorldSettings.GameType.SURVIVAL, false, 0, EnumDifficulty.NORMAL, 20,
                WorldType.DEFAULT, false);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(7, ((Number) m.get("eid")).intValue());
        assertEquals("SURVIVAL", m.get("gameMode"));
        assertEquals(0, ((Number) m.get("dimension")).intValue());
        assertEquals("NORMAL", m.get("difficulty"));
        assertEquals(20, ((Number) m.get("maxPlayers")).intValue());
    }

    @Test
    public void experienceSurfacesLevelBarTotal() {
        // ctor is (bar, totalExperience, level) — total before level
        S1FPacketSetExperience p = new S1FPacketSetExperience(0.5f, 1395, 30);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(30, ((Number) m.get("level")).intValue());
        assertEquals(0.5, ((Number) m.get("bar")).doubleValue(), 0.001);
        assertEquals(1395, ((Number) m.get("total")).intValue());
    }

    @Test
    public void titleWithMessageOmitsTimes() {
        S45PacketTitle p = new S45PacketTitle(
                S45PacketTitle.Type.TITLE, new ChatComponentText("Level Up"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("TITLE", m.get("type"));
        assertEquals("Level Up", m.get("message"));
        assertTrue("a message title carries no times", !m.containsKey("fadeIn"));
    }

    @Test
    public void titleTimesOmitsMessage() {
        S45PacketTitle p = new S45PacketTitle(2, 20, 3); // TIMES ctor
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("TIMES", m.get("type"));
        assertEquals(2, ((Number) m.get("fadeIn")).intValue());
        assertTrue("a times title carries no message", !m.containsKey("message"));
    }

    @Test
    public void clearTitleMustNotFabricateTimes() {
        // S45PacketTitle.readPacketData reads times ONLY for Type.TIMES; a CLEAR/RESET
        // carries neither message nor times. The pre-fix code branched on message==null
        // and so emitted fadeIn/stay/fadeOut=0 for CLEAR — three invented defaults.
        S45PacketTitle p = new S45PacketTitle(S45PacketTitle.Type.CLEAR, null);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("CLEAR", m.get("type"));
        assertTrue("CLEAR carries no times: fadeIn must be absent", !m.containsKey("fadeIn"));
        assertTrue("CLEAR carries no times: stay must be absent", !m.containsKey("stay"));
        assertTrue("CLEAR carries no times: fadeOut must be absent", !m.containsKey("fadeOut"));
        assertTrue("CLEAR carries no message", !m.containsKey("message"));
    }

    @Test
    public void clientStatusSurfacesEnum() {
        C16PacketClientStatus p = new C16PacketClientStatus(
                C16PacketClientStatus.EnumState.PERFORM_RESPAWN);
        assertTrue(reg.summarize(p).contains("PERFORM_RESPAWN"));
        assertEquals("PERFORM_RESPAWN", reg.projectStructured(p).get("status"));
    }
}
