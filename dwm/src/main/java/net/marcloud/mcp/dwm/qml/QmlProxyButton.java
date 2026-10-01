package net.marcloud.mcp.dwm.qml;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.audio.SoundHandler;

/**
 * An invisible {@link GuiButton} that stands in for one interactive QML element.
 *
 * <p><b>Why a vanilla button and not an element table.</b> {@code dwm} must not import
 * {@code net.marcloud.mcp.core} — {@code DwmEntryTest} enforces that by reflection — so the
 * element list {@code gui_snapshot} reads can only come from what vanilla already reflects:
 * {@code GuiScreen.buttonList} and {@code labelList}. Registering real {@code GuiButton}s is
 * therefore not one option among several, it is the only shape that does not break the module
 * boundary. It is also the cheapest: the element table becomes the button list itself, with
 * nothing to keep in sync.
 *
 * <p><b>Why it draws nothing.</b> The scene already paints itself; a second vanilla button
 * rendering on top would double-draw every control. {@link #drawButton} is an empty body, which
 * makes these invisible hit proxies — the pattern KateClient uses
 * ({@code ui/components/buttons/AbstractButton.java:20-22}) for exactly the same reason, and
 * LiquidLunar for its {@code SwitchButton}s.
 *
 * <p><b>Why the click is not re-derived from coordinates.</b> A click arrives at
 * {@code mousePressed} after vanilla has already resolved <em>which</em> button it hit, so the
 * proxy names its own element instead of converting a point back. That removes the one
 * conversion the design would otherwise need (scaled-GUI back to QML logical) and with it the
 * pixel error that conversion accumulates at non-integer DPI scales.
 *
 * <p><b>Note on signatures.</b> These are 1.8.9's, and they differ from later versions in ways
 * that matter: {@code drawButton} takes the {@link Minecraft} instance, {@code playPressSound}
 * takes a {@link SoundHandler}, and there is no {@code onClick} at all — the click surfaces
 * through {@code mousePressed} being reached, which vanilla's {@code GuiScreen.mouseClicked}
 * then follows with {@code actionPerformed}.
 */
public final class QmlProxyButton extends GuiButton {

    /** Stable id of the QML element this button stands for; what the bridge dispatches on. */
    private final String elementId;

    /** The QML element this stands for; activation emits its {@code clicked} signal. */
    private final io.github.timer_err.qml4j.render.items.core.MouseArea area;

    public QmlProxyButton(String elementId, String label, int x, int y, int w, int h,
                          io.github.timer_err.qml4j.render.items.core.MouseArea area) {
        // A real id, not -1: GuiButton's id is what a screen's own actionPerformed switches on,
        // and a sentinel would collide with a real button's id if this screen ever hosted one.
        super(0, x, y, w, h, label);
        this.elementId = elementId;
        this.area = area;
    }

    public String elementId() {
        return elementId;
    }

    /**
     * The QML element behind this button, for the bridge to emit {@code clicked} on.
     *
     * <p>Exposed rather than captured in the callback so the dispatch stays a QML signal
     * fire-and-forget with no arguments — the same call a human click makes. A callback that
     * synthesised pointer coordinates instead would be a second path into the scene, and a
     * second path is a second thing that can drift from the first.
     */
    public io.github.timer_err.qml4j.render.items.core.MouseArea area() {
        return area;
    }

    /**
     * Nothing is drawn.
     *
     * <p>Not a suppression but a statement: the scene owns every pixel of this screen, and a
     * vanilla button drawn over it would be a second, differently-styled copy of the same
     * control. {@code drawButton} is {@code public} and non-final in vanilla precisely so a
     * screen can own its own rendering.
     */
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        // Intentionally empty: an invisible hit proxy over a self-painted scene.
    }

    @Override
    public void playPressSound(SoundHandler soundHandlerIn) {
        // The scene plays its own sounds; a vanilla click on top of it would double them.
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        // 1.8.9's signature has no button parameter: GuiScreen.mouseClicked has already
        // established this is a left click before it reaches a button.
        //
        // `super` is the enabled/visible/hover authority, and calling it first means those
        // rules stay in ONE place -- so a disabled element cannot be activated by a human or by
        // a tool, and no future edit here can accidentally make that true.
        if (!super.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }
        // The dispatch is the QML signal a human click emits, with no arguments, so the scene
        // cannot tell an agent's click from a person's.
        if (area != null) {
            area.clicked.emit();
        }
        // False: consumed here rather than also being offered to actionPerformed, so a screen
        // that forgets to switch on this id cannot activate it twice.
        return false;
    }
}
