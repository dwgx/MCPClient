package net.marcloud.mcp.core.flt.seam.summarize;

import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S42PacketCombatEvent;
import net.minecraft.util.BlockPos;

/**
 * PHASE P.4 — the initial set of high-value 1.8.9 packet summarizers, chosen for
 * what an LLM most needs to perceive the game: authoritative position (S08),
 * time-of-day (S03), liveness (S00), world edits (S23), chat (S02), world load
 * (S26), entity motion (S12), and the client's own movement (C03 + subclasses).
 *
 * <p>PHASE E.2 adds three more the board bridge needs to surface world signals
 * honestly: health (S06), combat/death (S42), and the player tab-list (S38).
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
        r.register(new Health(), "net.minecraft.network.play.server.S06PacketUpdateHealth");
        r.register(new Combat(), "net.minecraft.network.play.server.S42PacketCombatEvent");
        r.register(new PlayerList(), "net.minecraft.network.play.server.S38PacketPlayerListItem");
        // C03 + its nested C04/C05/C06 movement packets share one summarizer.
        r.registerFallback(new Move());
        // Full-exposure per-category families (PHASE packet-exposure W2+).
        WorldSummarizers.registerInto(r);
        MovementSummarizers.registerInto(r);
        EntitySummarizers.registerInto(r);
        InventorySummarizers.registerInto(r);
        SessionSummarizers.registerInto(r);
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

    static final class Health implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S06PacketUpdateHealth".equals(cn);
        }
        @Override public String summarize(Object p) {
            S06PacketUpdateHealth s = (S06PacketUpdateHealth) p;
            return "health hp=" + f2(s.getHealth()) + " food=" + s.getFoodLevel()
                    + " sat=" + f2(s.getSaturationLevel());
        }
        @Override public java.util.Map<String, Object> project(Object p) {
            S06PacketUpdateHealth s = (S06PacketUpdateHealth) p;
            return PacketView.of()
                    .putRounded("hp", s.getHealth(), 2)
                    .put("food", s.getFoodLevel())
                    .putRounded("sat", s.getSaturationLevel(), 2)
                    .buildMap();
        }
    }

    /**
     * S42PacketCombatEvent. Only ENTITY_DIED carries a death message; the other
     * events (ENTER_COMBAT / END_COMBAT) have none, so we emit only the event id
     * for those and never a fake message. The death message is a public field
     * ({@code deathMessage}) already unformatted by the server ctor.
     */
    static final class Combat implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S42PacketCombatEvent".equals(cn);
        }
        @Override public String summarize(Object p) {
            S42PacketCombatEvent s = (S42PacketCombatEvent) p;
            String event = s.eventType == null ? "?" : s.eventType.name();
            if (s.eventType == S42PacketCombatEvent.Event.ENTITY_DIED) {
                String msg = s.deathMessage == null ? "" : s.deathMessage;
                if (msg.length() > 120) {
                    msg = msg.substring(0, 120) + "…";
                }
                return "combat event=" + event + " death=\"" + msg + "\"";
            }
            return "combat event=" + event;
        }
        @Override public java.util.Map<String, Object> project(Object p) {
            S42PacketCombatEvent s = (S42PacketCombatEvent) p;
            String event = s.eventType == null ? "?" : s.eventType.name();
            PacketView.Builder b = PacketView.of().put("event", event);
            if (s.eventType == S42PacketCombatEvent.Event.ENTITY_DIED) {
                // death message only exists for ENTITY_DIED (honesty: omit otherwise)
                b.put("death", s.deathMessage == null ? "" : s.deathMessage);
            }
            return b.buildMap();
        }
    }

    /**
     * S38PacketPlayerListItem. Emits the action plus, for {@code ADD_PLAYER}, the
     * comma-joined player names read from each entry's {@link com.mojang.authlib.GameProfile#getName()}.
     * For {@code REMOVE_PLAYER} the name is genuinely NOT on the wire (the client
     * decodes a {@code GameProfile(uuid, null)} — see {@code readPacketData}), so we
     * emit only the entry count and no names — no fabricated identity.
     */
    static final class PlayerList implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S38PacketPlayerListItem".equals(cn);
        }
        @Override public String summarize(Object p) {
            S38PacketPlayerListItem s = (S38PacketPlayerListItem) p;
            String action = s.getAction() == null ? "?" : s.getAction().name();
            java.util.List<S38PacketPlayerListItem.AddPlayerData> entries = s.getEntries();
            int count = entries == null ? 0 : entries.size();
            if (s.getAction() == S38PacketPlayerListItem.Action.ADD_PLAYER && entries != null) {
                StringBuilder names = new StringBuilder();
                for (S38PacketPlayerListItem.AddPlayerData e : entries) {
                    String name = e.getProfile() == null ? null : e.getProfile().getName();
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    if (names.length() > 0) {
                        names.append(',');
                    }
                    names.append(name);
                }
                return "playerList action=" + action + " count=" + count
                        + " names=" + (names.length() == 0 ? "-" : names);
            }
            return "playerList action=" + action + " count=" + count;
        }
        @Override public java.util.Map<String, Object> project(Object p) {
            S38PacketPlayerListItem s = (S38PacketPlayerListItem) p;
            String action = s.getAction() == null ? "?" : s.getAction().name();
            java.util.List<S38PacketPlayerListItem.AddPlayerData> entries = s.getEntries();
            int count = entries == null ? 0 : entries.size();
            PacketView.Builder b = PacketView.of().put("action", action).put("count", count);
            if (s.getAction() == S38PacketPlayerListItem.Action.ADD_PLAYER && entries != null) {
                // only ADD carries names on the wire; REMOVE has UUID only (omit names)
                java.util.List<String> names = new java.util.ArrayList<>();
                for (S38PacketPlayerListItem.AddPlayerData e : entries) {
                    String name = e.getProfile() == null ? null : e.getProfile().getName();
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
                b.put("names", names);
            }
            return b.buildMap();
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
