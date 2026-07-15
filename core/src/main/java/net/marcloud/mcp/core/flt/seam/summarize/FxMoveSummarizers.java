package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Map;

import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S24PacketBlockAction;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.network.play.server.S28PacketEffect;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.network.play.server.S2APacketParticles;

/**
 * B-tier (category-batch) summarizers for the high-frequency FX and entity-move
 * families. B-tier gets a MODEST typed projection — the few fields that matter —
 * rather than the full A-tier treatment, because these packets are numerous and
 * low individual value (sound/particle spam, per-tick entity deltas).
 *
 * <ul>
 *   <li>FX: S24 block-action, S25 block-break-anim, S2A particles, S28 world-effect,
 *       S29 sound-effect.</li>
 *   <li>Move-delta: the S14 entity-move family — S15 rel-move, S16 look, S17
 *       look+move — sharing one summarizer keyed by class name. Deltas decode as
 *       {@code raw/32} blocks and angles as {@code raw*360/256} degrees ({@link Summ}).
 *       Their entity id is only reachable via {@code getEntity(World)} (needs a live
 *       world), so we honestly surface the DELTAS and onGround, not a fabricated id.</li>
 * </ul>
 */
final class FxMoveSummarizers {

    private FxMoveSummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new BlockAction(), "net.minecraft.network.play.server.S24PacketBlockAction");
        r.register(new BlockBreakAnim(), "net.minecraft.network.play.server.S25PacketBlockBreakAnim");
        r.register(new Particles(), "net.minecraft.network.play.server.S2APacketParticles");
        r.register(new WorldEffect(), "net.minecraft.network.play.server.S28PacketEffect");
        r.register(new SoundEffect(), "net.minecraft.network.play.server.S29PacketSoundEffect");
        // The S14 move family: base + three nested subclasses, each an exact name.
        EntityMove move = new EntityMove();
        r.register(move, "net.minecraft.network.play.server.S14PacketEntity");
        r.register(move, "net.minecraft.network.play.server.S14PacketEntity$S15PacketEntityRelMove");
        r.register(move, "net.minecraft.network.play.server.S14PacketEntity$S16PacketEntityLook");
        r.register(move, "net.minecraft.network.play.server.S14PacketEntity$S17PacketEntityLookMove");
    }

    static final class BlockAction implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S24PacketBlockAction".equals(cn);
        }
        @Override public String summarize(Object p) {
            S24PacketBlockAction s = (S24PacketBlockAction) p;
            return "blockAction at=" + Summ.pos(s.getBlockPosition())
                    + " d1=" + s.getData1() + " d2=" + s.getData2();
        }
        @Override public Map<String, Object> project(Object p) {
            S24PacketBlockAction s = (S24PacketBlockAction) p;
            net.minecraft.util.BlockPos b = s.getBlockPosition();
            PacketView.Builder v = PacketView.of();
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("data1", s.getData1()).put("data2", s.getData2()).buildMap();
        }
    }

    static final class BlockBreakAnim implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S25PacketBlockBreakAnim".equals(cn);
        }
        @Override public String summarize(Object p) {
            S25PacketBlockBreakAnim s = (S25PacketBlockBreakAnim) p;
            return "blockBreakAnim breaker=" + s.getBreakerId() + " at=" + Summ.pos(s.getPosition())
                    + " progress=" + s.getProgress();
        }
        @Override public Map<String, Object> project(Object p) {
            S25PacketBlockBreakAnim s = (S25PacketBlockBreakAnim) p;
            net.minecraft.util.BlockPos b = s.getPosition();
            PacketView.Builder v = PacketView.of().put("breakerEid", s.getBreakerId());
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("progress", s.getProgress()).buildMap();
        }
    }

    static final class Particles implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S2APacketParticles".equals(cn);
        }
        @Override public String summarize(Object p) {
            S2APacketParticles s = (S2APacketParticles) p;
            String type = s.getParticleType() == null ? "?" : s.getParticleType().name();
            return "particles " + type + " at=" + Summ.f1(s.getXCoordinate()) + ","
                    + Summ.f1(s.getYCoordinate()) + "," + Summ.f1(s.getZCoordinate())
                    + " n=" + s.getParticleCount();
        }
        @Override public Map<String, Object> project(Object p) {
            S2APacketParticles s = (S2APacketParticles) p;
            return PacketView.of()
                    .put("type", s.getParticleType() == null ? "?" : s.getParticleType().name())
                    .putRounded("x", s.getXCoordinate(), 1).putRounded("y", s.getYCoordinate(), 1)
                    .putRounded("z", s.getZCoordinate(), 1).put("count", s.getParticleCount())
                    .buildMap();
        }
    }

    static final class WorldEffect implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S28PacketEffect".equals(cn);
        }
        @Override public String summarize(Object p) {
            S28PacketEffect s = (S28PacketEffect) p;
            return "worldEffect id=" + s.getSoundType() + " at=" + Summ.pos(s.getSoundPos())
                    + " data=" + s.getSoundData();
        }
        @Override public Map<String, Object> project(Object p) {
            S28PacketEffect s = (S28PacketEffect) p;
            net.minecraft.util.BlockPos b = s.getSoundPos();
            PacketView.Builder v = PacketView.of().put("effectId", s.getSoundType());
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("data", s.getSoundData()).put("serverwide", s.isSoundServerwide()).buildMap();
        }
    }

    static final class SoundEffect implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S29PacketSoundEffect".equals(cn);
        }
        @Override public String summarize(Object p) {
            S29PacketSoundEffect s = (S29PacketSoundEffect) p;
            return "sound " + s.getSoundName() + " at=" + Summ.f1(s.getX()) + ","
                    + Summ.f1(s.getY()) + "," + Summ.f1(s.getZ())
                    + " vol=" + Summ.f2(s.getVolume()) + " pitch=" + Summ.f2(s.getPitch());
        }
        @Override public Map<String, Object> project(Object p) {
            S29PacketSoundEffect s = (S29PacketSoundEffect) p;
            return PacketView.of().put("sound", s.getSoundName())
                    .putRounded("x", s.getX(), 2).putRounded("y", s.getY(), 2).putRounded("z", s.getZ(), 2)
                    .putRounded("volume", s.getVolume(), 2).putRounded("pitch", s.getPitch(), 2)
                    .buildMap();
        }
    }

    /**
     * Shared summarizer for the S14 entity-move family (base + S15/S16/S17). Surfaces
     * the position deltas (raw/32 blocks) and look (raw*360/256 degrees) that the
     * concrete subclass actually carries, plus onGround. The entity id is only
     * resolvable via getEntity(World) (needs a live world), so it is honestly omitted
     * rather than fabricated.
     */
    static final class EntityMove implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return cn != null && cn.startsWith("net.minecraft.network.play.server.S14PacketEntity");
        }
        @Override public String summarize(Object p) {
            S14PacketEntity s = (S14PacketEntity) p;
            boolean hasLook = s.func_149060_h();
            String kind = s.getClass().getSimpleName();
            String base = kind + " dPos=" + Summ.f3(s.func_149062_c() / 32.0) + ","
                    + Summ.f3(s.func_149061_d() / 32.0) + "," + Summ.f3(s.func_149064_e() / 32.0)
                    + " ground=" + s.getOnGround();
            if (hasLook) {
                base += " yaw=" + Summ.f1(Summ.angle(s.func_149066_f()))
                        + " pitch=" + Summ.f1(Summ.angle(s.func_149063_g()));
            }
            return base;
        }
        @Override public Map<String, Object> project(Object p) {
            S14PacketEntity s = (S14PacketEntity) p;
            PacketView.Builder v = PacketView.of()
                    .putRounded("dx", s.func_149062_c() / 32.0, 3)
                    .putRounded("dy", s.func_149061_d() / 32.0, 3)
                    .putRounded("dz", s.func_149064_e() / 32.0, 3)
                    .put("onGround", s.getOnGround());
            if (s.func_149060_h()) {
                v.putRounded("yaw", Summ.angle(s.func_149066_f()), 1)
                        .putRounded("pitch", Summ.angle(s.func_149063_g()), 1);
            }
            return v.buildMap();
        }
    }
}
