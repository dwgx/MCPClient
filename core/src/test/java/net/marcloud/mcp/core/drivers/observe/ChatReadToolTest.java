package net.marcloud.mcp.core.drivers.observe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.ke.ChatLog;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.io.http.Json;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the inbound chat path: a REAL {@code S02PacketChat}, through the REAL
 * {@link NettyTap.PacketTapHandler} on a REAL Netty channel, over a REAL
 * {@link EventBus}, into the real {@link ChatLog} ring, out through the real
 * {@code chat_read} handler.
 *
 * <p>Nothing is stubbed at the boundary that matters. The tap's summarizer is the
 * production registry ({@code PacketTapHandler}'s default ctor), so the typed
 * projection under test is the one the running client computes. The only thing absent
 * is a socket and a server.
 *
 * <p><b>What each test can fail on</b> (each was mutation-checked against the code as
 * written; see the report):
 * <ul>
 *   <li>{@code deathIsReadBackWithItsKindAndMachineReadableCause} — mutating
 *       {@code project} to drop the {@code key} makes {@code kind} become RAW and
 *       {@code death} disappear.</li>
 *   <li>{@code voidAndCreeperDeathsAreDistinguishable} — mutating the
 *       {@code .player}/{@code .item} stripping to a plain substring of the key makes
 *       both report {@code UNKNOWN} instead of distinct causes.</li>
 *   <li>{@code creeperKillReportsExplosionPlusTheKillerName} — the honesty case: the
 *       cause alone says "explosion", and only the killer argument says "Creeper".</li>
 *   <li>{@code unreadModeDoesNotRedeliverAndIdleTurnIsCheap} — mutating
 *       {@code markRead} to a plain store makes the second call re-deliver.</li>
 *   <li>{@code playerChatIsAClaimNotAFact} — mutating the authority table makes a
 *       typed line report SERVER_FACT.</li>
 * </ul>
 */
public class ChatReadToolTest {

    private EventBus bus;
    private ChatLog log;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        GameClock.INSTANCE.reset();
        bus = new EventBus();
        log = new ChatLog(64);
        log.attach(bus);
        // The REAL tap handler, default production summarizer registry.
        channel = new EmbeddedChannel(new NettyTap.PacketTapHandler(bus));
    }

    // ===== helpers =====

    /** Push a real packet inbound through the real tap. */
    private void deliver(Object packet) {
        channel.writeInbound(packet);
    }

    private SyncToolSpecification tool() {
        // seams == null => tapInstalled() false, so the honest "tap down" branch is
        // reachable; localName supplied so self-echo is decidable.
        return new ChatTools(log, null, () -> "Agent").chatRead();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Map<String, Object> args) {
        return Json.readObject(textOf(tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", args))));
    }

    /** First TextContent's text. {@code Content} is a sealed union; narrow it. */
    private static String textOf(CallToolResult res) {
        for (Content c : res.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        throw new AssertionError("no TextContent in " + res.content());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> entries(Map<String, Object> out) {
        Object e = out.get("entries");
        return e instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> out, int i) {
        return (Map<String, Object>) entries(out).get(i);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deathOf(Map<String, Object> row) {
        Object d = row.get("death");
        assertTrue("death row has no death block: " + row, d instanceof Map);
        return (Map<String, Object>) d;
    }

    // ===== the core gap: a death arrives and the agent can read it =====

    /**
     * The acceptance case. A vanilla death message — the one thing the agent most needs
     * to act on — travels the real receive path and comes back with its KIND and its
     * machine-readable CAUSE, not as prose.
     */
    @Test
    public void deathIsReadBackWithItsKindAndMachineReadableCause() {
        // Exactly what EntityDamageSource.getDeathMessage builds for a fall.
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.fall", new Object[]{"Agent"}),
                (byte) 1));

        Map<String, Object> out = call(Map.of());
        assertEquals("one message came through the tap", 1, entries(out).size());

        Map<String, Object> row = at(out, 0);
        assertEquals("kind must be DEATH, not RAW or SYSTEM", "DEATH", row.get("kind"));
        assertEquals("locale-independent key is exposed", "death.attack.fall", row.get("key"));
        assertEquals("a death is a server-computed fact", "SERVER_FACT", row.get("authority"));

        Map<String, Object> death = deathOf(row);
        assertEquals("cause is a type, not a sentence", "FALL", death.get("cause"));
        assertEquals("and it names the vanilla key suffix it stands for",
                "fall", death.get("vanillaSuffix"));
        assertEquals("no killer argument on a plain fall", null, death.get("killer"));
    }

    /**
     * The distinction the brief names: "you died to a creeper" vs "you died to the
     * void" must NOT both read as some vague death. These are the two facts a model
     * branches on, and they demand opposite next actions (fight/dodge vs stop walking
     * off the edge).
     */
    @Test
    public void voidAndCreeperDeathsAreDistinguishable() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.outOfWorld", new Object[]{"Agent"}),
                (byte) 1));
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.explosion.player",
                        new Object[]{"Agent", "Creeper"}),
                (byte) 1));

        Map<String, Object> out = call(Map.of());
        assertEquals(2, entries(out).size());
        assertEquals("the void has its own cause", "OUT_OF_WORLD", deathOf(at(out, 0)).get("cause"));
        assertEquals("a creeper kill is a distinct cause from the void",
                "EXPLOSION", deathOf(at(out, 1)).get("cause"));
        assertFalse("and the two are not the same fact",
                deathOf(at(out, 0)).get("cause").equals(deathOf(at(out, 1)).get("cause")));
    }

    /**
     * Honesty: a vanilla creeper kill is NOT its own damage type. Verified in the
     * client source, not assumed — {@code EntityCreeper.explode()} →
     * {@code World.createExplosion(this, …)} → {@code DamageSource.setExplosionSource}
     * builds {@code new EntityDamageSource("explosion.player", …)} because
     * {@code Explosion.getExplosivePlacedBy()} returns any EntityLivingBase exploder.
     * So {@code cause=EXPLOSION} alone understates it, and the killer argument is what
     * names the Creeper. This test exists so nobody "fixes" the cause enum by inventing
     * a CREEPER member that vanilla does not send.
     */
    @Test
    public void creeperKillReportsExplosionPlusTheKillerName() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.explosion.player",
                        new Object[]{"Agent", "Creeper"}),
                (byte) 1));

        Map<String, Object> death = deathOf(at(call(Map.of()), 0));
        assertEquals("vanilla sends explosion.player, not creeper",
                "EXPLOSION", death.get("cause"));
        assertEquals("the %2$s argument is what names the creeper", "Creeper", death.get("killer"));
    }

    /** The killer-and-item variant: argument POSITION carries the role, not prose order. */
    @Test
    public void deathWithAKillerAndAnItemReportsBothByPosition() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.player.item",
                        new Object[]{"Agent", "Steve", "Diamond Sword"}),
                (byte) 1));

        Map<String, Object> death = deathOf(at(call(Map.of()), 0));
        assertEquals("PLAYER", death.get("cause"));
        assertEquals("%2$s is the killer", "Steve", death.get("killer"));
        assertEquals("%3$s is the item", "Diamond Sword", death.get("item"));
    }

    // ===== the honesty boundary =====

    /**
     * A player's line is server-TRANSMITTED but player-AUTHORED. The transmission
     * says nothing about who wrote the words, and a model that treats "a player said
     * there is a creeper" as a fact about the world is reasoning on sand.
     */
    @Test
    public void playerChatIsAClaimNotAFact() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("chat.type.text",
                        new Object[]{"Steve", "theres a creeper at 100 64 -200"}),
                (byte) 1));

        Map<String, Object> row = at(call(Map.of()), 0);
        assertEquals("a typed line is PLAYER", "PLAYER", row.get("kind"));
        assertEquals("and is only a CLAIM", "PLAYER_CLAIM", row.get("authority"));
        assertEquals("the sender is argument 1, not prose", List.of("Steve",
                "theres a creeper at 100 64 -200"), row.get("args"));
    }

    /** A keyless literal cannot be attributed to anyone — server, plugin, or proxy. */
    @Test
    public void keylessLiteralIsUnknownAuthority() {
        deliver(new S02PacketChat(
                new ChatComponentText("[Plugin] Welcome to the server!"), (byte) 1));

        Map<String, Object> row = at(call(Map.of()), 0);
        assertEquals("no key means RAW", "RAW", row.get("kind"));
        assertEquals("and UNKNOWN, never a server fact", "UNKNOWN", row.get("authority"));
        assertEquals("the text still comes through", "[Plugin] Welcome to the server!",
                row.get("text"));
    }

    /**
     * The client-side echo, named: the agent's own send_chat coming back at it is
     * genuinely inbound but carries nothing it did not already have.
     */
    @Test
    public void ownChatComingBackIsFlaggedAsSelfEcho() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("chat.type.text",
                        new Object[]{"Agent", "I am heading to the mines"}),
                (byte) 1));
        deliver(new S02PacketChat(
                new ChatComponentTranslation("chat.type.text",
                        new Object[]{"Steve", "ok"}),
                (byte) 1));

        Map<String, Object> out = call(Map.of());
        assertEquals("my own line is an echo", Boolean.TRUE, at(out, 0).get("selfEcho"));
        assertEquals("someone else's is not", Boolean.FALSE, at(out, 1).get("selfEcho"));
    }

    /**
     * Tri-state honesty: with no readable local name, "this is not an echo" is a claim
     * the tool cannot support, so it must be null rather than a confident false.
     */
    @Test
    public void selfEchoIsNullWhenTheLocalNameIsUnreadable() {
        deliver(new S02PacketChat(
                new ChatComponentTranslation("chat.type.text",
                        new Object[]{"Agent", "hello"}), (byte) 1));

        String text = textOf(new ChatTools(log, null, null).chatRead().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of())));
        assertTrue("an undecidable echo must be null, not false — got: " + text,
                text.contains("\"selfEcho\":null"));
    }

    // ===== polling cost: the reason this design exists =====

    /**
     * The brief's constraint: this runs EVERY turn, so it must not re-deliver history.
     * An idle turn must be empty.
     */
    @Test
    public void unreadModeDoesNotRedeliverAndIdleTurnIsCheap() {
        deliver(new S02PacketChat(new ChatComponentText("first"), (byte) 1));

        Map<String, Object> first = call(Map.of());
        assertEquals("the first turn sees the message", 1, entries(first).size());

        Map<String, Object> second = call(Map.of());
        assertEquals("an idle turn must cost nothing — no re-delivery",
                0, entries(second).size());

        deliver(new S02PacketChat(new ChatComponentText("second"), (byte) 1));
        Map<String, Object> third = call(Map.of());
        assertEquals("only the new message comes back", 1, entries(third).size());
        assertEquals("and it is the new one", "second", at(third, 0).get("text"));
    }

    /** An explicit replay re-reads a range and still advances the cursor to its newest. */
    @Test
    public void statelessReplayDoesNotRewindTheUnreadCursor() {
        deliver(new S02PacketChat(new ChatComponentText("one"), (byte) 1));
        deliver(new S02PacketChat(new ChatComponentText("two"), (byte) 1));
        call(Map.of());                       // consume both -> cursor at newest
        long cursorAfter = log.readCursor();

        Map<String, Object> replay = call(Map.of("sinceSeq", 0L));
        assertEquals("a replay re-delivers the history it was asked for",
                2, entries(replay).size());
        assertEquals("but does NOT rewind the unread cursor",
                cursorAfter, ((Number) replay.get("cursor")).longValue());

        assertEquals("so the next unread read is still empty", 0, entries(call(Map.of())).size());
    }

    /**
     * {@code markRead} is public API with a stated "never moves backwards" contract.
     * Pinned DIRECTLY, because it is NOT observable through the read path: the read
     * path only ever marks with a seq taken from the ring, which is always >= the
     * current cursor, so mutating {@code accumulateAndGet(…, Math::max)} to a plain
     * {@code set} survived the replay test above (measured). Asserting it through
     * {@code chat_read} would have been a test that cannot fail.
     */
    @Test
    public void markReadNeverRewindsTheCursor() {
        log.markRead(7L);
        assertEquals(7L, log.readCursor());
        log.markRead(3L);
        assertEquals("a lower seq must not rewind the unread cursor", 7L, log.readCursor());
        log.markRead(9L);
        assertEquals("a higher seq advances it", 9L, log.readCursor());
    }

    /**
     * A filter that silently returns nothing would be indistinguishable from "the
     * server was silent", so the kinds filter must actually pass what it claims to.
     */
    @Test
    public void kindsFilterKeepsOnlyTheRequestedKind() {
        deliver(new S02PacketChat(new ChatComponentText("just text"), (byte) 1));
        deliver(new S02PacketChat(
                new ChatComponentTranslation("death.attack.lava", new Object[]{"Agent"}),
                (byte) 1));

        Map<String, Object> deaths = call(Map.of("kinds", List.of("DEATH")));
        assertEquals("only the death survives the filter", 1, entries(deaths).size());
        assertEquals("DEATH", at(deaths, 0).get("kind"));
        assertEquals("LAVA", deathOf(at(deaths, 0)).get("cause"));
    }

    /** Action bar is a different channel from the chat log, and stays distinguishable. */
    @Test
    public void actionBarIsItsOwnChannel() {
        deliver(new S02PacketChat(new ChatComponentText("on the bar"), (byte) 2));
        deliver(new S02PacketChat(new ChatComponentText("in the log"), (byte) 1));

        Map<String, Object> out = call(Map.of());
        assertEquals("type byte 2 is the action bar", "ACTION_BAR", at(out, 0).get("channel"));
        assertEquals("type byte 1 is the chat log", "CHAT", at(out, 1).get("channel"));
    }

    // ===== honesty about what is NOT observed =====

    /**
     * An empty ring with no tap is not "nobody spoke" — it is "we are not listening".
     * Returning a clean empty list would be a lie the model would act on.
     */
    @Test
    public void absentTapIsAnExplicitErrorNotAnAuthoritativeSilence() {
        CallToolResult res = tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of()));
        assertTrue("a dead sensor must not look like an authoritative empty",
                Boolean.TRUE.equals(res.isError()));
        assertTrue("and the message must say why: " + res.content(),
                textOf(res).contains("NOT an authoritative"));
    }

    /** A packet with no typed projection is dropped, not guessed at. */
    @Test
    public void nonChatPacketsAreIgnored() {
        deliver(new net.minecraft.network.play.server.S06PacketUpdateHealth(20f, 20, 5f));
        CallToolResult res = tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of()));
        // Still an error (no tap), proving the health packet was NOT recorded as chat.
        assertTrue("a health packet is not chat",
                Boolean.TRUE.equals(res.isError()));
    }

    /**
     * An S02 that reaches the tap but carries NO typed projection must be DROPPED,
     * not recorded as an empty {@code RAW} row. Recording it would manufacture a
     * message with no key and no text — an invented observation, and one a model would
     * read as "the server sent something blank". Mutating the {@code f == null ||
     * f.isEmpty()} guard to accept it survives every other test here (measured).
     */
    @Test
    public void chatPacketWithNoTypedProjectionIsDroppedNotGuessed() {
        bus.publish(new net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent(
                new NettyTap.PacketTapHandler.MessageSnapshot(
                        "net.minecraft.network.play.server.S02PacketChat", "chat type=1", null)));
        bus.publish(new net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent(
                new NettyTap.PacketTapHandler.MessageSnapshot(
                        "net.minecraft.network.play.server.S02PacketChat", "chat type=1",
                        Map.of())));

        assertEquals("nothing was recorded: a typeless S02 is not an observation", 0, log.size());
    }

    /** Outbound chat must not be recorded — it is the agent's own transmission. */
    @Test
    public void outboundChatIsNotRecordedAsInbound() {
        channel.writeOutbound(new net.minecraft.network.play.client.C01PacketChatMessage("mine"));
        CallToolResult res = tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of()));
        assertTrue("C01 is outbound; reading it as inbound would double-count the agent",
                Boolean.TRUE.equals(res.isError()));
    }

    /**
     * A reader that fell behind the ring's eviction window must be TOLD it missed
     * messages. Returning the surviving tail alone reads as "that was all of it",
     * which is a different and wrong claim — the model would believe it had seen
     * everything when a death scrolled out of the ring unread.
     */
    @Test
    public void aReaderThatFellBehindIsToldItMissedMessages() {
        ChatLog small = new ChatLog(2);
        small.attach(bus);
        for (String s : new String[]{"a", "b", "c", "d"}) {
            deliver(new S02PacketChat(new ChatComponentText(s), (byte) 1));
        }
        assertEquals("the ring now starts at seq 3", 3L, small.oldestSeq());

        ChatTools tools = new ChatTools(small, null, () -> "Agent");
        // A stale cursor (0) sits below the floor (3): seqs 1-2 were evicted unread.
        Map<String, Object> behind = Json.readObject(textOf(
                tools.chatRead().callHandler().apply(null,
                        new CallToolRequest("chat_read", Map.of("sinceSeq", 0L)))));
        assertEquals("the gap is reported, not hidden", 2L,
                ((Number) behind.get("missed")).longValue());

        // A caught-up reader must NOT be told it missed anything.
        small.markRead(small.newestSeq());
        Map<String, Object> caughtUp = Json.readObject(textOf(
                tools.chatRead().callHandler().apply(null,
                        new CallToolRequest("chat_read", Map.of()))));
        assertFalse("a caught-up reader is not behind",
                caughtUp.containsKey("missed"));
    }

    /** The ring evicts oldest and reports the floor so a gap is never silent. */
    @Test
    public void evictionExposesTheFloorSoAGapIsNotSilent() {
        ChatLog small = new ChatLog(2);
        small.attach(bus);
        deliver(new S02PacketChat(new ChatComponentText("a"), (byte) 1));
        deliver(new S02PacketChat(new ChatComponentText("b"), (byte) 1));
        deliver(new S02PacketChat(new ChatComponentText("c"), (byte) 1));

        assertEquals("the ring keeps only its capacity", 2, small.size());
        assertTrue("the floor is observable", small.oldestSeq() > 0L);
        assertEquals("the newest is the newest", 3L, small.newestSeq());
    }

    /**
     * The gap-detection case: with no tap installed, an empty read is NOT "nobody spoke".
     *
     * <p><b>This is a claim that used to have no test at all.</b> It was established on a live
     * client by a script that lived in {@code _scratch/} and was then deleted, which left the
     * audit document asserting a behaviour nothing in the repository could check. The branch is
     * reachable from {@link #tool()} -- which passes {@code seams == null} on purpose -- so it
     * costs one test to make the claim permanent instead of legendary.
     *
     * <p>Both halves matter and either alone is satisfiable by the wrong thing: a reply that
     * says the tap is missing but still returns {@code entries: []} with {@code isError: false}
     * reads as an authoritative silence to anything that only parses the JSON, and a reply that
     * refuses without naming the fix leaves the agent with nothing to do next.
     */
    @Test
    public void anAbsentTapIsRefusedRatherThanReportedAsNobodySpoke() {
        CallToolResult res = tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of()));
        String text = textOf(res);

        assertTrue("a refused read must be flagged as an error, not returned as data: " + text,
                res.isError());
        assertFalse("the reply must NOT be parseable as an empty authoritative read: " + text,
                text.startsWith("{"));
        assertTrue("it must say the silence is not authoritative: " + text,
                text.contains("NOT an authoritative"));
        assertTrue("and it must name the tool that fixes it: " + text,
                text.contains("seam_netty_install"));
    }

    /**
     * The stale-name case from the same live session: replies used to say "Install it via the
     * seam netty-tap tool", and no tool by that name exists -- the real ones are
     * {@code seam_netty_install} / {@code seam_netty_uninstall}. An instruction an agent cannot
     * follow is worse than no instruction, and it survives review because it reads as helpful.
     */
    @Test
    public void theRefusalNamesTheToolThatExistsAndNotTheOneThatDoesNot() {
        String text = textOf(tool().callHandler()
                .apply(null, new CallToolRequest("chat_read", Map.of())));
        assertFalse("'netty-tap' is not a tool name in this surface: " + text,
                text.contains("netty-tap"));

        // The same defect lived in the DESCRIPTION until this test existed. The reply was fixed
        // and the description was not, which is the shape this whole file is about: the half of
        // the surface an agent reads first is the half nobody re-reads.
        String description = tool().tool().description();
        assertFalse("the description must not advertise a tool that does not exist: " + description,
                description.contains("netty-tap"));
        assertTrue("and it must name the real one: " + description,
                description.contains("seam_netty_install"));
    }

    /**
     * The other side of the branch, provable without a live channel: the refusal is conditioned on
     * <em>nothing was ever seen</em>, not on "no tap". A log that holds a message the kind filter
     * excludes returns an ordinary empty read even with the tap down.
     *
     * <p>Without this, an implementation that refuses unconditionally satisfies both tests above.
     * A real {@code SeamController} cannot be made to report an installed tap in a headless JVM --
     * {@code installNettyTap} needs a live channel -- so this reaches the same branch through the
     * other condition rather than by faking the controller.
     */
    @Test
    public void aFilteredEmptyReadIsNotTheSameAsHavingSeenNothing() {
        deliver(new S02PacketChat(new ChatComponentText("a server notice"), (byte) 1));
        CallToolResult res = tool().callHandler().apply(null,
                new CallToolRequest("chat_read", Map.of("kinds", List.of("DEATH"))));
        String text = textOf(res);

        assertFalse("a log that holds something is not the 'tap down' case: " + text,
                res.isError());
        Map<String, Object> out = Json.readObject(text);
        assertEquals("the filter excluded everything, so the read is empty", 0L,
                ((Number) out.get("count")).longValue());
        assertTrue("and the log is observably NOT empty, which is why this differs",
                ((Number) out.get("oldestSeq")).longValue() > 0L);
    }
}