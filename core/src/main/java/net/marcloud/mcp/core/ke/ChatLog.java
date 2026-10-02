package net.marcloud.mcp.core.ke;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;

/**
 * A bounded ring of INBOUND CHAT, addressable by a monotonic cursor — the read side
 * that {@code send_chat} never had. {@code send_chat} made the agent able to speak;
 * nothing in the kernel made it able to HEAR. Without this the agent is deaf to a
 * server notice, another player's reply, its own death message, a join/leave, a
 * plugin warning, or a quest prompt, and 「自动完成对局」 is unreachable however good
 * the motor model is.
 *
 * <p><b>Packet identity — the names in the task brief are wrong for this codebase.</b>
 * Inbound chat in 1.8.9 (MCP 1.8.9 mappings) is
 * {@code net.minecraft.network.play.server.S02PacketChat}, carrying an
 * {@code IChatComponent} and a channel byte ({@code getType()}: 2 = action bar, else
 * chat log — read at {@code NetHandlerPlayClient.handleChat}:849). {@code S3F} in
 * 1.8.9 is {@code S3FPacketCustomPayload} (plugin channel), and {@code C02} is
 * {@code C02PacketUseEntity} — those are modern-protocol (1.19+) names. There is no
 * separate inbound "player chat" packet in 1.8.9 at all: a player's line arrives as
 * an S02 whose component is the translatable {@code chat.type.text}. The only
 * player-chat packet is OUTBOUND ({@code C01PacketChatMessage}), already projected by
 * {@code SessionSummarizers.ChatMessage}.
 *
 * <p><b>Reference-free (L7), same contract as {@link PacketJournal}.</b> The Netty tap
 * freezes a decoded packet to a {@link NettyTap.PacketTapHandler.MessageSnapshot} and
 * the live {@code S02PacketChat} dies with the tap callback frame. The live
 * {@code IChatComponent} is doubly dead — it can only be read inside the tap. So the
 * structured projection ({@code key} + {@code args}) is computed SYNCHRONOUSLY in the
 * tap by the chat summarizer's {@code project()}; this ring only ever sees immutable
 * scalars in a {@code Map}. It holds no {@code ByteBuf}, no packet, no component.
 *
 * <p><b>Kind, not prose.</b> A model must branch on "died to a creeper" vs "died to
 * the void" — those demand different next actions — so a death is decomposed into
 * {@link DeathCause} + killer + item rather than left as a sentence. See the honesty
 * note on {@link DeathCause} for why {@code explosion} cannot be narrowed to "creeper".
 *
 * <p><b>Polling cost.</b> This runs every turn, so it must NOT return the whole
 * history each time. {@link #unread()} returns only what has arrived since the last
 * read and advances the cursor; see {@code ChatTools.chat_read}.
 *
 * <p><b>Threading:</b> {@code record} runs on the publishing Netty worker;
 * {@link #tail}/{@link #unread} are read from MCP tool threads. One lock guards the
 * ring, mirroring {@link PacketJournal}.
 */
public final class ChatLog {

    /** The fully-qualified inbound chat packet class this ring consumes. */
    public static final String S02_CHAT = "net.minecraft.network.play.server.S02PacketChat";

    /** Prefix of the vanilla death translation keys. */
    public static final String DEATH_KEY_PREFIX = "death.attack.";

    /**
     * What KIND of message this is — the branch a model takes, not the prose.
     *
     * <p>Orthogonal to {@link Channel}: a death is {@link #DEATH} but arrives on the
     * {@link Channel#SYSTEM} channel, and a plugin's action-bar text is
     * {@link #RAW} on {@link Channel#ACTION_BAR}. Collapsing them would lose the
     * distinction the model actually needs.
     */
    public enum Kind {
        /** A vanilla death message: {@code death.attack.<type>}. A world fact. */
        DEATH,
        /** A human's own line: {@code chat.type.text} / {@code chat.type.emote}. */
        PLAYER,
        /** A server-generated notice that is not a death: join/leave, MOTD, a
         *  command's reply, a plugin's server-side notice. */
        SYSTEM,
        /** No translatable key at all — a literal string. Could be anything: a plugin,
         *  a proxy, a non-vanilla server, or already-resolved text. */
        RAW
    }

