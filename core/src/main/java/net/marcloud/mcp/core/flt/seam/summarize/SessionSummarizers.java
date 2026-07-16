package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Map;

import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C14PacketTabComplete;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S1FPacketSetExperience;
import net.minecraft.network.play.server.S3APacketTabComplete;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S45PacketTitle;

/**
 * A-tier summarizers for the SESSION / social remainder — join-game world context
 * (S01), experience (S1F), title/tab-complete (S45/S3A/C14), the client's chat and
 * status (C01/C16), and the score/team scoreboard updates that expose clean string
 * getters (S3C/S3E). Objective/display scoreboard packets (S3B/S3D) are left to the
 * B-tier generic pass: their fields are obfuscated int modes that only make sense
 * against a live scoreboard, so a flat A-tier projection would mislead more than help.
 */
final class SessionSummarizers {

    private SessionSummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new JoinGame(), "net.minecraft.network.play.server.S01PacketJoinGame");
        r.register(new Experience(), "net.minecraft.network.play.server.S1FPacketSetExperience");
        r.register(new Title(), "net.minecraft.network.play.server.S45PacketTitle");
        r.register(new TabCompleteServer(), "net.minecraft.network.play.server.S3APacketTabComplete");
        r.register(new UpdateScore(), "net.minecraft.network.play.server.S3CPacketUpdateScore");
        r.register(new Teams(), "net.minecraft.network.play.server.S3EPacketTeams");
        r.register(new ChatMessage(), "net.minecraft.network.play.client.C01PacketChatMessage");
        r.register(new ClientStatus(), "net.minecraft.network.play.client.C16PacketClientStatus");
        r.register(new TabCompleteClient(), "net.minecraft.network.play.client.C14PacketTabComplete");
    }

    static final class JoinGame implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S01PacketJoinGame".equals(cn);
        }
        @Override public String summarize(Object p) {
            S01PacketJoinGame s = (S01PacketJoinGame) p;
            return "joinGame eid=" + s.getEntityId() + " mode=" + Summ.enumName(s.getGameType())
                    + " dim=" + s.getDimension() + " diff=" + Summ.enumName(s.getDifficulty())
                    + " hardcore=" + s.isHardcoreMode() + " maxPlayers=" + s.getMaxPlayers();
        }
        @Override public Map<String, Object> project(Object p) {
            S01PacketJoinGame s = (S01PacketJoinGame) p;
            return PacketView.of().put("eid", s.getEntityId())
                    .put("gameMode", Summ.enumName(s.getGameType())).put("dimension", s.getDimension())
                    .put("difficulty", Summ.enumName(s.getDifficulty())).put("hardcore", s.isHardcoreMode())
                    .put("maxPlayers", s.getMaxPlayers())
                    .put("worldType", s.getWorldType() == null ? null : s.getWorldType().getWorldTypeName())
                    .put("reducedDebug", s.isReducedDebugInfo())
                    .buildMap();
        }
    }

    static final class Experience implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S1FPacketSetExperience".equals(cn);
        }
        @Override public String summarize(Object p) {
            S1FPacketSetExperience s = (S1FPacketSetExperience) p;
            return "experience level=" + s.getLevel() + " bar=" + Summ.f2(s.func_149397_c())
                    + " total=" + s.getTotalExperience();
        }
        @Override public Map<String, Object> project(Object p) {
            S1FPacketSetExperience s = (S1FPacketSetExperience) p;
            return PacketView.of().put("level", s.getLevel())
                    .putRounded("bar", s.func_149397_c(), 3).put("total", s.getTotalExperience()).buildMap();
        }
    }

    static final class Title implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S45PacketTitle".equals(cn);
        }
        /** The wire reads a message ONLY for TITLE/SUBTITLE (S45PacketTitle.readPacketData). */
        private static boolean carriesMessage(S45PacketTitle s) {
            return s.getType() == S45PacketTitle.Type.TITLE
                    || s.getType() == S45PacketTitle.Type.SUBTITLE;
        }

        /** The wire reads fade/stay times ONLY for TIMES; CLEAR/RESET carry neither. */
        private static boolean carriesTimes(S45PacketTitle s) {
            return s.getType() == S45PacketTitle.Type.TIMES;
        }

        @Override public String summarize(Object p) {
            S45PacketTitle s = (S45PacketTitle) p;
            String type = Summ.enumName(s.getType());
            if (carriesMessage(s)) {
                String msg = s.getMessage() == null ? "" : s.getMessage().getUnformattedText();
                return "title type=" + type + " msg=\"" + Summ.clip(msg, 80) + "\"";
            }
            if (carriesTimes(s)) {
                return "title type=" + type + " fadeIn=" + s.getFadeInTime()
                        + " stay=" + s.getDisplayTime() + " fadeOut=" + s.getFadeOutTime();
            }
            // CLEAR / RESET carry nothing beyond the type — do not invent times.
            return "title type=" + type;
        }

        @Override public Map<String, Object> project(Object p) {
            S45PacketTitle s = (S45PacketTitle) p;
            PacketView.Builder v = PacketView.of().put("type", Summ.enumName(s.getType()));
            if (carriesMessage(s) && s.getMessage() != null) {
                v.put("message", s.getMessage().getUnformattedText());
            }
            if (carriesTimes(s)) {
                v.put("fadeIn", s.getFadeInTime()).put("stay", s.getDisplayTime())
                        .put("fadeOut", s.getFadeOutTime());
            }
            return v.buildMap();
        }
    }

    static final class TabCompleteServer implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S3APacketTabComplete".equals(cn);
        }
        @Override public String summarize(Object p) {
            String[] m = ((S3APacketTabComplete) p).func_149630_c();
            return "tabComplete matches=" + (m == null ? 0 : m.length);
        }
        @Override public Map<String, Object> project(Object p) {
            String[] m = ((S3APacketTabComplete) p).func_149630_c();
            java.util.List<Object> list = new java.util.ArrayList<>();
            if (m != null) {
                java.util.Collections.addAll(list, m);
            }
            return PacketView.of().put("count", list.size()).put("matches", list).buildMap();
        }
    }

    static final class UpdateScore implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S3CPacketUpdateScore".equals(cn);
        }
        @Override public String summarize(Object p) {
            S3CPacketUpdateScore s = (S3CPacketUpdateScore) p;
            return "updateScore player=" + s.getPlayerName() + " obj=" + s.getObjectiveName()
                    + " value=" + s.getScoreValue() + " action=" + Summ.enumName(s.getScoreAction());
        }
        @Override public Map<String, Object> project(Object p) {
            S3CPacketUpdateScore s = (S3CPacketUpdateScore) p;
            return PacketView.of().put("player", s.getPlayerName()).put("objective", s.getObjectiveName())
                    .put("value", s.getScoreValue()).put("action", Summ.enumName(s.getScoreAction())).buildMap();
        }
    }

    static final class Teams implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S3EPacketTeams".equals(cn);
        }
        @Override public String summarize(Object p) {
            S3EPacketTeams s = (S3EPacketTeams) p;
            java.util.Collection<String> players = s.getPlayers();
            return "teams name=" + s.getName() + " action=" + s.getAction()
                    + " players=" + (players == null ? 0 : players.size());
        }
        @Override public Map<String, Object> project(Object p) {
            S3EPacketTeams s = (S3EPacketTeams) p;
            java.util.List<Object> players = new java.util.ArrayList<>();
            if (s.getPlayers() != null) {
                players.addAll(s.getPlayers());
            }
            return PacketView.of().put("name", s.getName()).put("action", s.getAction())
                    .put("displayName", s.getDisplayName()).put("prefix", s.getPrefix())
                    .put("suffix", s.getSuffix()).put("players", players).buildMap();
        }
    }

    static final class ChatMessage implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C01PacketChatMessage".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "chatSend text=\"" + Summ.clip(((C01PacketChatMessage) p).getMessage(), 120) + "\"";
        }
        @Override public Map<String, Object> project(Object p) {
            return PacketView.of().put("message", ((C01PacketChatMessage) p).getMessage()).buildMap();
        }
    }

    static final class ClientStatus implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C16PacketClientStatus".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "clientStatus " + Summ.enumName(((C16PacketClientStatus) p).getStatus());
        }
        @Override public Map<String, Object> project(Object p) {
            return PacketView.of().put("status", Summ.enumName(((C16PacketClientStatus) p).getStatus())).buildMap();
        }
    }

    static final class TabCompleteClient implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C14PacketTabComplete".equals(cn);
        }
        @Override public String summarize(Object p) {
            C14PacketTabComplete s = (C14PacketTabComplete) p;
            return "tabCompleteReq text=\"" + Summ.clip(s.getMessage(), 80) + "\""
                    + (s.getTargetBlock() == null ? "" : " target=" + Summ.pos(s.getTargetBlock()));
        }
        @Override public Map<String, Object> project(Object p) {
            C14PacketTabComplete s = (C14PacketTabComplete) p;
            PacketView.Builder v = PacketView.of().put("message", s.getMessage());
            net.minecraft.util.BlockPos b = s.getTargetBlock();
            if (b != null) {
                v.put("targetX", b.getX()).put("targetY", b.getY()).put("targetZ", b.getZ());
            }
            return v.buildMap();
        }
    }
}
