package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Map;

import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0CPacketInput;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;
import net.minecraft.network.play.server.S39PacketPlayerAbilities;

/**
 * A-tier summarizers for the MOVEMENT / abilities family — the player's fly/walk
 * abilities (server S39 + client C13 twin), discrete entity actions (sneak/sprint/
 * jump), and the raw movement-input packet. Position packets (S08/S18) live in
 * {@link HighValueSummarizers} / {@link WorldSummarizers}; the C03 movement family
 * has its own fallback in {@link HighValueSummarizers}.
 *
 * <p>Honesty note: the CLIENT {@link C13PacketPlayerAbilities} exposes only the
 * boolean flags — its {@code flySpeed}/{@code walkSpeed} fields have setters but no
 * getters, so we do not fabricate them. The SERVER {@link S39PacketPlayerAbilities}
 * twin does expose both speeds and we surface them.
 */
final class MovementSummarizers {

    private MovementSummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new Abilities(), "net.minecraft.network.play.server.S39PacketPlayerAbilities");
        r.register(new EntityAction(), "net.minecraft.network.play.client.C0BPacketEntityAction");
        r.register(new Input(), "net.minecraft.network.play.client.C0CPacketInput");
        r.register(new ClientAbilities(), "net.minecraft.network.play.client.C13PacketPlayerAbilities");
    }

    static final class Abilities implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S39PacketPlayerAbilities".equals(cn);
        }
        @Override public String summarize(Object p) {
            S39PacketPlayerAbilities s = (S39PacketPlayerAbilities) p;
            return "abilities fly=" + s.isFlying() + " allowFly=" + s.isAllowFlying()
                    + " invuln=" + s.isInvulnerable() + " creative=" + s.isCreativeMode()
                    + " flySpeed=" + Summ.f2(s.getFlySpeed()) + " walkSpeed=" + Summ.f2(s.getWalkSpeed());
        }
        @Override public Map<String, Object> project(Object p) {
            S39PacketPlayerAbilities s = (S39PacketPlayerAbilities) p;
            return PacketView.of()
                    .put("flying", s.isFlying()).put("allowFlying", s.isAllowFlying())
                    .put("invulnerable", s.isInvulnerable()).put("creative", s.isCreativeMode())
                    .putRounded("flySpeed", s.getFlySpeed(), 3).putRounded("walkSpeed", s.getWalkSpeed(), 3)
                    .buildMap();
        }
    }

    static final class ClientAbilities implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C13PacketPlayerAbilities".equals(cn);
        }
        @Override public String summarize(Object p) {
            C13PacketPlayerAbilities s = (C13PacketPlayerAbilities) p;
            // client twin: flySpeed/walkSpeed have no getters — do not fabricate
            return "abilitiesReq fly=" + s.isFlying() + " allowFly=" + s.isAllowFlying()
                    + " invuln=" + s.isInvulnerable() + " creative=" + s.isCreativeMode();
        }
        @Override public Map<String, Object> project(Object p) {
            C13PacketPlayerAbilities s = (C13PacketPlayerAbilities) p;
            return PacketView.of()
                    .put("flying", s.isFlying()).put("allowFlying", s.isAllowFlying())
                    .put("invulnerable", s.isInvulnerable()).put("creative", s.isCreativeMode())
                    .buildMap();
        }
    }

    static final class EntityAction implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C0BPacketEntityAction".equals(cn);
        }
        @Override public String summarize(Object p) {
            C0BPacketEntityAction s = (C0BPacketEntityAction) p;
            return "entityAction " + Summ.enumName(s.getAction()) + " aux=" + s.getAuxData();
        }
        @Override public Map<String, Object> project(Object p) {
            C0BPacketEntityAction s = (C0BPacketEntityAction) p;
            return PacketView.of().put("action", Summ.enumName(s.getAction()))
                    .put("auxData", s.getAuxData()).buildMap();
        }
    }

    static final class Input implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C0CPacketInput".equals(cn);
        }
        @Override public String summarize(Object p) {
            C0CPacketInput s = (C0CPacketInput) p;
            return "input fwd=" + Summ.f2(s.getForwardSpeed()) + " strafe=" + Summ.f2(s.getStrafeSpeed())
                    + " jump=" + s.isJumping() + " sneak=" + s.isSneaking();
        }
        @Override public Map<String, Object> project(Object p) {
            C0CPacketInput s = (C0CPacketInput) p;
            return PacketView.of()
                    .putRounded("forward", s.getForwardSpeed(), 3).putRounded("strafe", s.getStrafeSpeed(), 3)
                    .put("jumping", s.isJumping()).put("sneaking", s.isSneaking())
                    .buildMap();
        }
    }
}
