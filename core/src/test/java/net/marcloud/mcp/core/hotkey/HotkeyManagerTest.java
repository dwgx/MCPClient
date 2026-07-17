package net.marcloud.mcp.core.hotkey;

import static org.junit.Assert.assertEquals;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Headless coverage for {@link HotkeyManager}'s edge detection — the behaviour the whole
 * hotkey system rests on. A binding must fire ONCE per physical press (up→down), never again
 * while held, and re-arm after release. A regression to "fire while down" would re-open/close
 * the launcher every tick RSHIFT is held (dozens of times a second), so these assertions fail
 * loudly on that mistake.
 */
public class HotkeyManagerTest {

    private static final int RSHIFT = 0x36;

    @Test
    public void firesOnceOnPressNotWhileHeld() {
        HotkeyManager m = new HotkeyManager();
        AtomicInteger fires = new AtomicInteger();
        m.bind(RSHIFT, "test", fires::incrementAndGet);

        // Key not down: nothing fires.
        assertEquals("no fire when key up", 0, m.onKeysDown(Set.of()));
        // Up -> down: exactly one fire.
        assertEquals("one fire on press edge", 1, m.onKeysDown(Set.of(RSHIFT)));
        // Still held over several polls: NO further fires (this is the key regression guard).
        assertEquals("no fire while held (poll 2)", 0, m.onKeysDown(Set.of(RSHIFT)));
        assertEquals("no fire while held (poll 3)", 0, m.onKeysDown(Set.of(RSHIFT)));
        assertEquals("total fires after hold", 1, fires.get());
    }

    @Test
    public void reArmsAfterRelease() {
        HotkeyManager m = new HotkeyManager();
        AtomicInteger fires = new AtomicInteger();
        m.bind(RSHIFT, "test", fires::incrementAndGet);

        m.onKeysDown(Set.of(RSHIFT)); // press 1
        m.onKeysDown(Set.of());       // release
        m.onKeysDown(Set.of(RSHIFT)); // press 2
        assertEquals("two presses => two fires", 2, fires.get());
    }

    @Test
    public void onlyTheBoundKeyFires() {
        HotkeyManager m = new HotkeyManager();
        AtomicInteger fires = new AtomicInteger();
        m.bind(RSHIFT, "test", fires::incrementAndGet);
        assertEquals("an unrelated key never fires the binding", 0, m.onKeysDown(Set.of(0x11 /* W */)));
        assertEquals(0, fires.get());
    }

    @Test
    public void aThrowingBindingDoesNotBlockOthers() {
        HotkeyManager m = new HotkeyManager();
        AtomicInteger good = new AtomicInteger();
        m.bind(RSHIFT, "boom", () -> {
            throw new RuntimeException("intentional");
        });
        m.bind(RSHIFT, "good", good::incrementAndGet);
        // Both bindings are on RSHIFT; the throwing one must not stop the good one firing.
        int fired = m.onKeysDown(Set.of(RSHIFT));
        assertEquals("both bindings attempted on the same press", 2, fired);
        assertEquals("the non-throwing binding still ran", 1, good.get());
    }

    @Test
    public void nullOrNegativeBindsAreIgnored() {
        HotkeyManager m = new HotkeyManager();
        m.bind(RSHIFT, "null-action", null);
        m.bind(-5, "negative-key", () -> { });
        assertEquals("invalid binds are dropped, not half-registered", 0, m.bindingCount());
    }
}
