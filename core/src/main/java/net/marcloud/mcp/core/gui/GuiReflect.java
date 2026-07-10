package net.marcloud.mcp.core.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Reads the verified vanilla MC 1.8.9 GUI fields off a live {@link GuiScreen}
 * and turns them into {@link GuiElement}s. These are GAME classes (not security
 * {@code ProtectedClasses}), so {@code setAccessible(true)} on their protected /
 * private fields is fine.
 *
 * <p><b>Fail-loud-but-degrade.</b> Every read tolerates a field being absent:
 * instead of throwing, the missing field is appended to the caller-supplied
 * {@code unreadable} sink so mapping drift (a field renamed by an obfuscation /
 * mappings change) is VISIBLE rather than silently swallowed. A single bad
 * element never aborts the whole snapshot.
 *
 * <p><b>Threading.</b> This reads live game state and MUST be called on the game
 * thread ({@code GuiSnapshotService} marshals it via {@code GameBridge}).
 *
 * <p><b>Known heuristic limits</b> (documented, not hidden):
 * <ul>
 *   <li>Text fields are discovered by scanning declared fields of type
 *       {@link GuiTextField} up the screen's class hierarchy. Fields held in
 *       collections, created lazily, or stored under an interface/supertype are
 *       missed.</li>
 *   <li>Slot item id uses {@link Item#getIdFromItem(Item)}; a modded item
 *       outside the registry may report {@code -1}.</li>
 * </ul>
 */
public final class GuiReflect {

    private GuiReflect() {
    }

    /** Result of an extraction pass: the elements plus any fields that couldn't be read. */
    public record Extraction(List<GuiElement> elements, List<String> unreadable) {
        public Extraction {
            elements = elements == null ? List.of() : List.copyOf(elements);
            unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
        }
    }

    /**
     * Extract every clickable element from {@code screen}.
     *
     * @param screen           the open GuiScreen (must not be null)
     * @param onlyInteractable when true, skip elements that are invisible or disabled
     */
    public static Extraction extract(GuiScreen screen, boolean onlyInteractable) {
        List<GuiElement> out = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();

        extractButtons(screen, onlyInteractable, out, unreadable);
        extractTextFields(screen, onlyInteractable, out, unreadable);
        if (screen instanceof GuiContainer gc) {
            extractSlots(gc, onlyInteractable, out, unreadable);
        }
        extractLabels(screen, onlyInteractable, out, unreadable);

        return new Extraction(out, unreadable);
    }

    // ===== BUTTONS =====

    @SuppressWarnings("unchecked")
    private static void extractButtons(GuiScreen screen, boolean onlyInteractable,
                                       List<GuiElement> out, List<String> unreadable) {
        Object listObj = readField(screen, GuiScreen.class, "buttonList", unreadable);
        if (!(listObj instanceof List<?> list)) {
            return;
        }
        List<GuiButton> buttons = (List<GuiButton>) list;
        for (int i = 0; i < buttons.size(); i++) {
            GuiButton b = buttons.get(i);
            if (b == null) {
                continue;
            }
            String id = "b" + i;
            // public fields: safe direct reads.
            int x = b.xPosition;
            int y = b.yPosition;
            int btnId = b.id;
            boolean enabled = b.enabled;
            boolean visible = b.visible;
            String label = b.displayString == null ? "" : b.displayString;
            // protected fields: reflect (fail-loud-but-degrade to 0).
            int w = intField(b, GuiButton.class, "width", id, unreadable);
            int h = intField(b, GuiButton.class, "height", id, unreadable);
            boolean hovered = boolField(b, GuiButton.class, "hovered", id, unreadable);

            if (onlyInteractable && (!visible || !enabled)) {
                continue;
            }

            Bounds bounds = new Bounds(x, y, w, h);
            Point click = new Point(x + w / 2, y + h / 2);
            State state = new State(enabled, visible, false, hovered);
            java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
            attrs.put("buttonId", btnId);
            out.add(new GuiElement(id, GuiElement.KIND_BUTTON, GuiElement.ROLE_PUSHBUTTON,
                    label, "", bounds, click, state, List.of("click"), attrs));
        }
    }

    // ===== SLOTS =====

    private static void extractSlots(GuiContainer gc, boolean onlyInteractable,
                                     List<GuiElement> out, List<String> unreadable) {
        // protected on GuiContainer: guiLeft/guiTop are the top-left of the GUI
        // in scaled-GUI space; slot display positions are relative to it.
        int guiLeft = intField(gc, GuiContainer.class, "guiLeft", "container", unreadable);
        int guiTop = intField(gc, GuiContainer.class, "guiTop", "container", unreadable);

        Container container = gc.inventorySlots; // public field
        if (container == null) {
            unreadable.add("container.inventorySlots(null)");
            return;
        }
        List<Slot> slots = container.inventorySlots; // public field
        int windowId = container.windowId;           // public field
        if (slots == null) {
            unreadable.add("Container.inventorySlots(null)");
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            // public fields on Slot.
            int slotNumber = slot.slotNumber;
            int sx = slot.xDisplayPosition;
            int sy = slot.yDisplayPosition;

            // getStack() returns NULL when empty in 1.8.9 (no isEmpty()).
            ItemStack stack = null;
            boolean hasStack;
            try {
                stack = slot.getStack();
                hasStack = stack != null;
            } catch (Throwable t) {
                unreadable.add("Slot[" + slotNumber + "].getStack: " + t.getClass().getSimpleName());
                hasStack = false;
            }

            String id = "s" + slotNumber;
            int x = guiLeft + sx;
            int y = guiTop + sy;
            // vanilla slot render box is 16x16; +8 centers the click-point.
            Bounds bounds = new Bounds(x, y, 16, 16);
            Point click = new Point(x + 8, y + 8);
            // Slots have no per-slot enabled/visible flags in 1.8.9; treat as
            // enabled+visible while a container screen is open.
            State state = new State(true, true, false, false);

            String itemName = "";
            java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
            attrs.put("slotNumber", slotNumber);
            attrs.put("windowId", windowId);
            attrs.put("hasStack", hasStack);
            if (hasStack) {
                int count = stack.stackSize;
                int itemId = -1;
                try {
                    Item item = stack.getItem();
                    if (item != null) {
                        itemId = Item.getIdFromItem(item);
                    }
                } catch (Throwable t) {
                    unreadable.add("Slot[" + slotNumber + "].getItem: " + t.getClass().getSimpleName());
                }
                try {
                    itemName = stack.getDisplayName();
                } catch (Throwable t) {
                    unreadable.add("Slot[" + slotNumber + "].getDisplayName: " + t.getClass().getSimpleName());
                }
                attrs.put("itemId", itemId);
                attrs.put("count", count);
            }

            out.add(new GuiElement(id, GuiElement.KIND_SLOT, GuiElement.ROLE_CELL,
                    itemName == null ? "" : itemName, "", bounds, click, state,
                    List.of("click"), attrs));
        }
    }

    // ===== TEXT FIELDS =====

    /**
     * Heuristic discovery: walk the concrete screen's class hierarchy and read
     * every DECLARED field whose type is assignable to {@link GuiTextField}.
     * Fields held in collections or created lazily are missed (documented).
     */
    private static void extractTextFields(GuiScreen screen, boolean onlyInteractable,
                                          List<GuiElement> out, List<String> unreadable) {
        int idx = 0;
        Class<?> c = screen.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!GuiTextField.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                GuiTextField tf;
                try {
                    f.setAccessible(true);
                    tf = (GuiTextField) f.get(screen);
                } catch (Throwable t) {
                    unreadable.add(c.getSimpleName() + "." + f.getName()
                            + "(textfield): " + t.getClass().getSimpleName());
                    continue;
                }
                if (tf == null) {
                    continue; // lazily created / not yet initialized
                }
                String id = "t" + idx++;
                // public fields on GuiTextField.
                int x = tf.xPosition;
                int y = tf.yPosition;
                // width/height are PRIVATE final: reflect them.
                int w = intField(tf, GuiTextField.class, "width", id, unreadable);
                int h = intField(tf, GuiTextField.class, "height", id, unreadable);
                boolean visible = tf.getVisible();
                boolean focused = tf.isFocused();
                String text = tf.getText();

                if (onlyInteractable && !visible) {
                    continue;
                }
                Bounds bounds = new Bounds(x, y, w, h);
                Point click = new Point(x + w / 2, y + h / 2);
                State state = new State(true, visible, focused, false);
                out.add(new GuiElement(id, GuiElement.KIND_TEXTFIELD, GuiElement.ROLE_EDIT,
                        f.getName(), text == null ? "" : text, bounds, click, state,
                        List.of("click", "setText"), java.util.Map.of()));
            }
            c = c.getSuperclass();
        }
    }

    // ===== LABELS =====

    @SuppressWarnings("unchecked")
    private static void extractLabels(GuiScreen screen, boolean onlyInteractable,
                                      List<GuiElement> out, List<String> unreadable) {
        Object listObj = readField(screen, GuiScreen.class, "labelList", unreadable);
        if (!(listObj instanceof List<?> list)) {
            return;
        }
        int idx = 0;
        for (Object labelObj : list) {
            if (labelObj == null) {
                continue;
            }
            String id = "l" + idx++;
            // GuiLabel fields are obfuscated (field_146162_g = x, field_146174_h = y,
            // field_146167_a = width, field_146161_f = height, visible = visible).
            int x = intField(labelObj, labelObj.getClass(), "field_146162_g", id, unreadable);
            int y = intField(labelObj, labelObj.getClass(), "field_146174_h", id, unreadable);
            int w = intField(labelObj, labelObj.getClass(), "field_146167_a", id, unreadable);
            int h = intField(labelObj, labelObj.getClass(), "field_146161_f", id, unreadable);
            boolean visible = boolField(labelObj, labelObj.getClass(), "visible", id, unreadable);

            if (onlyInteractable) {
                // labels are non-interactive text; skip entirely in interactable mode
                continue;
            }
            Bounds bounds = new Bounds(x, y, w, h);
            Point click = new Point(x + w / 2, y + h / 2);
            State state = new State(false, visible, false, false);
            out.add(new GuiElement(id, GuiElement.KIND_LABEL, GuiElement.ROLE_TEXT,
                    "", "", bounds, click, state, List.of(), java.util.Map.of()));
        }
    }

    // ===== REFLECTION PRIMITIVES (fail-loud-but-degrade) =====

    /**
     * Read a field by name starting at {@code declaringHint}, walking up to
     * superclasses. On any failure, records a note in {@code unreadable} and
     * returns null instead of throwing.
     */
    private static Object readField(Object target, Class<?> declaringHint,
                                    String name, List<String> unreadable) {
        Class<?> c = declaringHint;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                unreadable.add(declaringHint.getSimpleName() + "." + name
                        + ": " + t.getClass().getSimpleName());
                return null;
            }
        }
        unreadable.add(declaringHint.getSimpleName() + "." + name + "(absent)");
        return null;
    }

    private static int intField(Object target, Class<?> declaringHint,
                                String name, String elemId, List<String> unreadable) {
        Object v = readFieldFor(target, declaringHint, name, elemId, unreadable);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolField(Object target, Class<?> declaringHint,
                                     String name, String elemId, List<String> unreadable) {
        Object v = readFieldFor(target, declaringHint, name, elemId, unreadable);
        return v instanceof Boolean b && b;
    }

    /** Like {@link #readField} but tags failures with the owning element id. */
    private static Object readFieldFor(Object target, Class<?> declaringHint,
                                       String name, String elemId, List<String> unreadable) {
        Class<?> c = declaringHint;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                unreadable.add(elemId + ":" + name + ":" + t.getClass().getSimpleName());
                return null;
            }
        }
        unreadable.add(elemId + ":" + name + ":absent");
        return null;
    }
}
