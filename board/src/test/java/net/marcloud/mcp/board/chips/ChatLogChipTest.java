package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.persist.Store;
import net.marcloud.mcp.board.signals.ChatSendSignal;

/**
 * Regression tests for {@link ChatLogChip} — the first REAL consumer that wires
 * the {@link ChatSendSignal} bus signal and the wave-A {@link Store} persistence
 * engine together. These fail on the old tree (the class does not exist) and, more
 * importantly, assert the framework's headline lifecycle guarantee end to end:
 * <b>enable -> receives events -> disable -> no longer receives</b>, driven purely
 * on Board's own {@link Trace} test bus with no live Minecraft.
 */
public class ChatLogChipTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** enable -> receives -> disable -> no longer receives, all on a headless Trace. */
    @Test
    public void observesChatOnlyWhileEnabled() {
        Trace trace = new Trace();
        ChatLogChip chip = new ChatLogChip(trace);

        // disabled: no subscription, nothing observed
        trace.publish(new ChatSendSignal("before"));
        assertEquals(0, trace.subscriberCount());
        assertEquals(0L, chip.sentCount());
        assertEquals("", chip.lastMessage());

        // enable subscribes (via the leak-proof track() bag) and starts observing
        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());
        trace.publish(new ChatSendSignal("hello"));
        trace.publish(new ChatSendSignal("world"));
        assertEquals(2L, chip.sentCount());
        assertEquals("world", chip.lastMessage());

        // disable auto-cancels the tracked subscription and stops observing
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
        trace.publish(new ChatSendSignal("after"));
        assertEquals(2L, chip.sentCount());
        assertEquals("world", chip.lastMessage());
    }

    /** Re-enabling after a disable must leave exactly one live subscription (no leak). */
    @Test
    public void reEnableDoesNotDoubleSubscribe() {
        Trace trace = new Trace();
        ChatLogChip chip = new ChatLogChip(trace);
        chip.setEnabled(true);
        chip.setEnabled(false);
        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());
        trace.publish(new ChatSendSignal("x"));
        assertEquals(1L, chip.sentCount());
    }

    /** The chip is a pure OBSERVER: it must never veto the outgoing chat send. */
    @Test
    public void observerNeverCancelsTheSend() {
        Trace trace = new Trace();
        ChatLogChip chip = new ChatLogChip(trace);
        chip.setEnabled(true);
        ChatSendSignal signal = trace.publish(new ChatSendSignal("keep me"));
        assertFalse("ChatLogChip must observe, never veto, the send", signal.isCancelled());
        assertEquals(1L, chip.sentCount());
    }

    /**
     * The observed counter/message SURVIVE a save/load through the real wave-A
     * {@link Store}: this is the first end-to-end proof a {@link Chip}'s state
     * persists via the persistence engine, keyed by the STABLE id (not the label).
     */
    @Test
    public void persistsObservedStateThroughStoreRoundTrip() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("board.json");

        // (1) a chip observes two messages, then is saved under its stable id
        Trace traceA = new Trace();
        ChatLogChip saved = new ChatLogChip(traceA);
        saved.setEnabled(true);
        traceA.publish(new ChatSendSignal("one"));
        traceA.publish(new ChatSendSignal("two"));
        assertEquals(2L, saved.sentCount());

        new Store(file).register(ChatLogChip.PERSIST_ID, saved).save();

        // (2) a FRESH chip (defaults) loads that file and recovers the state
        ChatLogChip loaded = new ChatLogChip(new Trace());
        assertEquals(0L, loaded.sentCount());
        new Store(file).register(ChatLogChip.PERSIST_ID, loaded).load();
        assertEquals(2L, loaded.sentCount());
        assertEquals("two", loaded.lastMessage());
    }

    /**
     * reset-before-load semantics: a missing file leaves a previously-dirty chip
     * at defaults rather than a stale value (the Store contract, exercised through
     * a chip for the first time).
     */
    @Test
    public void loadFromMissingFileResetsToDefaults() {
        Path file = tmp.getRoot().toPath().resolve("does-not-exist.json");
        Trace trace = new Trace();
        ChatLogChip chip = new ChatLogChip(trace);
        chip.setEnabled(true);
        trace.publish(new ChatSendSignal("dirty"));
        assertEquals(1L, chip.sentCount());

        new Store(file).register(ChatLogChip.PERSIST_ID, chip).load();
        assertEquals("missing file must reset to defaults, not keep stale state",
                0L, chip.sentCount());
        assertEquals("", chip.lastMessage());
    }

    /** The full path: managed by a Matrix behind the Board facade, toggleable. */
    @Test
    public void worksThroughBoardFacadeAndMatrix() {
        Board.shutdown();
        Board.trace().clear();
        Board.init();
        try {
            Matrix<Chip> features = Board.features();
            ChatLogChip chip = new ChatLogChip(); // binds Board.trace()
            features.add(chip);
            chip.setEnabled(true);
            Board.trace().publish(new ChatSendSignal("via board"));
            assertEquals(1L, chip.sentCount());

            features.remove(chip); // removal disables -> auto-cancels the subscription
            Board.trace().publish(new ChatSendSignal("after removal"));
            assertEquals(1L, chip.sentCount());
        } finally {
            Board.shutdown();
            Board.trace().clear();
        }
    }

    @Test
    public void exposesStableId() {
        assertEquals("ChatLogChip", new ChatLogChip(new Trace()).id());
        assertEquals("chat", new ChatLogChip(new Trace()).category());
        assertTrue("stable persist id must not be the display name",
                !ChatLogChip.PERSIST_ID.equals(new ChatLogChip(new Trace()).name()));
    }
}
