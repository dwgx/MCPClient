package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.util.MovementInput;
import org.junit.Test;

/**
 * Teeth for {@link ActMovementInput}: when the MOVE slot is ACTIVE the AI's intent
 * flows through and overrides vanilla; when idle it delegates entirely to the
 * wrapped vanilla input (behaviourally invisible).
 */
public class ActMovementInputTest {

    /** A programmable stand-in for the vanilla {@code MovementInputFromOptions}. */
    private static final class ScriptedVanilla extends MovementInput {
        float f;
        float s;
        boolean j;
        boolean sn;
        int updates;

        @Override
        public void updatePlayerMoveState() {
            updates++;
            this.moveForward = f;
            this.moveStrafe = s;
            this.jump = j;
            this.sneak = sn;
        }
    }

    /** A hand-driven MoveIntentView so we do not need a full runtime here. */
    private static final class View implements MoveIntentView {
        boolean active;
        float f;
        float s;
        boolean j;
        boolean sn;
        boolean sp;

        public boolean moveActive() {
            return active;
        }

        public float moveForward() {
            return f;
        }

        public float moveStrafe() {
            return s;
        }

        public boolean jump() {
            return j;
        }

        public boolean sneak() {
            return sn;
        }

        public boolean sprint() {
            return sp;
        }
    }

    @Test
    public void delegatesToVanillaWhenIdle() {
        ScriptedVanilla vanilla = new ScriptedVanilla();
        vanilla.f = 1.0f;
        vanilla.s = -1.0f;
        vanilla.j = true;
        View view = new View(); // inactive
        ActMovementInput input = new ActMovementInput(vanilla, view);

        input.updatePlayerMoveState();

        assertEquals("vanilla was consulted", 1, vanilla.updates);
        assertEquals("idle => vanilla forward passes through", 1.0f, input.moveForward, 1e-6);
        assertEquals(-1.0f, input.moveStrafe, 1e-6);
        assertTrue(input.jump);
        assertFalse("idle => no sprint request", input.sprintRequested());
    }

    @Test
    public void overridesVanillaWhenActive() {
        ScriptedVanilla vanilla = new ScriptedVanilla();
        vanilla.f = 1.0f;   // vanilla says forward
        vanilla.s = 0.0f;
        View view = new View();
        view.active = true;
        view.f = -1.0f;     // AI says backward
        view.s = 0.5f;
        view.sn = true;
        view.sp = true;
        ActMovementInput input = new ActMovementInput(vanilla, view);

        input.updatePlayerMoveState();

        assertEquals("vanilla still runs first (side effects preserved)", 1, vanilla.updates);
        assertEquals("active => AI forward overrides vanilla", -1.0f, input.moveForward, 1e-6);
        assertEquals(0.5f, input.moveStrafe, 1e-6);
        assertTrue(input.sneak);
        assertTrue("active sprint intent is exposed", input.sprintRequested());
    }

    @Test
    public void originalIsRetrievableForRestore() {
        ScriptedVanilla vanilla = new ScriptedVanilla();
        ActMovementInput input = new ActMovementInput(vanilla, new View());
        assertEquals(vanilla, input.original());
    }

    @Test
    public void toggleActiveFlipsBetweenDelegateAndOverride() {
        ScriptedVanilla vanilla = new ScriptedVanilla();
        vanilla.f = 1.0f;
        View view = new View();
        ActMovementInput input = new ActMovementInput(vanilla, view);

        input.updatePlayerMoveState();
        assertEquals(1.0f, input.moveForward, 1e-6); // delegated

        view.active = true;
        view.f = -0.5f;
        input.updatePlayerMoveState();
        assertEquals(-0.5f, input.moveForward, 1e-6); // overridden

        view.active = false;
        input.updatePlayerMoveState();
        assertEquals("reverts to vanilla when slot goes idle", 1.0f, input.moveForward, 1e-6);
    }
}
