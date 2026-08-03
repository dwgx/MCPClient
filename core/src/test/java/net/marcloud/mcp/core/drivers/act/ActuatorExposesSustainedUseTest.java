package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The actuator seam must expose what a HOLD controller needs, and it is a gap rather than a
 * preference for the same reason the locomotion reads were -- {@link ActActuator}'s javadoc frames
 * the interface as "every game touch a controller needs is a method here".
 *
 * <p>Eating, drawing a bow and blocking are unreachable without these. Vanilla keeps a use alive only
 * while its key is down: {@code Minecraft.java:2118-2122} calls {@code onStoppedUsingItem} on ANY
 * tick where {@code gameSettings.keyBindUseItem.isKeyDown()} is false, so a one-shot start is
 * cancelled within a couple of ticks. Measured on a live client after commit 52647ad: the use count
 * fell from 32 to 0 in about eight ticks and food never rose. Nothing in the act package could reach
 * that key, because {@code core} imported no {@code net.minecraft.client.settings} type at all.
 *
 * <p>Asserted through the INTERFACE rather than by calling the methods, following
 * {@link ActuatorExposesLocomotionStateTest}: the defect is that the seam does not offer them, and a
 * test that called them directly would fail to COMPILE before the fix, which is a build error rather
 * than a red test and names nothing.
 */
public class ActuatorExposesSustainedUseTest {

    /**
     * The four touch points a sustained use cannot be written without.
     *
     * <ul>
     *   <li><b>holdUseKey</b> -- the per-tick assertion. The write must be confirmable, because
     *       {@code KeyBinding.setKeyBindState} is a void that silently does nothing for a keyCode
     *       absent from its static hash.
     *   <li><b>releaseUseKey</b> -- an ACTION, not cleanup: a bow's arrow is created inside
     *       {@code ItemBow.onPlayerStoppedUsing}, reached only when vanilla sees the key up.
     *   <li><b>isUsingItem</b> -- the completion poll, the way {@code blockPresent} is for a dig.
     *   <li><b>itemInUseCount</b> -- carries the item's own duration (32 for food, 72000 for a bow or
     *       a blocking sword), which is how a controller tells a use that will end from one that
     *       never will, without needing to know what the item is.
     * </ul>
     */
    private static final List<String> REQUIRED = Arrays.asList(
            "holdUseKey", "releaseUseKey", "isUsingItem", "itemInUseCount");

    /**
     * Separate because it is the hazard read, and the one most easily "optimised" away.
     *
     * <p>{@code KeyBinding.unPressAllKeys} clears every binding whenever a GUI opens
     * ({@code Minecraft.java:1469} via {@code displayGuiScreen}). A controller that remembers what it
     * wrote instead of reading back would keep reporting a hold that vanilla has already stopped, so
     * the seam has to offer the read-back at all.
     */
    private static final String KEY_STATE_READ = "useKeyHeld";

    private static Method find(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    @Test
    public void theSeamExposesTheSustainedUseTouchPoints() {
        for (String name : REQUIRED) {
            assertNotNull("ActActuator must expose " + name + "(): eating, drawing a bow and blocking "
                    + "are unreachable while nothing can keep vanilla's use key asserted, since "
                    + "Minecraft.java:2118-2122 cancels the use on any tick the key is not down. "
                    + "KeyBinding.setKeyBindState is public static and the binding is a public field, "
                    + "so this needs no reflection and no compat patch.",
                find(ActActuator.class, name));
        }
        assertNotNull("ActActuator must expose " + KEY_STATE_READ + "(): a GUI opening calls "
                + "KeyBinding.unPressAllKeys, so a hold has to read the key back rather than trust "
                + "its own last write, or it reports a hold vanilla already ended.",
            find(ActActuator.class, KEY_STATE_READ));
    }

    @Test
    public void assertingTheKeyIsAnswerableRatherThanFireAndForget() {
        Method m = find(ActActuator.class, "holdUseKey");
        assertNotNull("ActActuator must expose holdUseKey()", m);
        assertEquals("holdUseKey() must return whether the assertion TOOK. setKeyBindState is a void "
                + "that does nothing when the keyCode is not in its hash -- a state a mid-session "
                + "rebind can genuinely produce -- and a hold that cannot tell would claim to be "
                + "holding a key it never pressed.",
            boolean.class, m.getReturnType());
        Method r = find(ActActuator.class, "releaseUseKey");
        assertNotNull("ActActuator must expose releaseUseKey()", r);
        assertEquals("releaseUseKey() must answer too: an unreleased key means the bow did not fire",
            boolean.class, r.getReturnType());
    }

    @Test
    public void theRemainingUseCountIsReadableAsANumber() {
        Method m = find(ActActuator.class, "itemInUseCount");
        assertNotNull("ActActuator must expose itemInUseCount()", m);
        assertEquals("itemInUseCount() must return the tick count itself, not a boolean: the NUMBER "
                + "is what separates food (32) from a bow (72000) and a use that ran out from one "
                + "that was interrupted",
            int.class, m.getReturnType());
    }

    @Test
    public void theFakeActuatorKeptInStep() {
        // The fake is what keeps these controllers headlessly testable. A seam method it does not
        // implement cannot be exercised in a unit test, which would silently push the whole hold
        // channel to live-only verification -- and nobody is running the game.
        for (String name : REQUIRED) {
            assertNotNull("FakeActuator must implement " + name + "() so the hold channel stays "
                    + "headlessly testable", find(FakeActuator.class, name));
        }
        assertNotNull("FakeActuator must implement " + KEY_STATE_READ + "()",
            find(FakeActuator.class, KEY_STATE_READ));

        // Invoked reflectively on purpose: a direct call would not COMPILE before the fix, and a
        // build error is not a red test.
        try {
            FakeActuator act = new FakeActuator();
            Object asserted = find(FakeActuator.class, "holdUseKey").invoke(act);
            assertEquals("the fake's assertion must report that it took", Boolean.TRUE, asserted);
            Object held = find(FakeActuator.class, KEY_STATE_READ).invoke(act);
            assertEquals("and must then read back as held -- a fake that does not model the key state "
                    + "would make every hold assertion in the suite vacuous",
                Boolean.TRUE, held);
            find(FakeActuator.class, "releaseUseKey").invoke(act);
            assertEquals("and read back as released after a release", Boolean.FALSE,
                find(FakeActuator.class, KEY_STATE_READ).invoke(act));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FakeActuator's sustained-use methods must be callable", e);
        }
    }
}
