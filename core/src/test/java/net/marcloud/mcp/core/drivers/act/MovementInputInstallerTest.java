package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.minecraft.util.MovementInput;
import org.junit.Test;

/**
 * Teeth for {@link MovementInputInstaller} through a fake {@link PlayerInputSlot}:
 * arm swaps in an {@link ActMovementInput}, disarm restores the exact vanilla
 * input, and a player-identity change (world-join / respawn) re-swaps because the
 * new player carries a fresh vanilla input.
 */
public class MovementInputInstallerTest {

    /** In-memory player-input slot: an identity token + its current MovementInput. */
    private static final class FakeSlot implements PlayerInputSlot {
        Object identity;
        MovementInput input;
        Boolean lastSprint;

        public Object playerIdentity() {
            return identity;
        }

        public MovementInput get() {
            return input;
        }

        public void set(MovementInput in) {
            this.input = in;
        }

        public void setSprinting(boolean sprinting) {
            this.lastSprint = sprinting;
        }
    }

    private static final class InactiveView implements MoveIntentView {
        public boolean moveActive() {
            return false;
        }

        public float moveForward() {
            return 0;
        }

        public float moveStrafe() {
            return 0;
        }

        public boolean jump() {
            return false;
        }

        public boolean sneak() {
            return false;
        }

        public boolean sprint() {
            return false;
        }
    }

    private static TickEvent tick() {
        return new TickEvent(1L);
    }

    @Test
    public void armSwapsInOurWrapperOverVanilla() {
        FakeSlot slot = new FakeSlot();
        Object player = new Object();
        MovementInput vanilla = new MovementInput();
        slot.identity = player;
        slot.input = vanilla;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.arm();
        installer.onTick(tick());

        assertTrue("our wrapper is installed", slot.get() instanceof ActMovementInput);
        assertSame("it wraps the exact vanilla input", vanilla,
                ((ActMovementInput) slot.get()).original());
    }

    @Test
    public void disarmRestoresTheExactVanillaInput() {
        FakeSlot slot = new FakeSlot();
        Object player = new Object();
        MovementInput vanilla = new MovementInput();
        slot.identity = player;
        slot.input = vanilla;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.arm();
        installer.onTick(tick());
        assertTrue(slot.get() instanceof ActMovementInput);

        installer.disarm();
        assertSame("disarm restores vanilla exactly", vanilla, slot.get());
        assertFalse(installer.isArmed());
    }

    @Test
    public void reSwapsWhenPlayerIdentityChanges() {
        FakeSlot slot = new FakeSlot();
        Object player1 = new Object();
        MovementInput vanilla1 = new MovementInput();
        slot.identity = player1;
        slot.input = vanilla1;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.arm();
        installer.onTick(tick());
        ActMovementInput first = (ActMovementInput) slot.get();

        // World-join/respawn: a NEW player object with a fresh vanilla input,
        // dropping our previous override.
        Object player2 = new Object();
        MovementInput vanilla2 = new MovementInput();
        slot.identity = player2;
        slot.input = vanilla2;

        installer.onTick(tick());
        ActMovementInput second = (ActMovementInput) slot.get();
        assertNotSame("a fresh wrapper is installed on the new player", first, second);
        assertSame("wrapping the new player's fresh vanilla input", vanilla2, second.original());
    }

    @Test
    public void reInstallsIfOurWrapperWasReplacedByVanilla() {
        FakeSlot slot = new FakeSlot();
        Object player = new Object();
        MovementInput vanilla = new MovementInput();
        slot.identity = player;
        slot.input = vanilla;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.arm();
        installer.onTick(tick());
        assertTrue(slot.get() instanceof ActMovementInput);

        // Something replaced our wrapper with a fresh vanilla input (same player).
        MovementInput replaced = new MovementInput();
        slot.input = replaced;
        installer.onTick(tick());
        assertTrue("wrapper is re-installed", slot.get() instanceof ActMovementInput);
        assertSame(replaced, ((ActMovementInput) slot.get()).original());
    }

    @Test
    public void notArmedDoesNothing() {
        FakeSlot slot = new FakeSlot();
        MovementInput vanilla = new MovementInput();
        slot.identity = new Object();
        slot.input = vanilla;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.onTick(tick()); // never armed
        assertSame("no swap while disarmed", vanilla, slot.get());
    }

    @Test
    public void leavingWorldForgetsInstallSoRejoinReSwaps() {
        FakeSlot slot = new FakeSlot();
        Object player = new Object();
        MovementInput vanilla = new MovementInput();
        slot.identity = player;
        slot.input = vanilla;

        MovementInputInstaller installer = new MovementInputInstaller(slot, new InactiveView());
        installer.arm();
        installer.onTick(tick());
        assertTrue(slot.get() instanceof ActMovementInput);

        // Leave the world.
        slot.identity = null;
        slot.input = null;
        installer.onTick(tick());

        // Re-join with a fresh player + input.
        Object player2 = new Object();
        MovementInput vanilla2 = new MovementInput();
        slot.identity = player2;
        slot.input = vanilla2;
        installer.onTick(tick());
        assertTrue("re-join re-installs the wrapper", slot.get() instanceof ActMovementInput);
        assertSame(vanilla2, ((ActMovementInput) slot.get()).original());
    }
}
