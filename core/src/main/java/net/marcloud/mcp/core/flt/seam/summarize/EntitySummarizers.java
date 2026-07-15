package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.List;
import java.util.Map;

import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0DPacketCollectItem;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S10PacketSpawnPainting;
import net.minecraft.network.play.server.S11PacketSpawnExperienceOrb;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S1EPacketRemoveEntityEffect;

/**
 * A-tier summarizers for the ENTITY family — spawn (player/mob/object/painting/xp),
 * pickup, destroy, attach/leash, and potion effects. Each emits a compact String
 * and a typed {@link PacketView} projection.
 *
 * <p>Conventions (see {@link Summ}): entity positions are fixed-point {@code *32}
 * ({@link Summ#fp32}), velocities {@code *8000} (blocks/tick), angles byte-packed
 * ({@link Summ#angle}). DataWatcher/metadata lists are surfaced as a COUNT only —
 * decoding a live DataWatcher is out of the reference-free tap's scope. Packets
 * whose entity id is reachable only through {@code getEntity(World)} (S19/S43/S49/
 * S0A) are handled by their own tier where the id is honestly available; this file
 * covers the ones exposing a direct {@code getEntityID()}.
 */
final class EntitySummarizers {

    private EntitySummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new SpawnPlayer(), "net.minecraft.network.play.server.S0CPacketSpawnPlayer");
        r.register(new SpawnMob(), "net.minecraft.network.play.server.S0FPacketSpawnMob");
        r.register(new SpawnObject(), "net.minecraft.network.play.server.S0EPacketSpawnObject");
        r.register(new SpawnPainting(), "net.minecraft.network.play.server.S10PacketSpawnPainting");
        r.register(new SpawnXpOrb(), "net.minecraft.network.play.server.S11PacketSpawnExperienceOrb");
        r.register(new CollectItem(), "net.minecraft.network.play.server.S0DPacketCollectItem");
        r.register(new DestroyEntities(), "net.minecraft.network.play.server.S13PacketDestroyEntities");
        r.register(new EntityAttach(), "net.minecraft.network.play.server.S1BPacketEntityAttach");
        r.register(new Animation(), "net.minecraft.network.play.server.S0BPacketAnimation");
        r.register(new EffectAdd(), "net.minecraft.network.play.server.S1DPacketEntityEffect");
        r.register(new EffectRemove(), "net.minecraft.network.play.server.S1EPacketRemoveEntityEffect");
    }

    static final class SpawnPlayer implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S0CPacketSpawnPlayer".equals(cn);
        }
        @Override public String summarize(Object p) {
            S0CPacketSpawnPlayer s = (S0CPacketSpawnPlayer) p;
            return "spawnPlayer eid=" + s.getEntityID() + " uuid=" + s.getPlayer()
                    + " at=" + Summ.f2(Summ.fp32(s.getX())) + "," + Summ.f2(Summ.fp32(s.getY()))
                    + "," + Summ.f2(Summ.fp32(s.getZ()))
                    + " yaw=" + Summ.f1(Summ.angle(s.getYaw())) + " pitch=" + Summ.f1(Summ.angle(s.getPitch()));
        }
        @Override public Map<String, Object> project(Object p) {
            S0CPacketSpawnPlayer s = (S0CPacketSpawnPlayer) p;
            return PacketView.of().put("eid", s.getEntityID())
                    .put("uuid", s.getPlayer() == null ? null : s.getPlayer().toString())
                    .putRounded("x", Summ.fp32(s.getX()), 2).putRounded("y", Summ.fp32(s.getY()), 2)
                    .putRounded("z", Summ.fp32(s.getZ()), 2)
                    .putRounded("yaw", Summ.angle(s.getYaw()), 1).putRounded("pitch", Summ.angle(s.getPitch()), 1)
                    .put("currentItem", s.getCurrentItemID())
                    .buildMap();
        }
    }

    static final class SpawnMob implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S0FPacketSpawnMob".equals(cn);
        }
        @Override public String summarize(Object p) {
            S0FPacketSpawnMob s = (S0FPacketSpawnMob) p;
            return "spawnMob eid=" + s.getEntityID() + " type=" + s.getEntityType()
                    + " at=" + Summ.f2(Summ.fp32(s.getX())) + "," + Summ.f2(Summ.fp32(s.getY()))
                    + "," + Summ.f2(Summ.fp32(s.getZ()))
                    + " vel=" + Summ.f3(s.getVelocityX() / 8000.0) + "," + Summ.f3(s.getVelocityY() / 8000.0)
                    + "," + Summ.f3(s.getVelocityZ() / 8000.0);
        }
        @Override public Map<String, Object> project(Object p) {
            S0FPacketSpawnMob s = (S0FPacketSpawnMob) p;
            return PacketView.of().put("eid", s.getEntityID()).put("type", s.getEntityType())
                    .putRounded("x", Summ.fp32(s.getX()), 2).putRounded("y", Summ.fp32(s.getY()), 2)
                    .putRounded("z", Summ.fp32(s.getZ()), 2)
                    .putRounded("velX", s.getVelocityX() / 8000.0, 3)
                    .putRounded("velY", s.getVelocityY() / 8000.0, 3)
                    .putRounded("velZ", s.getVelocityZ() / 8000.0, 3)
                    .buildMap();
        }
    }

    static final class SpawnObject implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S0EPacketSpawnObject".equals(cn);
        }
        @Override public String summarize(Object p) {
            S0EPacketSpawnObject s = (S0EPacketSpawnObject) p;
            return "spawnObject eid=" + s.getEntityID() + " type=" + s.getType()
                    + " at=" + Summ.f2(Summ.fp32(s.getX())) + "," + Summ.f2(Summ.fp32(s.getY()))
                    + "," + Summ.f2(Summ.fp32(s.getZ())) + " data=" + s.func_149009_m();
        }
        @Override public Map<String, Object> project(Object p) {
            S0EPacketSpawnObject s = (S0EPacketSpawnObject) p;
            PacketView.Builder v = PacketView.of().put("eid", s.getEntityID()).put("type", s.getType())
                    .putRounded("x", Summ.fp32(s.getX()), 2).putRounded("y", Summ.fp32(s.getY()), 2)
                    .putRounded("z", Summ.fp32(s.getZ()), 2).put("data", s.func_149009_m());
            // velocity only meaningful when objectData > 0
            if (s.func_149009_m() > 0) {
                v.putRounded("velX", s.getSpeedX() / 8000.0, 3)
                        .putRounded("velY", s.getSpeedY() / 8000.0, 3)
                        .putRounded("velZ", s.getSpeedZ() / 8000.0, 3);
            }
            return v.buildMap();
        }
    }

    static final class SpawnPainting implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S10PacketSpawnPainting".equals(cn);
        }
        @Override public String summarize(Object p) {
            S10PacketSpawnPainting s = (S10PacketSpawnPainting) p;
            return "spawnPainting eid=" + s.getEntityID() + " art=" + s.getTitle()
                    + " at=" + Summ.pos(s.getPosition()) + " facing=" + Summ.enumName(s.getFacing());
        }
        @Override public Map<String, Object> project(Object p) {
            S10PacketSpawnPainting s = (S10PacketSpawnPainting) p;
            net.minecraft.util.BlockPos b = s.getPosition();
            PacketView.Builder v = PacketView.of().put("eid", s.getEntityID()).put("art", s.getTitle());
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("facing", Summ.enumName(s.getFacing())).buildMap();
        }
    }

    static final class SpawnXpOrb implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S11PacketSpawnExperienceOrb".equals(cn);
        }
        @Override public String summarize(Object p) {
            S11PacketSpawnExperienceOrb s = (S11PacketSpawnExperienceOrb) p;
            return "spawnXpOrb eid=" + s.getEntityID() + " xp=" + s.getXPValue()
                    + " at=" + Summ.f2(Summ.fp32(s.getX())) + "," + Summ.f2(Summ.fp32(s.getY()))
                    + "," + Summ.f2(Summ.fp32(s.getZ()));
        }
        @Override public Map<String, Object> project(Object p) {
            S11PacketSpawnExperienceOrb s = (S11PacketSpawnExperienceOrb) p;
            return PacketView.of().put("eid", s.getEntityID()).put("xp", s.getXPValue())
                    .putRounded("x", Summ.fp32(s.getX()), 2).putRounded("y", Summ.fp32(s.getY()), 2)
                    .putRounded("z", Summ.fp32(s.getZ()), 2).buildMap();
        }
    }

    static final class CollectItem implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S0DPacketCollectItem".equals(cn);
        }
        @Override public String summarize(Object p) {
            S0DPacketCollectItem s = (S0DPacketCollectItem) p;
            return "collectItem item=" + s.getCollectedItemEntityID() + " by=" + s.getEntityID();
        }
        @Override public Map<String, Object> project(Object p) {
            S0DPacketCollectItem s = (S0DPacketCollectItem) p;
            return PacketView.of().put("collectedEid", s.getCollectedItemEntityID())
                    .put("collectorEid", s.getEntityID()).buildMap();
        }
    }

    static final class DestroyEntities implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S13PacketDestroyEntities".equals(cn);
        }
        @Override public String summarize(Object p) {
            int[] ids = ((S13PacketDestroyEntities) p).getEntityIDs();
            return "destroyEntities count=" + (ids == null ? 0 : ids.length);
        }
        @Override public Map<String, Object> project(Object p) {
            int[] ids = ((S13PacketDestroyEntities) p).getEntityIDs();
            List<Object> list = new java.util.ArrayList<>();
            if (ids != null) {
                for (int id : ids) {
                    list.add(id);
                }
            }
            return PacketView.of().put("count", list.size()).put("eids", list).buildMap();
        }
    }

    static final class EntityAttach implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S1BPacketEntityAttach".equals(cn);
        }
        @Override public String summarize(Object p) {
            S1BPacketEntityAttach s = (S1BPacketEntityAttach) p;
            return "entityAttach eid=" + s.getEntityId() + " vehicle=" + s.getVehicleEntityId()
                    + " leash=" + s.getLeash();
        }
        @Override public Map<String, Object> project(Object p) {
            S1BPacketEntityAttach s = (S1BPacketEntityAttach) p;
            return PacketView.of().put("eid", s.getEntityId())
                    .put("vehicleEid", s.getVehicleEntityId()).put("leash", s.getLeash()).buildMap();
        }
    }

    static final class Animation implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S0BPacketAnimation".equals(cn);
        }
        @Override public String summarize(Object p) {
            S0BPacketAnimation s = (S0BPacketAnimation) p;
            return "animation eid=" + s.getEntityID() + " type=" + s.getAnimationType();
        }
        @Override public Map<String, Object> project(Object p) {
            S0BPacketAnimation s = (S0BPacketAnimation) p;
            return PacketView.of().put("eid", s.getEntityID())
                    .put("animation", s.getAnimationType()).buildMap();
        }
    }

    static final class EffectAdd implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S1DPacketEntityEffect".equals(cn);
        }
        @Override public String summarize(Object p) {
            S1DPacketEntityEffect s = (S1DPacketEntityEffect) p;
            return "effectAdd eid=" + s.getEntityId() + " effect=" + (s.getEffectId() & 0xFF)
                    + " amp=" + (s.getAmplifier() & 0xFF) + " dur=" + s.getDuration();
        }
        @Override public Map<String, Object> project(Object p) {
            S1DPacketEntityEffect s = (S1DPacketEntityEffect) p;
            return PacketView.of().put("eid", s.getEntityId())
                    .put("effect", s.getEffectId() & 0xFF).put("amplifier", s.getAmplifier() & 0xFF)
                    .put("duration", s.getDuration()).buildMap();
        }
    }

    static final class EffectRemove implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S1EPacketRemoveEntityEffect".equals(cn);
        }
        @Override public String summarize(Object p) {
            S1EPacketRemoveEntityEffect s = (S1EPacketRemoveEntityEffect) p;
            return "effectRemove eid=" + s.getEntityId() + " effect=" + s.getEffectId();
        }
        @Override public Map<String, Object> project(Object p) {
            S1EPacketRemoveEntityEffect s = (S1EPacketRemoveEntityEffect) p;
            return PacketView.of().put("eid", s.getEntityId())
                    .put("effect", s.getEffectId()).buildMap();
        }
    }
}
