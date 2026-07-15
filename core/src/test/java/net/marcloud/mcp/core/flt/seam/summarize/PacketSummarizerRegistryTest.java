package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.EnumSet;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S42PacketCombatEvent;
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
    public void healthFieldsSurface() {
        S06PacketUpdateHealth p = new S06PacketUpdateHealth(17.5f, 20, 4.25f);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("health"));
        assertTrue(s, s.contains("hp=17.50"));
        assertTrue(s, s.contains("food=20"));
        assertTrue(s, s.contains("sat=4.25"));
    }

    @Test
    public void combatDeathMessageSurfaces() throws Exception {
        S42PacketCombatEvent p = new S42PacketCombatEvent();
        p.eventType = S42PacketCombatEvent.Event.ENTITY_DIED;
        p.deathMessage = "Steve was slain by Zombie";
        String s = reg.summarize(p);
        assertTrue(s, s.contains("event=ENTITY_DIED"));
        assertTrue(s, s.contains("death=\"Steve was slain by Zombie\""));
    }

    @Test
    public void combatNonDeathHasNoMessage() {
        S42PacketCombatEvent p = new S42PacketCombatEvent();
        p.eventType = S42PacketCombatEvent.Event.END_COMBAT;
        String s = reg.summarize(p);
        assertTrue(s, s.contains("event=END_COMBAT"));
        assertTrue("non-death combat must not fabricate a death message: " + s,
                !s.contains("death="));
    }

    @Test
    public void playerListAddNamesSurface() throws Exception {
        S38PacketPlayerListItem p = newPlayerList(S38PacketPlayerListItem.Action.ADD_PLAYER);
        addEntry(p, "Steve");
        addEntry(p, "Alex");
        String s = reg.summarize(p);
        assertTrue(s, s.contains("action=ADD_PLAYER"));
        assertTrue(s, s.contains("count=2"));
        assertTrue(s, s.contains("names=Steve,Alex"));
    }

    @Test
    public void playerListRemoveHasNoNames() throws Exception {
        // On the wire a REMOVE entry has GameProfile(uuid, null) — no name.
        S38PacketPlayerListItem p = newPlayerList(S38PacketPlayerListItem.Action.REMOVE_PLAYER);
        addEntryNoName(p);
        String s = reg.summarize(p);
        assertTrue(s, s.contains("action=REMOVE_PLAYER"));
        assertTrue(s, s.contains("count=1"));
        assertTrue("REMOVE must not carry a names= field (no name on wire): " + s,
                !s.contains("names="));
    }

    // ---- S38 construction helpers (server ctors are unusable headless) ------

    private static S38PacketPlayerListItem newPlayerList(
            S38PacketPlayerListItem.Action action) throws Exception {
        S38PacketPlayerListItem p = new S38PacketPlayerListItem();
        Field f = S38PacketPlayerListItem.class.getDeclaredField("action");
        f.setAccessible(true);
        f.set(p, action);
        return p;
    }

    private static void addEntry(S38PacketPlayerListItem p, String name) {
        GameProfile profile = new GameProfile(java.util.UUID.randomUUID(), name);
        p.getEntries().add(p.new AddPlayerData(profile, 0, null, null));
    }

    private static void addEntryNoName(S38PacketPlayerListItem p) {
        GameProfile profile = new GameProfile(java.util.UUID.randomUUID(), null);
        p.getEntries().add(p.new AddPlayerData(profile, 0, null, null));
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
