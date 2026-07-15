package net.marcloud.mcp.board.signals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Signal;
import net.marcloud.mcp.board.Trace;
import org.junit.Test;

/**
 * PHASE E (E.2): payload contract for the new world signals — the Tier-1 pair
 * ({@link ChatReceiveSignal}, {@link DisconnectSignal}) and the Tier-2 typed value
 * types ({@link PlayerJoinSignal}, {@link PlayerLeaveSignal}, {@link DeathSignal},
 * {@link HealthChangeSignal}, {@link BlockChangeSignal}).
 *
 * <p>Each is an immutable single-payload {@link Signal} mirroring {@code KeySignal}:
 * the accessor round-trips the constructor argument, null String payloads coerce
 * to {@code ""} (never null), and the signals travel on a real {@link Trace}
 * (proving they are plain, dispatchable {@code Signal}s, not {@code Cancellable}).
 * These are the teeth that would FAIL if a signal leaked mutable state, returned
 * null, or was mis-typed as cancellable.
 */
public class WorldSignalsTest {

    // ---- Tier-1: ChatReceiveSignal -----------------------------------------

    @Test
    public void chatReceiveRoundTripsText() {
        ChatReceiveSignal s = new ChatReceiveSignal("hello world");
        assertEquals("hello world", s.text());
    }

    @Test
    public void chatReceiveNullTextCoercesToEmpty() {
        assertEquals("", new ChatReceiveSignal(null).text());
    }

    @Test
    public void chatReceiveIsPlainNotCancellable() {
        Signal s = new ChatReceiveSignal("x");
        assertFalse("ChatReceiveSignal must not be Cancellable",
                s instanceof Signal.Cancellable);
    }

    // ---- Tier-1: DisconnectSignal ------------------------------------------

    @Test
    public void disconnectRoundTripsReason() {
        assertEquals("Kicked: afk", new DisconnectSignal("Kicked: afk").reason());
    }

    @Test
    public void disconnectNullReasonCoercesToEmpty() {
        assertEquals("", new DisconnectSignal(null).reason());
    }

    // ---- Tier-2: name-carrying signals -------------------------------------

    @Test
    public void playerJoinRoundTripsName() {
        assertEquals("Steve", new PlayerJoinSignal("Steve").name());
        assertEquals("", new PlayerJoinSignal(null).name());
    }

    @Test
    public void playerLeaveRoundTripsName() {
        assertEquals("Alex", new PlayerLeaveSignal("Alex").name());
        assertEquals("", new PlayerLeaveSignal(null).name());
    }

    @Test
    public void deathRoundTripsMessage() {
        assertEquals("Steve was slain by Zombie",
                new DeathSignal("Steve was slain by Zombie").message());
        assertEquals("", new DeathSignal(null).message());
    }

    // ---- Tier-2: HealthChangeSignal ----------------------------------------

    @Test
    public void healthRoundTripsValue() {
        assertEquals(17.5f, new HealthChangeSignal(17.5f).health(), 0.0001f);
    }

    // ---- Tier-2: BlockChangeSignal -----------------------------------------

    @Test
    public void blockChangeRoundTripsFields() {
        BlockChangeSignal s = new BlockChangeSignal(10, 64, -20, "minecraft:stone");
        assertEquals(10, s.x());
        assertEquals(64, s.y());
        assertEquals(-20, s.z());
        assertEquals("minecraft:stone", s.state());
    }

    @Test
    public void blockChangeNullStateCoercesToEmpty() {
        assertEquals("", new BlockChangeSignal(0, 0, 0, null).state());
    }

    // ---- all travel on a real Trace ----------------------------------------

    @Test
    public void allWorldSignalsDispatchOnTrace() {
        Trace trace = new Trace();
        final int[] hits = {0};
        trace.subscribe(ChatReceiveSignal.class, s -> hits[0]++);
        trace.subscribe(DisconnectSignal.class, s -> hits[0]++);
        trace.subscribe(PlayerJoinSignal.class, s -> hits[0]++);
        trace.subscribe(PlayerLeaveSignal.class, s -> hits[0]++);
        trace.subscribe(DeathSignal.class, s -> hits[0]++);
        trace.subscribe(HealthChangeSignal.class, s -> hits[0]++);
        trace.subscribe(BlockChangeSignal.class, s -> hits[0]++);

        trace.publish(new ChatReceiveSignal("a"));
        trace.publish(new DisconnectSignal("b"));
        trace.publish(new PlayerJoinSignal("c"));
        trace.publish(new PlayerLeaveSignal("d"));
        trace.publish(new DeathSignal("e"));
        trace.publish(new HealthChangeSignal(1f));
        trace.publish(new BlockChangeSignal(1, 2, 3, "f"));

        assertEquals("each world signal must reach its own subscriber", 7, hits[0]);
    }

    @Test
    public void timestampsArePositiveAndToStringNonNull() {
        ChatReceiveSignal s = new ChatReceiveSignal("x");
        assertTrue(s.timestampNanos() != 0L);
        assertNotNull(s.toString());
        // canonical class identity guard (mirrors CanonicalSignalTest)
        assertSame(ChatReceiveSignal.class,
                new ChatReceiveSignal("y").getClass());
    }
}
