package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0CPacketInput;
import net.minecraft.network.play.server.S05PacketSpawnPosition;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S41PacketServerDifficulty;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;

/**
 * Teeth for the W2 world + movement A-tier summarizers: each must produce the exact
 * String format and the typed {@code project()} fields. Packets with public ctors
 * are built directly; wire-only packets are round-tripped through
 * {@link PacketBuffer} (the real decode path), so a wrong getter or scaling FAILS.
 */
public class WorldMovementSummarizersTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    // ---- constructor-built server packets -----------------------------------

    @Test
    public void spawnPositionSurfacesCoords() {
        S05PacketSpawnPosition p = new S05PacketSpawnPosition(new BlockPos(10, 64, -20));
        assertTrue(reg.summarize(p).contains("spawnPos at=10,64,-20"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(10, ((Number) m.get("x")).intValue());
        assertEquals(64, ((Number) m.get("y")).intValue());
        assertEquals(-20, ((Number) m.get("z")).intValue());
    }

    @Test
    public void respawnSurfacesDimAndMode() {
        S07PacketRespawn p = new S07PacketRespawn(
                -1, EnumDifficulty.HARD, WorldType.DEFAULT, WorldSettings.GameType.CREATIVE);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("dim=-1"));
        assertTrue(s, s.contains("diff=HARD"));
        assertTrue(s, s.contains("mode=CREATIVE"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(-1, ((Number) m.get("dimension")).intValue());
        assertEquals("HARD", m.get("difficulty"));
        assertEquals("CREATIVE", m.get("gameMode"));
    }

    @Test
    public void difficultySurfacesLock() {
        S41PacketServerDifficulty p = new S41PacketServerDifficulty(EnumDifficulty.PEACEFUL, true);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("PEACEFUL", m.get("difficulty"));
        assertEquals(Boolean.TRUE, m.get("locked"));
    }

    @Test
    public void entityTeleportDecodesFixedPoint() {
        // ctor takes raw wire ints: pos*32, angle as byte(deg*256/360).
        // 320/32 = 10.0 ; 2048/32 = 64.0 ; yaw byte 64 -> 64*360/256 = 90.0
        S18PacketEntityTeleport p = new S18PacketEntityTeleport(
                42, 320, 2048, -640, (byte) 64, (byte) 0, true);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(42, ((Number) m.get("eid")).intValue());
        assertEquals(10.0, ((Number) m.get("x")).doubleValue(), 0.001);
        assertEquals(64.0, ((Number) m.get("y")).doubleValue(), 0.001);
        assertEquals(-20.0, ((Number) m.get("z")).doubleValue(), 0.001);
        assertEquals(90.0, ((Number) m.get("yaw")).doubleValue(), 0.5);
        assertEquals(Boolean.TRUE, m.get("onGround"));
    }

    // ---- client packets ------------------------------------------------------

    @Test
    public void diggingSurfacesStatusAndPos() {
        C07PacketPlayerDigging p = new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, new BlockPos(1, 2, 3), EnumFacing.UP);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("START_DESTROY_BLOCK", m.get("status"));
        assertEquals(1, ((Number) m.get("x")).intValue());
        assertEquals("UP", m.get("face"));
    }

    @Test
    public void blockPlacementSurfacesDirAndOffsets() {
        C08PacketPlayerBlockPlacement p = new C08PacketPlayerBlockPlacement(
                new BlockPos(5, 6, 7), 1, null, 0.5f, 0.25f, 0.75f);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(5, ((Number) m.get("x")).intValue());
        assertEquals(1, ((Number) m.get("direction")).intValue());
        assertEquals(0.5, ((Number) m.get("offsetX")).doubleValue(), 0.001);
    }

    @Test
    public void inputSurfacesMovementIntent() {
        C0CPacketInput p = new C0CPacketInput(0.98f, -0.5f, true, false);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("jump=true"));
        assertTrue(s, s.contains("sneak=false"));
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(-0.5, ((Number) m.get("forward")).doubleValue(), 0.001);
        assertEquals(0.98, ((Number) m.get("strafe")).doubleValue(), 0.001);
        assertEquals(Boolean.TRUE, m.get("jumping"));
        assertEquals(Boolean.FALSE, m.get("sneaking"));
    }

    /**
     * The two halves of the SPI are independent: a packet with only a String
     * summarizer must project null, while still summarizing. (The previous version of
     * this test asserted only that summarize() was non-blank — which passes on
     * pre-W2.1 code because the generic summarizer returns the simple name for
     * anything, i.e. it was vacuous.)
     */
    @Test
    public void stringOnlySummarizerProjectsNullWhileStillSummarizing() {
        net.minecraft.network.play.server.S00PacketKeepAlive p =
                new net.minecraft.network.play.server.S00PacketKeepAlive(7);
        assertNull("KeepAlive has no project() override -> no typed view",
                reg.projectStructured(p));
        assertFalse("but it still has a String summary", reg.summarize(p).isBlank());
    }
}
