package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.ChatComponentText;
import org.junit.Test;

public class PacketSummarizerRegistryTest {

    private final PacketSummarizerRegistry reg = PacketSummarizerRegistry.defaults();

    @Test
    public void posLookCoordsSurface() {
        S08PacketPlayerPosLook p = new S08PacketPlayerPosLook(
                10.5, 64.0, -20.25, 90f, 0f, EnumSet.noneOf(S08PacketPlayerPosLook.EnumFlags.class));
        String s = reg.summarize(p);
        assertTrue(s, s.contains("posLook"));
        assertTrue(s, s.contains("10.50"));
        assertTrue(s, s.contains("64.00"));
        assertTrue(s, s.contains("-20.25"));
        assertTrue(s, s.contains("yaw=90"));
    }

    @Test
    public void timeShowsFrozenWhenNegative() {
        // ctor(totalWorldTime, totalTime, doDayLightCycle); worldTime encodes cycle sign
        S03PacketTimeUpdate on = new S03PacketTimeUpdate(1000L, 6000L, true);
        S03PacketTimeUpdate frozen = new S03PacketTimeUpdate(1000L, 6000L, false);
        assertTrue(reg.summarize(on).contains("cycle=on"));
        assertTrue(reg.summarize(frozen).contains("cycle=frozen"));
    }

    @Test
    public void keepAliveShowsId() {
        assertTrue(reg.summarize(new S00PacketKeepAlive(4242)).contains("id=4242"));
    }

    @Test
    public void chatTextSurfaces() {
        S02PacketChat p = new S02PacketChat(new ChatComponentText("hello world"), (byte) 0);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("chat"));
        assertTrue(s, s.contains("hello world"));
    }

    @Test
    public void velocityConvertsFixedPoint() {
        // ctor stores motion*8000 (clamped); summarizer divides by 8000 to recover blocks/tick
        S12PacketEntityVelocity p = new S12PacketEntityVelocity(42, 1.0, 0.0, -0.5);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("eid=42"));
        assertTrue(s, s.contains("1.000"));
        assertTrue(s, s.contains("-0.500"));
    }

    @Test
    public void c03SubclassResolvesViaFallback() {
        C03PacketPlayer.C04PacketPlayerPosition p =
                new C03PacketPlayer.C04PacketPlayerPosition(1.0, 2.0, 3.0, true);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("move"));
        assertTrue(s, s.contains("1.00"));
        assertTrue("nested C04 must resolve to the C03 summarizer via prefix fallback: " + s,
                s.contains("flags="));
    }

    @Test
    public void unregisteredFallsToGeneric() {
        Object stub = new S00PacketKeepAlive(); // registered — use something NOT registered:
        // A plain object stands in for an unregistered packet class.
        String s = reg.summarize(new Object());
        assertEquals("Object", s);
        assertNotNull(reg.summarize(stub));
    }

    @Test
    public void throwingSummarizerIsContained() {
        PacketSummarizerRegistry r = new PacketSummarizerRegistry(new GenericPacketSummarizer());
        r.registerFallback(new PacketSummarizer() {
            @Override public boolean handles(String cn) { return true; }
            @Override public String summarize(Object p) { throw new RuntimeException("boom"); }
        });
        String s = r.summarize(new Object());
        assertNotNull("throwing summarizer must degrade to generic, never propagate", s);
        assertEquals("Object", s);
    }
}
