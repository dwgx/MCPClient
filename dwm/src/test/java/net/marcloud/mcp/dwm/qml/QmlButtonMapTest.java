package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.timer_err.qml4j.render.items.core.MouseEvent;

import org.junit.Test;

/**
 * Pins the LWJGL-index to Qt-flag button mapping, headless.
 *
 * <p>{@link PointerButtonLiveIT} is the behavioural proof — it clicks a real field into focus —
 * but live ITs are opt-in ({@code dwm.live.skip} defaults to true), so on a plain
 * {@code mvn test} nothing would guard the mapping at all. This runs everywhere; between them,
 * the pair covers both "the values are these" and "the values do what we think".
 *
 * <p>The assertions are written against qml4j's own constants rather than literals, so an
 * upstream renumbering shows up as a compile-time or behavioural change here instead of a
 * silently wrong click. The one thing asserted as a literal is that left is <b>not</b> zero,
 * because "the index passed straight through" is the specific bug this exists to catch.
 */
public class QmlButtonMapTest {

    @Test
    public void theThreeStandardButtonsMapToTheirQtFlags() {
        assertEquals("LWJGL 0 is left, and Qt's LeftButton is 1",
            MouseEvent.LEFT_BUTTON, QmlButtonMap.toQml(0));
        assertEquals("LWJGL 1 is right, and Qt's RightButton is 2",
            MouseEvent.RIGHT_BUTTON, QmlButtonMap.toQml(1));
        assertEquals("LWJGL 2 is middle, and Qt's MiddleButton is 4",
            MouseEvent.MIDDLE_BUTTON, QmlButtonMap.toQml(2));
    }

    /**
     * The mapping must not be the identity — that is the whole bug.
     *
     * <p>Stated separately because the assertions above would all hold if qml4j ever happened to
     * number its buttons from zero, and this is the property the calling code depends on.
     */
    @Test
    public void theMappingIsNotTheIdentityOnAnyStandardButton() {
        for (int index = 0; index <= 2; index++) {
            assertFalse("button " + index + " must be translated, not passed through; a raw index "
                    + "reaches qml4j as a different button entirely",
                index == QmlButtonMap.toQml(index));
        }
        assertTrue("left must not map to Qt.NoButton, which is what index 0 means to qml4j",
            QmlButtonMap.toQml(0) != QmlButtonMap.NO_BUTTON);
    }

    /** Distinct inputs must stay distinct, or two buttons would trigger the same UI path. */
    @Test
    public void theStandardButtonsStayDistinct() {
        int left = QmlButtonMap.toQml(0);
        int right = QmlButtonMap.toQml(1);
        int middle = QmlButtonMap.toQml(2);
        assertTrue("left and right must differ", left != right);
        assertTrue("right and middle must differ", right != middle);
        assertTrue("left and middle must differ", left != middle);
    }

    /**
     * An unmapped index degrades to {@code NoButton} rather than to a guess.
     *
     * <p>Qt continues in powers of two above the third button, but LWJGL2's ordering there is
     * platform-dependent, so a mapping would encode an unmeasured assumption. NoButton is a
     * value qml4j's dispatcher already handles.
     */
    @Test
    public void unknownAndNegativeIndicesBecomeNoButton() {
        assertEquals("a fourth button has no measured Qt equivalent",
            QmlButtonMap.NO_BUTTON, QmlButtonMap.toQml(3));
        assertEquals("a high index must not fall through to a real button",
            QmlButtonMap.NO_BUTTON, QmlButtonMap.toQml(15));
        // -1 is what Mouse.getEventButton() reports for a move or wheel-only event. Those do not
        // reach pointerDown/Up, but a mapper that returned a real button for it would be a trap.
        assertEquals("LWJGL's move/wheel sentinel must not become a button",
            QmlButtonMap.NO_BUTTON, QmlButtonMap.toQml(-1));
    }
}