    /**
     * WHERE it is displayed, read from {@code S02PacketChat.getType()}. Kept separate
     * from {@link Kind} because a death and a plugin banner share a channel while
     * demanding opposite reactions.
     */
    public enum Channel {
        /** Chat log (type byte 0 or 1 — vanilla renders both identically). */
        CHAT,
        /** Action bar, floating above the hotbar (type byte 2). */
        ACTION_BAR
    }

    /**
     * How much the CONTENT of this message can be trusted as a statement about the
     * world. This is the honesty boundary the brief asks for, and it is about the
     * content's origin — not about which machine moved the bytes.
     *
     * <p>Every entry here arrived INBOUND over the Netty tap, so the transport is
     * always server-side: the server put this on the wire. That says nothing about
     * who WROTE the words, which is what a model must not conflate.
     */
    public enum Authority {
        /**
         * The server COMPUTED this from authoritative state — a death message is
         * synthesised from the actual damage source ({@code DamageSource
         * .getDeathMessage}), a join/leave from the roster. As far as the world is
         * concerned this is a fact.
         */
        SERVER_FACT,
        /**
         * A PLAYER wrote these words and the server relayed them. The transmission is
         * server-side; the content is an assertion by someone. A player saying "there's
         * a creeper at 100 64 -200" is not evidence that there is.
         */
        PLAYER_CLAIM,
        /**
         * A literal string with no translation key. There is NO way to tell from the
         * wire whether the server, a plugin, or a proxy wrote it. Do not treat as fact.
         */
        UNKNOWN
    }

    /**
     * The machine-readable cause of a {@link Kind#DEATH} message, named exactly after
     * the vanilla {@code death.attack.<type>} key suffix so there is no mapping table
     * to drift. The enum covers every base type in
     * {@code assets/minecraft/lang/en_US.lang} (23 bases; the 34 keys are those bases
     * plus {@code .player} / {@code .item} variants).
     *
     * <p><b>The limit of this enum, stated because it changes what the model should
     * do.</b> A vanilla Creeper kill is NOT a distinct cause. {@code EntityCreeper
     * .explode()} → {@code World.createExplosion(this, …)} → {@code Explosion} with
     * the creeper as exploder → {@code DamageSource.setExplosionSource}
     * ({@code DamageSource.java:107}) yields {@code new EntityDamageSource
     * ("explosion.player", …)} — because {@code getExplosivePlacedBy()} returns any
     * {@code EntityLivingBase} exploder, not only a TNT placer. So "killed by a
     * creeper" is reported as {@link #EXPLOSION} with the killer argument reading
     * {@code Creeper}, and is indistinguishable here from TNT-primed-by-that-player.
     * {@code cause} tells the model WHAT CLASS of thing killed it; {@code killer} says
     * which one. A model that reads {@code cause} alone and answers "an explosion"
     * is not wrong, but it is not specific enough to pick the right next move.
     */
    public enum DeathCause {
        LIGHTNING_BOLT("lightningBolt"),
        IN_FIRE("inFire"),
        ON_FIRE("onFire"),
        LAVA("lava"),
        IN_WALL("inWall"),
        DROWN("drown"),
        STARVE("starve"),
        CACTUS("cactus"),
        FALL("fall"),
        OUT_OF_WORLD("outOfWorld"),
        GENERIC("generic"),
        MAGIC("magic"),
        WITHER("wither"),
        ANVIL("anvil"),
        FALLING_BLOCK("fallingBlock"),
        MOB("mob"),
        PLAYER("player"),
        EXPLOSION("explosion"),
        ARROW("arrow"),
        FIREBALL("fireball"),
        THROWN("thrown"),
        INDIRECT_MAGIC("indirectMagic"),
        THORNS("thorns"),
        /** A {@code death.attack.} key this build's lang file does not define. */
        UNKNOWN(null);

