package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.List;
import java.util.Map;

import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C12PacketUpdateSign;
import net.minecraft.network.play.server.S05PacketSpawnPosition;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S41PacketServerDifficulty;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IChatComponent;

/**
 * A-tier summarizers for the WORLD family — spawn/respawn/difficulty/game-state,
 * bulk block edits, explosions, entity teleport, and the client's own world-edit
 * intents (dig / place / sign). Each emits both a compact String
 * ({@link PacketSummarizer#summarize}) and a typed projection
 * ({@link PacketSummarizer#project} → {@link PacketView}).
 *
 * <p>Position/time/single-block-change (S03/S08/S23/S26) already live in
 * {@link HighValueSummarizers}; this file is the world packets added in the full-
 * exposure pass. Registered by {@link #registerInto}.
 */
final class WorldSummarizers {

    private WorldSummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new SpawnPosition(), "net.minecraft.network.play.server.S05PacketSpawnPosition");
        r.register(new Respawn(), "net.minecraft.network.play.server.S07PacketRespawn");
        r.register(new GameState(), "net.minecraft.network.play.server.S2BPacketChangeGameState");
        r.register(new Difficulty(), "net.minecraft.network.play.server.S41PacketServerDifficulty");
        r.register(new MultiBlockChange(), "net.minecraft.network.play.server.S22PacketMultiBlockChange");
        r.register(new Explosion(), "net.minecraft.network.play.server.S27PacketExplosion");
        r.register(new EntityTeleport(), "net.minecraft.network.play.server.S18PacketEntityTeleport");
        r.register(new Digging(), "net.minecraft.network.play.client.C07PacketPlayerDigging");
        r.register(new BlockPlacement(), "net.minecraft.network.play.client.C08PacketPlayerBlockPlacement");
        r.register(new UpdateSign(), "net.minecraft.network.play.client.C12PacketUpdateSign");
    }

    static final class SpawnPosition implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S05PacketSpawnPosition".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "spawnPos at=" + Summ.pos(((S05PacketSpawnPosition) p).getSpawnPos());
        }
        @Override public Map<String, Object> project(Object p) {
            BlockPos b = ((S05PacketSpawnPosition) p).getSpawnPos();
            if (b == null) {
                return PacketView.of().buildMap();
            }
            return PacketView.of().put("x", b.getX()).put("y", b.getY()).put("z", b.getZ()).buildMap();
        }
    }

    static final class Respawn implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S07PacketRespawn".equals(cn);
        }
        @Override public String summarize(Object p) {
            S07PacketRespawn s = (S07PacketRespawn) p;
            return "respawn dim=" + s.getDimensionID() + " diff=" + Summ.enumName(s.getDifficulty())
                    + " mode=" + Summ.enumName(s.getGameType())
                    + " world=" + (s.getWorldType() == null ? "?" : s.getWorldType().getWorldTypeName());
        }
        @Override public Map<String, Object> project(Object p) {
            S07PacketRespawn s = (S07PacketRespawn) p;
            return PacketView.of()
                    .put("dimension", s.getDimensionID())
                    .put("difficulty", Summ.enumName(s.getDifficulty()))
                    .put("gameMode", Summ.enumName(s.getGameType()))
                    .put("worldType", s.getWorldType() == null ? null : s.getWorldType().getWorldTypeName())
                    .buildMap();
        }
    }

    static final class GameState implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S2BPacketChangeGameState".equals(cn);
        }
        @Override public String summarize(Object p) {
            S2BPacketChangeGameState s = (S2BPacketChangeGameState) p;
            return "gameState id=" + s.getGameState() + " value=" + Summ.f2(s.func_149137_d());
        }
        @Override public Map<String, Object> project(Object p) {
            S2BPacketChangeGameState s = (S2BPacketChangeGameState) p;
            return PacketView.of().put("state", s.getGameState())
                    .putRounded("value", s.func_149137_d(), 3).buildMap();
        }
    }

    static final class Difficulty implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S41PacketServerDifficulty".equals(cn);
        }
        @Override public String summarize(Object p) {
            S41PacketServerDifficulty s = (S41PacketServerDifficulty) p;
            return "difficulty " + Summ.enumName(s.getDifficulty()) + " locked=" + s.isDifficultyLocked();
        }
        @Override public Map<String, Object> project(Object p) {
            S41PacketServerDifficulty s = (S41PacketServerDifficulty) p;
            return PacketView.of().put("difficulty", Summ.enumName(s.getDifficulty()))
                    .put("locked", s.isDifficultyLocked()).buildMap();
        }
    }

    static final class EntityTeleport implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S18PacketEntityTeleport".equals(cn);
        }
        @Override public String summarize(Object p) {
            S18PacketEntityTeleport s = (S18PacketEntityTeleport) p;
            return "entityTeleport eid=" + s.getEntityId()
                    + " at=" + Summ.f2(Summ.fp32(s.getX())) + "," + Summ.f2(Summ.fp32(s.getY()))
                    + "," + Summ.f2(Summ.fp32(s.getZ()))
                    + " yaw=" + Summ.f1(Summ.angle(s.getYaw())) + " pitch=" + Summ.f1(Summ.angle(s.getPitch()))
                    + " ground=" + s.getOnGround();
        }
        @Override public Map<String, Object> project(Object p) {
            S18PacketEntityTeleport s = (S18PacketEntityTeleport) p;
            return PacketView.of().put("eid", s.getEntityId())
                    .putRounded("x", Summ.fp32(s.getX()), 2).putRounded("y", Summ.fp32(s.getY()), 2)
                    .putRounded("z", Summ.fp32(s.getZ()), 2)
                    .putRounded("yaw", Summ.angle(s.getYaw()), 1).putRounded("pitch", Summ.angle(s.getPitch()), 1)
                    .put("onGround", s.getOnGround()).buildMap();
        }
    }

    static final class MultiBlockChange implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S22PacketMultiBlockChange".equals(cn);
        }
        @Override public String summarize(Object p) {
            S22PacketMultiBlockChange s = (S22PacketMultiBlockChange) p;
            S22PacketMultiBlockChange.BlockUpdateData[] arr = s.getChangedBlocks();
            int n = arr == null ? 0 : arr.length;
            String first = "-";
            if (n > 0 && arr[0] != null) {
                first = Summ.pos(arr[0].getPos());
            }
            return "multiBlockChange count=" + n + " first=" + first;
        }
        @Override public Map<String, Object> project(Object p) {
            S22PacketMultiBlockChange s = (S22PacketMultiBlockChange) p;
            S22PacketMultiBlockChange.BlockUpdateData[] arr = s.getChangedBlocks();
            java.util.List<Object> blocks = new java.util.ArrayList<>();
            if (arr != null) {
                for (S22PacketMultiBlockChange.BlockUpdateData b : arr) {
                    if (b == null) {
                        continue;
                    }
                    BlockPos bp = b.getPos();
                    PacketView.Builder bb = PacketView.of();
                    if (bp != null) {
                        bb.put("x", bp.getX()).put("y", bp.getY()).put("z", bp.getZ());
                    }
                    bb.put("state", String.valueOf(b.getBlockState()));
                    blocks.add(bb.buildMap());
                }
            }
            return PacketView.of().put("count", blocks.size()).put("blocks", blocks).buildMap();
        }
    }

    static final class Explosion implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S27PacketExplosion".equals(cn);
        }
        @Override public String summarize(Object p) {
            S27PacketExplosion s = (S27PacketExplosion) p;
            List<BlockPos> aff = s.getAffectedBlockPositions();
            return "explosion at=" + Summ.f2(s.getX()) + "," + Summ.f2(s.getY()) + "," + Summ.f2(s.getZ())
                    + " strength=" + Summ.f2(s.getStrength()) + " destroyed=" + (aff == null ? 0 : aff.size());
        }
        @Override public Map<String, Object> project(Object p) {
            S27PacketExplosion s = (S27PacketExplosion) p;
            List<BlockPos> aff = s.getAffectedBlockPositions();
            return PacketView.of()
                    .putRounded("x", s.getX(), 2).putRounded("y", s.getY(), 2).putRounded("z", s.getZ(), 2)
                    .putRounded("strength", s.getStrength(), 2)
                    .put("destroyedCount", aff == null ? 0 : aff.size())
                    .buildMap();
        }
    }

    static final class Digging implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C07PacketPlayerDigging".equals(cn);
        }
        @Override public String summarize(Object p) {
            C07PacketPlayerDigging s = (C07PacketPlayerDigging) p;
            return "digging status=" + Summ.enumName(s.getStatus()) + " at=" + Summ.pos(s.getPosition())
                    + " face=" + Summ.enumName(s.getFacing());
        }
        @Override public Map<String, Object> project(Object p) {
            C07PacketPlayerDigging s = (C07PacketPlayerDigging) p;
            BlockPos b = s.getPosition();
            PacketView.Builder v = PacketView.of().put("status", Summ.enumName(s.getStatus()));
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("face", Summ.enumName(s.getFacing())).buildMap();
        }
    }

    static final class BlockPlacement implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C08PacketPlayerBlockPlacement".equals(cn);
        }
        @Override public String summarize(Object p) {
            C08PacketPlayerBlockPlacement s = (C08PacketPlayerBlockPlacement) p;
            return "blockPlace at=" + Summ.pos(s.getPosition()) + " dir=" + s.getPlacedBlockDirection()
                    + " item=" + (s.getStack() == null ? "empty" : String.valueOf(s.getStack()));
        }
        @Override public Map<String, Object> project(Object p) {
            C08PacketPlayerBlockPlacement s = (C08PacketPlayerBlockPlacement) p;
            BlockPos b = s.getPosition();
            PacketView.Builder v = PacketView.of();
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            return v.put("direction", s.getPlacedBlockDirection())
                    .put("item", s.getStack() == null ? "empty" : String.valueOf(s.getStack()))
                    .putRounded("offsetX", s.getPlacedBlockOffsetX(), 3)
                    .putRounded("offsetY", s.getPlacedBlockOffsetY(), 3)
                    .putRounded("offsetZ", s.getPlacedBlockOffsetZ(), 3)
                    .buildMap();
        }
    }

    static final class UpdateSign implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C12PacketUpdateSign".equals(cn);
        }
        @Override public String summarize(Object p) {
            C12PacketUpdateSign s = (C12PacketUpdateSign) p;
            return "updateSign at=" + Summ.pos(s.getPosition()) + " lines=" + joinLines(s.getLines(), 20);
        }
        @Override public Map<String, Object> project(Object p) {
            C12PacketUpdateSign s = (C12PacketUpdateSign) p;
            BlockPos b = s.getPosition();
            PacketView.Builder v = PacketView.of();
            if (b != null) {
                v.put("x", b.getX()).put("y", b.getY()).put("z", b.getZ());
            }
            java.util.List<Object> lines = new java.util.ArrayList<>();
            IChatComponent[] arr = s.getLines();
            if (arr != null) {
                for (IChatComponent c : arr) {
                    lines.add(c == null ? "" : c.getUnformattedText());
                }
            }
            return v.put("lines", lines).buildMap();
        }
    }

    private static String joinLines(IChatComponent[] arr, int perLineMax) {
        if (arr == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(Summ.clip(arr[i] == null ? "" : arr[i].getUnformattedText(), perLineMax));
        }
        return sb.append(']').toString();
    }
}
