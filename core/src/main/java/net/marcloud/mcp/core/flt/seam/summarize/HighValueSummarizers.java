package net.marcloud.mcp.core.flt.seam.summarize;

import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.util.BlockPos;

/**
 * PHASE P.4 — the initial set of high-value 1.8.9 packet summarizers, chosen for
 * what an LLM most needs to perceive the game: authoritative position (S08),
 * time-of-day (S03), liveness (S00), world edits (S23), chat (S02), world load
 * (S26), entity motion (S12), and the client's own movement (C03 + subclasses).
 *
 * <p>Each reads only public getters off the live packet and returns a compact
 * String; nothing retains the packet. Placeholder-free formatting keeps summaries
 * short. Registered by exact class name; the C03 movement family is a prefix
 * fallback because its concrete packets are nested classes of {@link C03PacketPlayer}.
 */
public final class HighValueSummarizers {

    private HighValueSummarizers() {
    }

    /** One-line abstract base: exact-name match + a typed summarize. */
    private abstract static class Named implements PacketSummarizer {
        private final String fqn;

        Named(String fqn) {
            this.fqn = fqn;
        }

        @Override
        public boolean handles(String packetClassName) {
            return fqn.equals(packetClassName);
        }
    }

    private static String f2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String f1(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static String f3(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    /** Register the P.4 set into {@code r}. */
    public static void registerInto(PacketSummarizerRegistry r) {
        r.register(new PosLook(), "net.minecraft.network.play.server.S08PacketPlayerPosLook");
        r.register(new Time(), "net.minecraft.network.play.server.S03PacketTimeUpdate");
        r.register(new KeepAlive(), "net.minecraft.network.play.server.S00PacketKeepAlive");
        r.register(new BlockChange(), "net.minecraft.network.play.server.S23PacketBlockChange");
        r.register(new Chat(), "net.minecraft.network.play.server.S02PacketChat");
        r.register(new ChunkBulk(), "net.minecraft.network.play.server.S26PacketMapChunkBulk");
        r.register(new Velocity(), "net.minecraft.network.play.server.S12PacketEntityVelocity");
        // C03 + its nested C04/C05/C06 movement packets share one summarizer.
        r.registerFallback(new Move());
    }

    static final class PosLook implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S08PacketPlayerPosLook".equals(cn);
        }
        @Override public String summarize(Object p) {
            S08PacketPlayerPosLook s = (S08PacketPlayerPosLook) p;
            String rel = s.func_179834_f() == null || s.func_179834_f().isEmpty()
                    ? "abs" : s.func_179834_f().toString();
            return "posLook x=" + f2(s.getX()) + " y=" + f2(s.getY()) + " z=" + f2(s.getZ())
                    + " yaw=" + f1(s.getYaw()) + " pitch=" + f1(s.getPitch()) + " rel=" + rel;
        }
    }

    static final class Time implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S03PacketTimeUpdate".equals(cn);
        }
        @Override public String summarize(Object p) {
            S03PacketTimeUpdate s = (S03PacketTimeUpdate) p;
            long wt = s.getWorldTime();
            String cycle = wt < 0 ? "frozen" : "on";
            long tod = Math.abs(wt) % 24000L;
            return "time total=" + s.getTotalWorldTime() + " world=" + wt
                    + " tod=" + tod + " cycle=" + cycle;
        }
    }

    static final class KeepAlive implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S00PacketKeepAlive".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "keepAlive id=" + ((S00PacketKeepAlive) p).func_149134_c();
        }
    }

    static final class BlockChange implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S23PacketBlockChange".equals(cn);
        }
        @Override public String summarize(Object p) {
            S23PacketBlockChange s = (S23PacketBlockChange) p;
            BlockPos bp = s.getBlockPosition();
            String at = bp == null ? "?" : bp.getX() + "," + bp.getY() + "," + bp.getZ();
            String state;
            try {
                state = String.valueOf(s.getBlockState());
            } catch (Throwable t) {
                state = "?";
            }
            return "blockChange at=" + at + " state=" + state;
        }
    }

    static final class Chat implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S02PacketChat".equals(cn);
        }
        @Override public String summarize(Object p) {
            S02PacketChat s = (S02PacketChat) p;
            String text;
            try {
                text = s.getChatComponent() == null ? ""
                        : s.getChatComponent().getUnformattedText();
            } catch (Throwable t) {
                text = "";
            }
            if (text != null && text.length() > 120) {
                text = text.substring(0, 120) + "…";
            }
            return "chat type=" + s.getType() + " text=\"" + text + "\"";
        }
    }

    static final class ChunkBulk implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S26PacketMapChunkBulk".equals(cn);
        }
        @Override public String summarize(Object p) {
            S26PacketMapChunkBulk s = (S26PacketMapChunkBulk) p;
            int n = s.getChunkCount();
            String first = n > 0 ? s.getChunkX(0) + "," + s.getChunkZ(0) : "-";
            return "chunkBulk count=" + n + " first=" + first;
        }
    }

    static final class Velocity implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S12PacketEntityVelocity".equals(cn);
        }
        @Override public String summarize(Object p) {
            S12PacketEntityVelocity s = (S12PacketEntityVelocity) p;
            // wire is fixed-point: blocks/tick = raw / 8000.0
            return "velocity eid=" + s.getEntityID()
                    + " v=" + f3(s.getMotionX() / 8000.0)
                    + "," + f3(s.getMotionY() / 8000.0)
                    + "," + f3(s.getMotionZ() / 8000.0);
        }
    }

    /** C03PacketPlayer and its nested C04/C05/C06 movement packets. */
    static final class Move implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return cn != null
                    && cn.startsWith("net.minecraft.network.play.client.C03PacketPlayer");
        }
        @Override public String summarize(Object p) {
            C03PacketPlayer s = (C03PacketPlayer) p;
            boolean moving = s.isMoving();
            boolean rotating = s.getRotating();
            String flags = moving && rotating ? "posLook"
                    : moving ? "pos" : rotating ? "look" : "ground";
            return "move x=" + f2(s.getPositionX()) + " y=" + f2(s.getPositionY())
                    + " z=" + f2(s.getPositionZ()) + " yaw=" + f1(s.getYaw())
                    + " pitch=" + f1(s.getPitch()) + " ground=" + s.isOnGround()
                    + " flags=" + flags;
        }
    }
}
