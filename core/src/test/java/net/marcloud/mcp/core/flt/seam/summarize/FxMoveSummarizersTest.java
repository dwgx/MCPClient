package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.network.play.server.S28PacketEffect;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.util.BlockPos;
import org.junit.Test;

/**
 * Teeth for the W3 B-tier FX + move-delta summarizers, on the packets with
 * primitive public constructors. Verifies the modest typed projection and the two
 * recurring decode conventions: block-anim/effect coords + the S14 family's
 * delta/32 + angle*360/256.
 */
public class FxMoveSummarizersTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    @Test
    public void blockBreakAnimSurfacesBreakerAndProgress() {
        S25PacketBlockBreakAnim p = new S25PacketBlockBreakAnim(55, new BlockPos(1, 2, 3), 7);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(55, ((Number) m.get("breakerEid")).intValue());
        assertEquals(1, ((Number) m.get("x")).intValue());
        assertEquals(7, ((Number) m.get("progress")).intValue());
    }

    @Test
    public void worldEffectSurfacesIdAndData() {
        S28PacketEffect p = new S28PacketEffect(2001, new BlockPos(4, 5, 6), 42, false);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(2001, ((Number) m.get("effectId")).intValue());
        assertEquals(42, ((Number) m.get("data")).intValue());
        assertEquals(Boolean.FALSE, m.get("serverwide"));
    }

    @Test
    public void soundEffectSurfacesNameAndPos() {
        S29PacketSoundEffect p = new S29PacketSoundEffect("mob.zombie.say", 10.0, 64.0, -8.0, 1.0f, 0.5f);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals("mob.zombie.say", m.get("sound"));
        assertEquals(10.0, ((Number) m.get("x")).doubleValue(), 0.001);
        assertEquals(0.5, ((Number) m.get("pitch")).doubleValue(), 0.02);
    }

    @Test
    public void relMoveDecodesDeltaOver32AndHasNoLook() {
        // 16/32 = 0.5 block delta; RelMove carries no look
        S14PacketEntity.S15PacketEntityRelMove p =
                new S14PacketEntity.S15PacketEntityRelMove(99, (byte) 16, (byte) 0, (byte) -32, true);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(0.5, ((Number) m.get("dx")).doubleValue(), 0.001);
        assertEquals(-1.0, ((Number) m.get("dz")).doubleValue(), 0.001);
        assertEquals(Boolean.TRUE, m.get("onGround"));
        assertTrue("rel-move carries no look", !m.containsKey("yaw"));
    }

    @Test
    public void lookOnlyPacketMustNotFabricatePositionDeltas() {
        // S16PacketEntityLook.readPacketData reads ONLY yaw/pitch/onGround — it never
        // touches posX/Y/Z. Emitting dx/dy/dz=0 would assert "did not move", a claim
        // the wire never made. Fails on the pre-fix code, which emitted them always.
        S14PacketEntity.S16PacketEntityLook p =
                new S14PacketEntity.S16PacketEntityLook(3, (byte) 64, (byte) 0, true);
        Map<String, Object> m = reg.projectStructured(p);
        assertTrue("look-only packet carries no position: dx must be absent", !m.containsKey("dx"));
        assertTrue("look-only packet carries no position: dy must be absent", !m.containsKey("dy"));
        assertTrue("look-only packet carries no position: dz must be absent", !m.containsKey("dz"));
        assertEquals("but it does carry look", 90.0, ((Number) m.get("yaw")).doubleValue(), 0.5);
        assertEquals(Boolean.TRUE, m.get("onGround"));
        assertTrue("the String summary must not claim a delta either",
                !reg.summarize(p).contains("dPos"));
    }

    @Test
    public void baseEntityPacketCarriesNothingButItsIdentity() {
        // Base S14PacketEntity.readPacketData reads ONLY entityId — no pos, no look,
        // no onGround. Its projection must therefore be empty, not zeros.
        S14PacketEntity p = new S14PacketEntity(11);
        Map<String, Object> m = reg.projectStructured(p);
        assertTrue("base S14 carries no position", !m.containsKey("dx"));
        assertTrue("base S14 carries no look", !m.containsKey("yaw"));
        assertTrue("base S14 carries no onGround", !m.containsKey("onGround"));
    }

    @Test
    public void lookMoveDecodesDeltaAndAngle() {
        // full look+move: yaw byte 64 -> 90 deg
        S14PacketEntity.S17PacketEntityLookMove p =
                new S14PacketEntity.S17PacketEntityLookMove(7, (byte) 32, (byte) 0, (byte) 0, (byte) 64, (byte) 0, false);
        Map<String, Object> m = reg.projectStructured(p);
        assertEquals(1.0, ((Number) m.get("dx")).doubleValue(), 0.001);
        assertEquals(90.0, ((Number) m.get("yaw")).doubleValue(), 0.5);
    }
}
