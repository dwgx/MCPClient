package net.marcloud.mcp.core.io.transport;

import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.compat.patches.EscMenu;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;

/**
 * {@code open_pause_menu} — the one door the ESC tree was missing.
 *
 * <p><b>Why a tool and not a fix to {@code press_key_binding}.</b> ESC never reaches a
 * {@code KeyBinding}. {@code Minecraft.runTick} handles it in the raw LWJGL event loop
 * ({@code Minecraft.java:1939-1944}), on the {@code currentScreen == null} branch, by calling
 * {@link net.minecraft.client.Minecraft#displayInGameMenu()}. Nothing that reproduces a
 * {@code KeyBinding} dispatch can deliver it, so the seam exposes that method instead — see
 * {@link EscMenu}, which calls the same one ESC does.
 *
 * <p><b>One tool, not a tree-walker.</b> Everything under the pause menu is an ordinary
 * {@code buttonList} entry, which {@code gui_snapshot} already publishes as an addressable
 * element and {@code gui_click_element} already drives through the real
 * {@code GuiScreen.mouseClicked}. A {@code click("statistics")}-style verb would be a SECOND route
 * into the same screens, and two routes are how "refuse to replace another screen" and "toggle,
 * do not stack" drift into two different UIs. So this tool opens the root; {@code gui_snapshot}
 * reads it; {@code gui_click_element} walks it, one real click at a time, exactly as a person
 * does.
 *
 * <p><b>The tree, and where each door is.</b> Everything below is from the frozen 1.8.9 client
 * source, and the button ids are the ones the vanilla code compares against — the agent should
 * still read the live ids off {@code gui_snapshot} rather than trust this table, because the
 * table cannot see {@code button.enabled}.
 *
 * <pre>
 * ESC -> Minecraft.displayInGameMenu()            (Minecraft.java:1478)
 *   GuiIngameMenu
 *     4  Back to Game   -> displayGuiScreen(null) + setIngameFocus()
 *     0  Options...     -> new GuiOptions(this, mc.gameSettings)
 *     7  Share to LAN   -> new GuiShareToLan(this)
 *     5  Achievements   -> new GuiAchievements(this, mc.thePlayer.getStatFileWriter())
 *     6  Statistics     -> new GuiStats(this, mc.thePlayer.getStatFileWriter())
 *     1  Save and Quit  -> loadWorld(null) + GuiMainMenu / GuiMultiplayer
 *   GuiOptions
 *     110 Skin Customisation -> new GuiCustomizeSkin(this)
 *     101 Video...           -> new GuiVideoSettings(this, settings)
 *     100 Controls...        -> new GuiControls(this, settings)
 *     102 Language...        -> new GuiLanguage(this, settings, mc.getLanguageManager())
 *     103 Chat Settings...   -> new ScreenChatOptions(this, settings)
 *     104 Snooper Settings   -> new GuiSnooper(this, settings)
 *     105 Resource Packs...  -> new GuiScreenResourcePacks(this)
 *     106 Sounds...          -> new GuiScreenOptionsSounds(this, settings)
 *     107 Streaming...       -> new GuiStreamOptions(this, settings) IF mc.getTwitchStream()
 *                                .func_152936_l() && .func_152928_D(), else GuiStreamUnavailable
 *     200 Done               -> displayGuiScreen(parentScreen)
 * </pre>
 *
 * <p><b>Statistics and Achievements: the door, and why it is the human one.</b> From the
 * player's inventory panel they are unreachable in appearance only —
 * {@code GuiInventory.initGui} clears {@code buttonList} and adds nothing, so the
 * {@code actionPerformed} ids 0/1 on those two screens are dead code from that direction. The
 * door that actually exists is the pause menu's own Achievements/Statistics button, and that is
 * the door this tool opens and {@code gui_click_element} then clicks: the same button, with the
 * same vanilla id, on the same screen a person clicks. No second route was needed.
 *
 * <p><b>A correction worth keeping, because the obvious reading is wrong.</b> The singleplayer
 * gate in {@code GuiIngameMenu.initGui} lands on <b>Share to LAN</b>, not on Statistics or
 * Achievements: {@code guibutton} is declared BEFORE the Share-to-LAN line, so it is button 7,
 * and the trailing {@code guibutton.enabled = isSingleplayer() && !getIntegratedServer()
 * .getPublic()} gates button 7 alone. Buttons 5 and 6 are enabled unconditionally. An agent on a
 * multiplayer server CAN open Statistics and Achievements; it CANNOT open Share to LAN. Read
 * {@code enabled} off {@code gui_snapshot} rather than assuming either way.
 *
 * <p>Separate from {@link ToolRegistry} for the same reason {@link EnchantTools} is: that file
 * is under concurrent edit by another agent.
 */
