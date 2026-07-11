import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.Privilege;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToolRequirement;
import org.junit.Test;

/**
 * Pins the security posture of the structured-GUI tools so a future edit cannot
 * silently un-gate them. gui_snapshot is an observe-level read; the three action
 * tools drive real handlers with server-visible effects and must sit at R1 with
 * HIGH write-integrity and the dedicated SE_GUI_INTERACT privilege.
 */
public class GuiToolGateTest {

    @Test
    public void snapshotIsObserveLevelReadOnly() {
        SeToolRequirement tp = SeToolRequirement.forTool("gui_snapshot", true);
        assertEquals("gui_snapshot is an R2 observe read", Ring.R2, tp.requiredRing());
        assertEquals("gui_snapshot writes nothing", null, tp.writesResourceAt());
        assertEquals("gui_snapshot needs no privilege", null, tp.requiredPrivilege());
    }

    @Test
    public void actionToolsAreR1HighIntegrityGuiPrivilege() {
        for (String name : new String[] {"gui_click_element", "gui_type_text", "gui_press_key"}) {
            SeToolRequirement tp = SeToolRequirement.forTool(name, true);
            assertEquals(name + " is R1 (server-visible effect)", Ring.R1, tp.requiredRing());
            assertEquals(name + " writes at HIGH integrity",
                    IntegrityLevel.HIGH, tp.writesResourceAt());
            assertEquals(name + " requires SE_GUI_INTERACT",
                    Privilege.SE_GUI_INTERACT, tp.requiredPrivilege());
        }
    }
}