        private final String vanillaKeySuffix;

        DeathCause(String vanillaKeySuffix) {
            this.vanillaKeySuffix = vanillaKeySuffix;
        }

        /** The exact {@code death.attack.} suffix this enum stands for, or null for {@link #UNKNOWN}. */
        public String vanillaKeySuffix() {
            return vanillaKeySuffix;
        }

        /** The base cause for a vanilla key suffix, or {@link #UNKNOWN} if unrecognised. */
        public static DeathCause ofKeySuffix(String suffix) {
            if (suffix != null) {
                for (DeathCause c : values()) {
                    if (suffix.equals(c.vanillaKeySuffix)) {
                        return c;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * One observed inbound chat message. Immutable, reference-free: Strings, a
     * primitive, two enums, and an immutable List of Strings. Nothing here can
     * outlive the tap callback or point back at a live game object.
     *
     * @param seq    stable, monotonic, session-unique cursor id (what {@code chat_read}
     *                polls with)
     * @param kind   what KIND of message this is
     * @param channel where the client renders it
     * @param text   the fully-resolved, unformatted text — for a human or an LLM to
     *               read, NOT for branching on (it is locale-dependent)
     * @param key    the raw translation key ({@code death.attack.fall},
     *               {@code chat.type.text}) or null when the message had no key
     * @param args   the resolved format arguments, in order — machine-readable and
     *               locale-INDEPENDENT
     * @param deathCause the decomposed cause when {@code kind == DEATH}, else null
     * @param deathKiller the {@code %2$s} argument when the key carries one, else null
     * @param deathItem   the {@code %3$s} argument when the key carries one, else null
     * @param authority   how far the CONTENT can be trusted as a world fact
     */
    public record Entry(long seq, long tickId, long arrivalMono,
                        Kind kind, Channel channel,
                        String text, String key, List<String> args,
                        DeathCause deathCause, String deathKiller, String deathItem,
                        Authority authority) {

        /** Is this a death message? (A model branches on this before anything else.) */
        public boolean isDeath() {
            return kind == Kind.DEATH;
        }
    }

    private final Object lock = new Object();
    private final Entry[] ring;
    private int head;    // next write index
    private int size;    // number of valid entries
    private long seqGen; // monotonic id source (1-based)

    /** Highest seq ever handed out by a read. Monotone: an explicit replay of an old
     *  {@code sinceSeq} never rewinds it. See {@link #markRead(long)}. */
    private final AtomicLong readCursor = new AtomicLong(0L);

    /** A ring holding up to {@code capacity} recent chat messages (min 1). */
    public ChatLog(int capacity) {
        this.ring = new Entry[Math.max(1, capacity)];
    }

    /**
     * Subscribe to inbound packet events. Deliberately NOT {@code SeamPacketOutbound}
     * and not {@code C01PacketChatMessage}: {@link #selfEcho} marks the agent's own
     * words when they come BACK, so an outbound record would double-count them.
     */
    public void attach(EventBus bus) {
        if (bus == null) {
            return;
        }
        bus.subscribe(SeamPacketInboundEvent.class, this::recordInbound);
    }

    void recordInbound(SeamPacketInboundEvent e) {
        if (e == null) {
            return;
        }
        try {
            record(e.tickId(), e.timestampNanos(), e.rawMsg());
        } catch (Throwable t) {
            // A misbehaving projection must never break the Netty publishing thread.
        }
    }

    private void record(long tickId, long arrivalMono, Object rawMsg) {
        if (!(rawMsg instanceof NettyTap.PacketTapHandler.MessageSnapshot snap)
                || !S02_CHAT.equals(snap.className())) {
            return;
        }
        // No typed projection ⇒ the tap's chat summarizer did not run for this
        // packet. Record nothing rather than guess: a silent empty row would read as
        // "the server said nothing", which is a different and wrong claim.
        Map<String, Object> f = snap.fields();
        if (f == null || f.isEmpty()) {
            return;
        }
        Entry entry = project(pendingSeq(), tickId, arrivalMono, f);
        if (entry == null) {
            return;
        }
        synchronized (lock) {
            ring[head] = entry;
            head = (head + 1) % ring.length;
            if (size < ring.length) {
                size++;
            }
        }
    }

    /**
     * Allocate the next seq OUTSIDE the ring lock. Holding the lock across
     * {@link #project} would serialise a Netty worker behind another thread's read,
     * and {@link #project} allocates and walks argument lists.
     */
    private long pendingSeq() {
        synchronized (lock) {
            return ++seqGen;
        }
    }

    /**
     * Turn one tap-time field map into an {@link Entry}. Pure and package-visible so
     * the classification is testable without a live client.
     *
     * <p>Reads only scalars the tap already froze — {@code type}, {@code text},
     * {@code key}, {@code args} — and never re-derives them from a live packet.
     */
    static Entry project(long seq, long tickId, long arrivalMono, Map<String, Object> f) {
        String text = str(f.get("text"));
        String key = str(f.get("key"));
        List<String> args = argsOf(f.get("args"));

        Channel channel = intOf(f.get("type")) == 2 ? Channel.ACTION_BAR : Channel.CHAT;

        boolean isDeath = key != null && key.startsWith(DEATH_KEY_PREFIX);
        Kind kind;
        DeathCause cause = null;
        String killer = null;
        String item = null;
        if (isDeath) {
            kind = Kind.DEATH;
            // Strip the arity variants off the key to recover the base damage type.
            // Order matters: "player.item" must reduce to "player", not to "item".
            String base = key.substring(DEATH_KEY_PREFIX.length());
            if (base.endsWith(".item")) {
                base = base.substring(0, base.length() - ".item".length());
            }
            if (base.endsWith(".player")) {
                base = base.substring(0, base.length() - ".player".length());
            }
            cause = DeathCause.ofKeySuffix(base);
            // DamageSource.getDeathMessage passes [victim, killer] for a ".player"
            // key and [victim, killer, item] for a ".item" key, so argument position
            // IS the role — which is why these are read by index, not parsed.
            if (args.size() > 1) {
                killer = args.get(1);
            }
            if (args.size() > 2) {
                item = args.get(2);
            }
        } else if (key != null && (key.equals("chat.type.text") || key.equals("chat.type.emote"))) {
            kind = Kind.PLAYER;
        } else if (key != null) {
            kind = Kind.SYSTEM;
        } else {
            kind = Kind.RAW;
        }

        Authority authority;
        if (kind == Kind.DEATH) {
            authority = Authority.SERVER_FACT;
        } else if (kind == Kind.PLAYER) {
            authority = Authority.PLAYER_CLAIM;
        } else {
            // A key we recognise as a server key is server-computed; a keyless
            // literal is not attributable from the wire at all.
            authority = key != null ? Authority.SERVER_FACT : Authority.UNKNOWN;
        }

        return new Entry(seq, tickId, arrivalMono, kind, channel,
                text, key, List.copyOf(args), cause, killer, item, authority);
    }

    /**
     * Whether this message is the agent hearing its OWN words back — the client-side
     * echo the honesty boundary must name.
     *
     * <p>The agent called {@code send_chat}; the server relayed the result as an
     * inbound S02. It is genuinely inbound — but it carries no information the agent
     * did not already have when it composed the message, and a model that treats its
     * own echo as a server reply will branch on it. The sender of a
     * {@code chat.type.text} line is {@code args.get(0)}.
     *
     * <p><b>TRI-STATE, and that is the point.</b> Returns {@code null} when the
     * question cannot be decided — a PLAYER line whose sender or whose local name is
     * unavailable. It deliberately does NOT collapse those to {@code false}: "this is
     * not an echo" is a claim, and with no local name to compare against the tool
     * cannot support it. A confident {@code false} would let a model read its own
     * message back as another player's reply.
     *
     * @param sender    the {@code %1$s} argument, as recorded in {@code args.get(0)}
     * @param localName the local player's name, or null when it could not be read
     * @return {@code TRUE}/{@code FALSE} when decidable, {@code null} when not
     */
    public static Boolean selfEcho(Entry e, String sender, String localName) {
        if (e == null || e.kind() != Kind.PLAYER) {
            // Decidable: only a PLAYER line can be an echo at all.
            return Boolean.FALSE;
        }
        if (sender == null || sender.isEmpty()
                || localName == null || localName.isEmpty()) {
            // NOT decidable — must not degrade to false.
            return null;
        }
        return Boolean.valueOf(sender.equals(localName));
    }

    /** All entries, oldest first. */
    public List<Entry> tail() {
        synchronized (lock) {
            List<Entry> out = new ArrayList<>(size);
            int start = (head - size + ring.length) % ring.length;
            for (int i = 0; i < size; i++) {
                out.add(ring[(start + i) % ring.length]);
            }
            return out;
        }
    }

    /**
     * Entries with {@code seq > sinceSeq}, oldest first, capped at {@code limit}
     * (the MOST RECENT {@code limit}, matching {@code packet_view}). This is the
     * stateless read; it does NOT touch the unread cursor.
     */
    public List<Entry> since(long sinceSeq, int limit) {
        List<Entry> all = tail();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.seq() > sinceSeq) {
                out.add(e);
            }
        }
        int k = Math.max(0, limit);
        if (out.size() > k) {
            out = new ArrayList<>(out.subList(out.size() - k, out.size()));
        }
        return out;
    }

    /**
     * Entries since {@code sinceSeq}, then advance the unread cursor to the highest
     * seq handed out. The cursor is MONOTONE: passing an older {@code sinceSeq} to
     * replay history never rewinds it, so a replay can neither re-deliver nor
     * un-see anything.
     */
    public List<Entry> sinceAndMarkRead(long sinceSeq, int limit) {
        List<Entry> out = since(sinceSeq, limit);
        markRead(out.isEmpty() ? sinceSeq : out.get(out.size() - 1).seq());
        return out;
    }

    /** What {@link #unread()} would return right now, without advancing the cursor. */
    public List<Entry> peekUnread(int limit) {
        return since(readCursor.get(), limit);
    }

    /**
     * Return only what has arrived since the last read, and advance the cursor.
     *
     * <p>This is the cheap every-turn poll: an idle turn costs one empty list, not the
     * whole session's chat. {@code sinceSeq} of {@code -1} means "everything not yet
     * read"; any other value overrides it for a stateless replay (see
     * {@link #sinceAndMarkRead}).
     */
    public List<Entry> unread(int limit) {
        return sinceAndMarkRead(readCursor.get(), limit);
    }

    /** Advance the unread cursor to at least {@code seq}. Never moves backwards. */
    public void markRead(long seq) {
        readCursor.accumulateAndGet(seq, Math::max);
    }

    /** The current unread cursor: the highest seq already handed to a reader. */
    public long readCursor() {
        return readCursor.get();
    }

    /**
     * The oldest seq still in the ring, or 0 when empty. A caller whose {@code
     * sinceSeq} is below this has MISSED messages — the honest answer is "you fell
     * behind", not a clean-looking partial list.
     */
    public long oldestSeq() {
        synchronized (lock) {
            if (size == 0) {
                return 0L;
            }
            return ring[(head - size + ring.length) % ring.length].seq();
        }
    }

    /** The newest seq in the ring, or 0 when empty. */
    public long newestSeq() {
        synchronized (lock) {
            if (size == 0) {
                return 0L;
            }
            return ring[(head - 1 + ring.length) % ring.length].seq();
        }
    }

    /** Ring capacity. */
    public int capacity() {
        return ring.length;
    }

    /** Number of entries currently held. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    // ===== field coercion (the tap map is Map<String,Object>; be total) =====

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> argsOf(Object v) {
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "ChatLog{size=" + size() + "/" + capacity() + ", cursor=" + readCursor() + "}";
    }

    /** Lower-cased helper kept package-local for tests asserting key handling. */
    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}