public final class EscPanelTools {

    /** Same budget {@code gui_snapshot} and the rest of the GUI surface uses. */
    private static final long TIMEOUT_MS = 3000L;

    public void registerAll(IoManager registry) {
        SyncToolSpecification spec = openPauseMenu();
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), Ring.R3));
    }

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult err(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    SyncToolSpecification openPauseMenu() {
        Tool tool = Tool.builder()
                .name("open_pause_menu")
                .title("Open the pause menu (the ESC tree root)")
                .description("[requires: in a world, no other screen open] Press ESC: opens the "
                        + "pause menu, the root of the entire options tree. This is the ONLY way "
                        + "an agent can reach it -- ESC is a raw LWJGL event handled in "
                        + "Minecraft.runTick's keyboard loop, not a KeyBinding, so "
                        + "press_key_binding answers bindingClaimed=false and does nothing; and "
                        + "gui_press_key drives GuiScreen.keyTyped, which vanilla only reaches "
                        + "when a screen is ALREADY open. This tool calls "
                        + "Minecraft.displayInGameMenu() -- the same method the ESC key calls -- so "
                        + "the guard, the screen and the singleplayer sound pause are vanilla's "
                        + "own, shared with the player.\n"
                        + "Then walk it the human way: gui_snapshot reads the buttons, "
                        + "gui_click_element clicks one by element id. Nothing below needs another "
                        + "door, because every sub-page is an ordinary button.\n"
                        + "THE TREE (vanilla button ids -- still read the live ids and the enabled "
                        + "flag off gui_snapshot, this table cannot see them):\n"
                        + "  Options... (0)          -> Video (101) | Controls (100) | Language (102)"
                        + " | Chat (103) | Snooper (104) | Resource Packs (105) | Sounds (106)"
                        + " | Skin (110) | Done (200)\n"
                        + "  Options (107, Streaming...) opens only when mc.getTwitchStream() "
                        + "reports a broadcast is live; otherwise vanilla shows "
                        + "GuiStreamUnavailable.\n"
                        + "  Statistics (6) and Achievements (5) are always enabled -- they are "
                        + "reachable from the pause menu in singleplayer AND on a multiplayer "
                        + "server. Share to LAN (7) is the one vanilla gates: "
                        + "GuiIngameMenu.initGui enables it only when isSingleplayer() && "
                        + "!integratedServer.getPublic(). Read 'enabled' off gui_snapshot rather "
                        + "than assuming.\n"
                        + "  Save and Quit to Title (1).\n"
                        + "Close it again with gui_press_key 'Escape', which drives the real "
                        + "GuiScreen.keyTyped.")
                .annotations(ToolAnnotations.builder()
                        .title("Open the pause menu (the ESC tree root)")
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            final EscMenu.Outcome outcome;
            try {
                outcome = GameBridge.onGameThread(EscMenu::openPauseMenu, TIMEOUT_MS);
            } catch (Exception e) {
                return err("open_pause_menu could not run on the game thread: " + e);
            }
            if (outcome == null) {
                return err("open_pause_menu produced no outcome; nothing is claimed about what is "
                        + "on screen");
            }
            if (!outcome.opened()) {
                return err("the pause menu is not open: " + outcome.detail());
            }
            return ok("pause menu open (currentScreen=" + outcome.screen()
                    + ") -- the same method the ESC key calls. Read it with gui_snapshot and walk "
                    + "it with gui_click_element; close it with gui_press_key 'Escape'.");
        });
    }
}
