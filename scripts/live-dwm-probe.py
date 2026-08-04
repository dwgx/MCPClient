#!/usr/bin/env python3
"""Drive the DWM UI inside a running client and assert what it actually did.

This is the automation counterpart to the headless ITs. Those run against a bare GLFW window;
this one talks to a REAL game over MCP and checks the things only a real frame can show. Three
bugs found by hand on 2026-07-27 were invisible to every headless assertion:

  * a leftover ARRAY_BUFFER binding turned MC's client-side vertex pointer into a buffer offset
    and SIGSEGV'd the world draw,
  * restoring a vertex array object aborted the JVM, because GL 3.0 entry points do not exist in
    Apple's 2.1 compatibility profile,
  * the composite was queued and never flushed, so the scene painted perfectly into the offscreen
    layer and nothing reached the screen — while every state field reported health.

The last one is why this probe reads PIXELS and asserts the process is still alive after each
step, rather than trusting a status field.

Why it drives dwm through eval_java rather than the gui_* tools: gui_snapshot enumerates
vanilla's buttonList, and a QML scene never populates it, so gui_click_element cannot see a dwm
control. Input therefore goes in through dwm's own UiInput SPI, which is also the path the game
uses, so the probe exercises production code rather than a test-only shim.

Usage:
    python3 scripts/live-dwm-probe.py [--port 25599] [--keep]

The socket client, the eval_java wrapper and the record/report harness live in
scripts/mcp_probe.py, shared with the other live probes -- this probe's own copy of the reply
framing is exactly what let the truncation bug survive a session here after nav-astar-probe.py
had already fixed it. Exit codes follow smoke-live-gl.sh's convention:
    0 PASS · 1 FAIL (an assertion failed) · 2 TIMEOUT (no MCP within the deadline) · 3 SETUP
"""

import argparse
import os
import socket
import subprocess
import sys
import time

# scripts/ is not on sys.path when this file is loaded BY PATH, which test_probe_framing.py and
# the ad-hoc probes noted in .gitignore both do (the hyphen rules out a plain import). Running it
# directly already puts scripts/ there.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_probe import (  # noqa: E402 - the sys.path line above has to run first
    EXIT_FAIL, EXIT_SETUP, EXIT_TIMEOUT, Mcp, record, report,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The scene the probe drives, and the four pages it expects the rail to reach.
PAGES = [
    "pages/PageHome.qml",
    "pages/PageKernel.qml",
    "pages/PageChips.qml",
    "pages/PageSettings.qml",
]

# The rail rows that reach them, by the names NavigationView gives its items. Paired with PAGES
# by index; their POSITIONS are read from the live scene rather than listed (see row_centre).
ROWS = ["navHome", "navKernel", "navChips", "navSettings"]

# --- the snippets ---------------------------------------------------------------------------
# Each reaches dwm reflectively, because core must not link the dwm module and this probe must
# not require it to.
#
# The surface's own state is read through its package-private accessors (view, uiScale, isInert)
# rather than its fields, so this probe survives the compositor/input-router split that field
# names would have pinned. It must be getDeclaredMethod: getMethod only finds PUBLIC methods and
# would raise NoSuchMethodException on all three.

SURFACE_PREAMBLE = """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        Object screen = mc.currentScreen;
        if (screen == null || !screen.getClass().getName().startsWith("net.marcloud.mcp.dwm."))
          return "NOT-DWM " + (screen == null ? "null" : screen.getClass().getName());
        java.lang.reflect.Field sf = screen.getClass().getDeclaredField("surface");
        sf.setAccessible(true);
        Object surf = sf.get(screen);
"""


def load_world(mcp):
    return mcp.java("LoadWorld", """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.theWorld != null) return "already-in-world";
        java.util.List<String> names = new java.util.ArrayList<>();
        for (net.minecraft.world.storage.SaveFormatComparator c : mc.getSaveLoader().getSaveList())
          names.add(c.getFileName());
        if (names.isEmpty()) return "NO-SAVES";
        mc.launchIntegratedServer(names.get(0), names.get(0), null);
        return "loading " + names.get(0);
""")


def open_ui(mcp):
    return mcp.java("OpenUi", """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        Object s = net.marcloud.mcp.dwm.DwmEntry.createScreen();
        if (s == null) return "CREATE-NULL";
        mc.displayGuiScreen((net.minecraft.client.gui.GuiScreen) s);
        return "opened " + s.getClass().getName() + " inWorld=" + (mc.theWorld != null);
""")


def ui_health(mcp):
    return mcp.java("Health", SURFACE_PREAMBLE + """
        java.lang.reflect.Method isOpen = surf.getClass().getMethod("isOpen");
        java.lang.reflect.Method lastErr = surf.getClass().getMethod("lastError");
        java.lang.reflect.Method inert = surf.getClass().getDeclaredMethod("isInert");
        inert.setAccessible(true);
        return "isOpen=" + isOpen.invoke(surf) + " inert=" + inert.invoke(surf)
             + " lastError=" + lastErr.invoke(surf)
             + " fb=" + mc.getFramebuffer().framebufferObject;
""")


def target_pixels(mcp):
    """Sample MC's OWN framebuffer, which is the only witness that the composite arrived.

    Read with glReadPixels rather than through Skia: Skia's queue was exactly what the flush bug
    was dropping, so asking Skia would have confirmed the bug as healthy.
    """
    return mcp.java("TargetPix", SURFACE_PREAMBLE + """
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        int fb = mc.getFramebuffer().framebufferObject;
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fb);
        // Shell.qml places the window at logical 20,20; sample well inside its title bar and body.
        int[][] pts = {{60, 60}, {200, 200}, {300, 120}};
        StringBuilder sb = new StringBuilder("scale=" + scale);
        int lit = 0;
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
        int h = mc.getFramebuffer().framebufferHeight;
        for (int[] p : pts) {
          int x = Math.round(p[0] * scale);
          // glReadPixels is bottom-left origin; the scene is authored top-left.
          int y = h - Math.round(p[1] * scale) - 1;
          px.clear();
          org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA,
              org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
          int r = px.get(0) & 0xFF, g = px.get(1) & 0xFF, b = px.get(2) & 0xFF;
          sb.append(String.format(" (%d,%d)=%02x%02x%02x", p[0], p[1], r, g, b));
          if (r + g + b > 24) lit++;
        }
        return sb.append(" lit=").append(lit).toString();
""")


def click(mcp, x, y):
    """A real press/release pair through dwm's UiInput, in framebuffer pixels."""
    return mcp.java("Click", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method down = surf.getClass().getMethod("pointerDown", float.class, float.class, int.class);
        java.lang.reflect.Method up = surf.getClass().getMethod("pointerUp", float.class, float.class, int.class);
        java.lang.reflect.Method move = surf.getClass().getMethod("pointerMove", float.class, float.class);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        // The SPI takes framebuffer pixels; the caller thinks in the scene's logical units.
        float px = {x}f * scale, py = {y}f * scale;
        move.invoke(surf, px, py);
        Object a = down.invoke(surf, px, py, 0);
        Object b = up.invoke(surf, px, py, 0);
        return "down=" + a + " up=" + b;
""")


def row_centre(mcp, object_name):
    """The absolute centre of a named rail row, in the scene's logical units.

    Asked of the live scene rather than hardcoded. A first version of this probe carried the
    numbers inline and was wrong by exactly one row — every click landed on the destination below
    the one it named, and the probe reported a navigation failure that was its own. Geometry that
    the layout owns has to be read from the layout, or the harness invents its own bugs.
    """
    return mcp.java("RowCentre" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object it = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (it == null) return "NO-ROW";
        // Absolute position: an item's x/y are relative to its parent, so walk the chain, less any
        // Flickable's scroll offset -- see item_box for why that subtraction is load-bearing.
        float ax = 0, ay = 0;
        Object cur = it;
        while (cur != null) {{
          Object xp = cur.getClass().getField("x").get(cur);
          Object yp = cur.getClass().getField("y").get(cur);
          ax += ((Number) xp.getClass().getMethod("peekFloat").invoke(xp)).floatValue();
          ay += ((Number) yp.getClass().getMethod("peekFloat").invoke(yp)).floatValue();
          if (cur.getClass().getName().endsWith("core.Flickable")) {{
            Object cx = cur.getClass().getField("contentX").get(cur);
            Object cy = cur.getClass().getField("contentY").get(cur);
            ax -= ((Number) cx.getClass().getMethod("peekFloat").invoke(cx)).floatValue();
            ay -= ((Number) cy.getClass().getMethod("peekFloat").invoke(cy)).floatValue();
          }}
          Object pp = cur.getClass().getField("parent").get(cur);
          cur = pp.getClass().getMethod("peek").invoke(pp);
        }}
        Object wp = it.getClass().getField("width").get(it);
        Object hp = it.getClass().getField("height").get(it);
        float w = ((Number) wp.getClass().getMethod("peekFloat").invoke(wp)).floatValue();
        float h = ((Number) hp.getClass().getMethod("peekFloat").invoke(hp)).floatValue();
        return Math.round(ax + w / 2) + "," + Math.round(ay + h / 2);
""")


def wheel(mcp, x, y, notches):
    """One wheel notch through dwm's own SPI, at a point in LOGICAL units.

    The point has to land inside the Flickable or the notch goes nowhere: QmlUiSurface.wheel looks up
    the innermost Flickable under the cursor and falls back to qml4j's own dispatch when there is
    none. Aiming at the nav rail instead of the page therefore leaves targetY untouched, which reads
    as "scrolling is broken" rather than "you missed".
    """
    return mcp.java("Wheel", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = ((Number) usm.invoke(surf)).floatValue();
        java.lang.reflect.Method w = surf.getClass().getMethod(
            "wheel", float.class, float.class, float.class, float.class);
        // Notches are not distances and do not scale; the position is spatial and does.
        return "wheel=" + w.invoke(surf, {x}f * scale, {y}f * scale, 0.0F, {notches}.0F);
""")


def check_card_plate_on_screen(mcp):
    """Assert a SettingsCard's plate is brighter than the page behind it, in MC's framebuffer.

    Nothing else covers this, and its absence let a GPU-level bug survive four investigations.
    SettingsCardLiveIT samples the offscreen LAYER of a test fixture (dwm/CardGallery.qml), so it
    passes while the shipped page shows nothing, and CompositeReachesTargetLiveIT is satisfied by any
    single non-magenta pixel -- a page with every card plate missing stays green. The bug was MC's
    GL_ALPHA_TEST (GREATER 0.1) discarding every Skia fragment at alpha <= 25, which is where
    CardBackgroundFillColorDefault's alpha 13 lives; the card's TEXT drew fine at alpha 255/197,
    and that split is what made it look like a compositing fault for so long.

    Three measurement traps, each of which produced a false reading while I was finding that bug:

      * scroll_into_view only guarantees an item's first 40px are visible -- its contract, sized for
        a 40px nav row. A card is 68 tall, so it can honestly report "in-view" with the bottom half
        below the fold, and a midpoint sample then reads the page instead of the plate.
      * the clip that matters is the FLICKABLE's viewport (measured: logical y 112..440), not the
        window (480 tall). Checking against the window called a card at y=420 safe when the 328px
        viewport had already cut it off.
      * a wheel notch only moves targetY; contentY converges over later frames. Sampling straight
        after a scroll catches the content mid-flight.

    So: scroll, let the game render, re-read the box, and only then read pixels. Frames are NOT
    pumped by hand here -- doing that and then calling GL in the same eval SIGSEGVs in libGL, because
    GlStateGuard has restored MC's state by the time frame() returns. target_pixels() is safe for
    exactly the same reason: it only reads.

    The reference point is the 20px group gap ABOVE the card rather than a constant, so this asserts
    CONTRAST and cannot be broken by a theme change.
    """
    if "NO-ITEM" in scroll_into_view(mcp, "fxMaster"):
        record("the animation card can be scrolled into view", False, "fxMaster not found")
        return
    # Then nudge until the WHOLE card is inside the viewport, re-measuring between notches. Where the
    # card lands after scroll_into_view depends on where the preceding checks left the page -- one run
    # ended at y=372..440 (just inside) and another at y=388..456 (clipped), so this cannot be left
    # to chance. One notch per call, with a real pause, because a notch only moves targetY and the
    # game's own frames are what carry contentY to it.
    box = ""
    for _ in range(8):
        time.sleep(0.5)
        box = item_box(mcp, "fxMaster")
        if "," not in box:
            record("the card's box is readable after scrolling", False, box)
            return
        bx, by, bw, bh = [int(float(v)) for v in box.split(",")]
        # Measured: the Flickable's viewport is logical y 112..440. A card whose bottom edge is past
        # that is partly clipped, and sampling its middle would read the page, not the plate.
        if by >= 112 and by + bh <= 440:
            break
        wheel(mcp, 460, 300, -1 if by + bh > 440 else 1)
    else:
        record("the card is fully inside the Flickable viewport before sampling", False,
               f"card y={by}..{by + bh} against viewport 112..440 after 8 notches")
        return
    # x=460 sits clear of the icon column, the text block (ends about 307) and the toggle (from 508).
    result = mcp.java("CardPlateOnScreen", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = ((Number) usm.invoke(surf)).floatValue();
        int fh = mc.getFramebuffer().framebufferHeight;
        // MC's own framebuffer via glReadPixels, never through Skia: asking Skia would be asking the
        // queue whose missing flush was an earlier bug of this exact shape whether it is well.
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
            mc.getFramebuffer().framebufferObject);
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
        int[] ys = {{{by + bh // 2}, {by - 8}}};
        int[] lum = new int[2];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {{
            int dx = Math.round(460 * scale);
            // glReadPixels is bottom-left origin; the scene is authored top-left.
            int dy = fh - Math.round(ys[i] * scale) - 1;
            px.clear();
            org.lwjgl.opengl.GL11.glReadPixels(dx, dy, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
            int r = px.get(0) & 0xFF, g = px.get(1) & 0xFF, b = px.get(2) & 0xFF;
            lum[i] = (r * 30 + g * 59 + b * 11) / 100;
            sb.append(String.format("(460,%d)=%02x%02x%02x ", ys[i], r, g, b));
        }}
        return sb.append("delta=").append(lum[0] - lum[1]).toString();
""")
    if not result.startswith("(460,"):
        record("a card's plate is brighter than the page behind it, on the SCREEN", False,
               result[:200])
        return
    delta = int(result.rsplit("delta=", 1)[1].strip())
    # Measured +7, and it takes TWO layers to get there: FluentElevation's cardStroke #19000000 --
    # black at alpha 25 -- darkens the 2a panel to 26 first, then the #0dffffff face lifts it to 31.
    # The face alone over the panel would be +11, so a bar set from that single-layer arithmetic
    # could never be met. 4 rejects a plate that is drawn and practically invisible.
    record("a card's plate is brighter than the page behind it, on the SCREEN",
           delta >= 4, result.strip()[:200])


def item_box(mcp, object_name):
    """A named item's absolute box in logical units, as "x,y,w,h", or a diagnostic string.

    Same parent-chain walk as row_centre, but returning the whole rectangle: a drag needs to aim at
    a fraction of a control's width, which a centre point cannot express.
    """
    return mcp.java("Box" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object it = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (it == null) return "NO-ITEM";
        float ax = 0, ay = 0;
        Object cur = it;
        while (cur != null) {{
          Object xp = cur.getClass().getField("x").get(cur);
          Object yp = cur.getClass().getField("y").get(cur);
          ax += ((Number) xp.getClass().getMethod("peekFloat").invoke(xp)).floatValue();
          ay += ((Number) yp.getClass().getMethod("peekFloat").invoke(yp)).floatValue();
          // A Flickable shifts its contents by contentX/contentY, and qml4j's hit test SUBTRACTS
          // that on the way down. Walking x/y alone therefore reports where an item would be if
          // the page were unscrolled -- correct before the page could scroll, and off by the
          // scroll offset afterwards. Measured: with the page scrolled 96px the check box's
          // reported box was 96px below where a click actually reached it, and every control
          // inside the Flickable stopped responding.
          if (cur.getClass().getName().endsWith("core.Flickable")) {{
            Object cx = cur.getClass().getField("contentX").get(cur);
            Object cy = cur.getClass().getField("contentY").get(cur);
            ax -= ((Number) cx.getClass().getMethod("peekFloat").invoke(cx)).floatValue();
            ay -= ((Number) cy.getClass().getMethod("peekFloat").invoke(cy)).floatValue();
          }}
          Object pp = cur.getClass().getField("parent").get(cur);
          cur = pp.getClass().getMethod("peek").invoke(pp);
        }}
        Object wp = it.getClass().getField("width").get(it);
        Object hp = it.getClass().getField("height").get(it);
        return Math.round(ax) + "," + Math.round(ay) + ","
             + Math.round(((Number) wp.getClass().getMethod("peekFloat").invoke(wp)).floatValue())
             + "," + Math.round(((Number) hp.getClass().getMethod("peekFloat").invoke(hp)).floatValue());
""")


def drag(mcp, from_x, y, to_x):
    """Press, move in steps, release — a real drag, which a click can never stand in for.

    The intermediate moves are the point: qml4j only delivers positionChanged to a CAPTURED area, so
    a press-then-release pair exercises the click path and leaves the drag path untested. Stepping
    also proves the capture survives motion rather than being re-hit-tested each time.
    """
    return mcp.java("Drag", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method down = surf.getClass().getMethod("pointerDown", float.class, float.class, int.class);
        java.lang.reflect.Method up = surf.getClass().getMethod("pointerUp", float.class, float.class, int.class);
        java.lang.reflect.Method move = surf.getClass().getMethod("pointerMove", float.class, float.class);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        float y = {y}f * scale;
        float x0 = {from_x}f * scale, x1 = {to_x}f * scale;
        move.invoke(surf, x0, y);
        down.invoke(surf, x0, y, 0);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {{
          move.invoke(surf, x0 + (x1 - x0) * i / steps, y);
        }}
        up.invoke(surf, x1, y, 0);
        return "dragged";
""")


def item_property(mcp, object_name, prop):
    """Read a named item's QML property, so an assertion can be about state rather than about
    whether a call returned."""
    return mcp.java("Prop" + object_name + prop, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object it = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (it == null) return "NO-ITEM";
        Object p = it.getClass().getField("{prop}").get(it);
        return String.valueOf(p.getClass().getMethod("peek").invoke(p));
""")


def hover(mcp, x, y):
    """Move the pointer without pressing, for hover-state assertions."""
    return mcp.java("Hover", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method move = surf.getClass().getMethod("pointerMove", float.class, float.class);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        move.invoke(surf, {x}f * scale, {y}f * scale);
        return "moved";
""")


def current_page(mcp):
    return mcp.java("CurPage", SURFACE_PREAMBLE + """
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object nav = view.getClass().getMethod("findByObjectName", String.class).invoke(view, "nav");
        if (nav == null) return "NO-NAV";
        java.lang.reflect.Field cp = nav.getClass().getField("currentPage");
        Object prop = cp.get(nav);
        Object val = prop.getClass().getMethod("peek").invoke(prop);
        return String.valueOf(val);
""")


def type_text(mcp, text):
    """Type through the same key path the game uses, one character at a time."""
    return mcp.java("TypeText", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method key = surf.getClass().getMethod("key", int.class, String.class, boolean.class, boolean.class);
        String s = "{text}";
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
          acc.append(key.invoke(surf, 0, String.valueOf(s.charAt(i)), false, false)).append(",");
        return "consumed=" + acc;
""")


def focus_and_read_field(mcp, text):
    """Focus the settings page's text box, type, and read the field back.

    Reading the field is the assertion. A key path that is consumed but drops the character looks
    identical from the outside — which is exactly the bug that made all text input dead until it
    was measured.
    """
    return mcp.java("FieldRoundTrip", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object root = view.getClass().getMethod("root").invoke(view);
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        Object field = null;
        while (!queue.isEmpty()) {{
          Object n = queue.poll();
          if (n.getClass().getName().endsWith("items.input.TextField")) {{ field = n; break; }}
          java.lang.reflect.Field ch = n.getClass().getField("children");
          for (Object c : (java.util.List<?>) ch.get(n)) queue.add(c);
        }}
        if (field == null) return "NO-TEXTFIELD-ON-PAGE";
        view.getClass().getMethod("setFocus", Class.forName("io.github.timer_err.qml4j.render.items.core.Item"))
            .invoke(view, field);
        java.lang.reflect.Method key = surf.getClass().getMethod("key", int.class, String.class, boolean.class, boolean.class);
        String s = "{text}";
        for (int i = 0; i < s.length(); i++) key.invoke(surf, 0, String.valueOf(s.charAt(i)), false, false);
        Object got = field.getClass().getMethod("text").invoke(field);
        return "typed=" + s + " read=" + got;
""")


def scroll_into_view(mcp, object_name):
    """Scroll the settings page until the named item sits inside the viewport.

    Needed because the page became a Flickable: content below the fold is genuinely unreachable
    until scrolled to, so a click at its coordinates lands on nothing. That is correct behaviour
    and it is exactly what a probe has to account for -- the alternative reading, "the control is
    broken", is what the first run of this looked like.

    Scrolls through dwm's own wheel SPI, the same path MC feeds from a real wheel event, rather
    than writing contentY directly: setting the property would prove the layout can move and say
    nothing about whether wheel input reaches the Flickable.
    """
    return mcp.java("ScrollTo" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        java.lang.reflect.Method wheel = surf.getClass().getMethod(
            "wheel", float.class, float.class, float.class, float.class);

        Object target = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (target == null) return "NO-ITEM";

        // Find the enclosing Flickable, and the target's offset within it.
        Object flick = null;
        Object cur = target;
        float oy = 0;
        while (cur != null) {{
            if (cur.getClass().getName().endsWith("core.Flickable")) {{ flick = cur; break; }}
            Object yp = cur.getClass().getField("y").get(cur);
            oy += ((Number) yp.getClass().getMethod("peekFloat").invoke(yp)).floatValue();
            Object pp = cur.getClass().getField("parent").get(cur);
            cur = pp.getClass().getMethod("peek").invoke(pp);
        }}
        if (flick == null) return "NO-FLICKABLE";

        java.lang.reflect.Field hf = flick.getClass().getField("height");
        float viewH = ((Number) hf.get(flick).getClass().getMethod("peekFloat")
            .invoke(hf.get(flick))).floatValue();
        java.lang.reflect.Field cyf = flick.getClass().getField("contentY");
        Object cy = cyf.get(flick);

        // Reads the SCROLL TARGET, not the current offset. Scrolling is now a smooth animation:
        // a wheel notch moves targetY and the animator eases contentY toward it over the following
        // frames. This whole helper runs inside ONE eval on the game thread, so no frame can be
        // rendered while it loops -- reading contentY here would see the pre-animation value every
        // time and the loop would keep scrolling until it ran out of iterations, overshooting
        // wildly. Measured: it reported NOT-IN-VIEW rel=365 against a 328px viewport.
        //
        // The target is the honest thing to test anyway: it is where the page WILL be, and the
        // frames that carry it there are the animator's business, not this helper's.
        java.lang.reflect.Field tyf = flick.getClass().getDeclaredField("targetY");
        tyf.setAccessible(true);

        // One notch per step, in whichever direction the target lies, up to a bound. Bidirectional
        // because the probe visits the second group and then comes back to the first; a
        // scroll-down-only helper would report the first group as unreachable on the way back.
        // Bounded so a target that can never come into view fails rather than spinning.
        for (int i = 0; i < 40; i++) {{
            float top = ((Number) tyf.get(flick)).floatValue();
            float rel = oy - top;
            if (rel >= 0 && rel + 40 <= viewH) {{
                return "in-view rel=" + Math.round(rel) + " targetY=" + Math.round(top);
            }}
            // A real wheel reports +y for up, so scrolling DOWN to reach lower content is -1.
            float dy = rel < 0 ? 1.0F : -1.0F;
            wheel.invoke(surf, 200.0F * scale, 200.0F * scale, 0.0F, dy);
        }}
        float top = ((Number) tyf.get(flick)).floatValue();
        return "NOT-IN-VIEW rel=" + Math.round(oy - top) + " viewH=" + Math.round(viewH);
""")


def click_and_focused(mcp, x, y, object_name):
    """Click at a point, then report whether the named item ended up holding focus.

    Both halves in one eval so nothing can run between them. The answer is qml4j's own
    `focused()`, compared by identity against the named item — asking the field whether it
    "looks focused" would be asking the thing under test.
    """
    return mcp.java("ClickFocus" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        // Clear focus FIRST, and report what it was. The check above this one focuses the field
        // with setFocus, so without this the click would be asserting against focus that was
        // already there — measured: with the button translation reverted, this check still passed
        // until the clear was added. A precondition that is merely likely is not a precondition.
        view.getClass().getMethod("clearFocus").invoke(view);
        Object before = view.getClass().getMethod("focused").invoke(view);
        if (before != null) return "FOCUS-NOT-CLEARED " + before;

        java.lang.reflect.Method down = surf.getClass().getMethod("pointerDown", float.class, float.class, int.class);
        java.lang.reflect.Method up = surf.getClass().getMethod("pointerUp", float.class, float.class, int.class);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        float px = {x}f * scale, py = {y}f * scale;
        // Button 0 is LWJGL's left, which is what MC hands the SPI.
        down.invoke(surf, px, py, 0);
        up.invoke(surf, px, py, 0);
        Object wrapper = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (wrapper == null) return "NO-ITEM";
        // The wrapper is the FluentTextBox Item; the focusable node is the TextField inside it.
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(wrapper);
        Object field = null;
        while (!q.isEmpty()) {{
          Object n = q.poll();
          if (n.getClass().getName().endsWith("items.input.TextField")) {{ field = n; break; }}
          for (Object c : (java.util.List<?>) n.getClass().getField("children").get(n)) q.add(c);
        }}
        if (field == null) return "NO-TEXTFIELD";
        Object focused = view.getClass().getMethod("focused").invoke(view);
        return "focused=" + (focused == field) + " got=" + focused;
""")


def type_and_read(mcp, text, object_name):
    """Type into whatever currently holds focus, then read the named field back.

    Deliberately does NOT focus anything: it runs after click_and_focused, which cleared focus
    before clicking, so the characters can only land if the CLICK is what gave the field focus.
    """
    return mcp.java("TypeInto" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object wrapper = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (wrapper == null) return "NO-ITEM";
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(wrapper);
        Object field = null;
        while (!q.isEmpty()) {{
          Object n = q.poll();
          if (n.getClass().getName().endsWith("items.input.TextField")) {{ field = n; break; }}
          for (Object c : (java.util.List<?>) n.getClass().getField("children").get(n)) q.add(c);
        }}
        if (field == null) return "NO-TEXTFIELD";
        // Clear whatever an earlier check left behind, so the read cannot pass on stale text.
        field.getClass().getMethod("setText", String.class).invoke(field, "");
        java.lang.reflect.Method key = surf.getClass().getMethod("key", int.class, String.class, boolean.class, boolean.class);
        String s = "{text}";
        for (int i = 0; i < s.length(); i++) key.invoke(surf, 0, String.valueOf(s.charAt(i)), false, false);
        return "typed=" + s + " read=" + field.getClass().getMethod("text").invoke(field);
""")


def resize(mcp, w, h):
    """Drive a size change, the historic 'world goes black after a resize' path."""
    return mcp.java("Resize", SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method frame = surf.getClass().getMethod("frame", int.class, int.class, long.class);
        frame.invoke(surf, {w}, {h}, System.nanoTime());
        java.lang.reflect.Method isOpen = surf.getClass().getMethod("isOpen");
        java.lang.reflect.Method lastErr = surf.getClass().getMethod("lastError");
        return "isOpen=" + isOpen.invoke(surf) + " lastError=" + lastErr.invoke(surf);
""")


def close_ui(mcp):
    return mcp.java("CloseUi", """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        mc.displayGuiScreen(null);
        return "closed currentScreen=" + mc.currentScreen;
""")


def alive():
    """Whether the client process still exists.

    Checked after every step, because the failures that matter most here are not assertion
    failures but hard crashes: two of the three bugs this probe exists for killed the JVM
    outright, and a dead process is otherwise indistinguishable from a hung call.
    """
    out = subprocess.run(["pgrep", "-f", "net.minecraft.client.main.Main"],
                         capture_output=True, text=True)
    return out.returncode == 0 and out.stdout.strip() != ""


def wait_for_mcp(port, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            socket.create_connection(("127.0.0.1", port), 2).close()
            return True
        except OSError:
            time.sleep(2)
    return False


def wait_for_world(mcp, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = mcp.java("InWorld", """
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return "inWorld=" + (mc.theWorld != null);
""")
        if "inWorld=true" in state:
            return True
        time.sleep(3)
    return False


def step(name, value, predicate, mcp=None):
    """Assert on a snippet's returned text, and fail loudly on a crash or a Java throw."""
    if not alive():
        return record(name, False, "the client process died during this step")
    if value.startswith("PROBE-ERROR") or value.startswith("THREW") or value.startswith("NOT-DWM"):
        return record(name, False, value.split("\n")[0][:200])
    return record(name, predicate(value), value[:200])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--timeout", type=int, default=180,
                    help="seconds to wait for the MCP port")
    ap.add_argument("--keep", action="store_true",
                    help="leave the UI open at the end instead of closing it")
    args = ap.parse_args()

    print(f"live-dwm-probe: waiting for MCP on 127.0.0.1:{args.port}")
    if not wait_for_mcp(args.port, args.timeout):
        print(f"TIMEOUT: nothing listening on {args.port} within {args.timeout}s.")
        print("Start the client first:  ./scripts/run-mcp.sh")
        return EXIT_TIMEOUT
    if not alive():
        print("SETUP: the port is open but no client process was found.")
        return EXIT_SETUP

    mcp = Mcp(args.port, client_name="live-dwm-probe")

    print("\n-- world")
    step("a world can be entered", load_world(mcp),
         lambda v: "already-in-world" in v or "loading" in v)
    if not wait_for_world(mcp):
        record("the world finished loading", False, "still not in a world")
        return report()
    record("the world finished loading", True)

    print("\n-- opening the UI over live gameplay")
    step("DwmEntry builds and shows a screen", open_ui(mcp), lambda v: "opened" in v)
    time.sleep(2)
    step("the surface is live and fault-free", ui_health(mcp),
         lambda v: "isOpen=true" in v and "inert=false" in v and "lastError=null" in v)

    print("\n-- the composite actually reaches MC's framebuffer")
    # The assertion the flush bug would fail: state said healthy, pixels said nothing arrived.
    step("the window is visible in the target framebuffer", target_pixels(mcp),
         lambda v: "lit=3" in v)

    print("\n-- navigation, driven through dwm's own input SPI")
    for page, row in zip(PAGES[1:], ROWS[1:]):
        centre = row_centre(mcp, row)
        if "," not in centre:
            record(f"{row}'s position is readable", False, centre)
            continue
        x, y = (int(n) for n in centre.split(","))
        step(f"clicking {row} at {x},{y} selects {page}", click(mcp, x, y),
             lambda v: "down=true" in v)
        time.sleep(0.7)
        step(f"{page} is the current page", current_page(mcp), lambda v, p=page: v.strip() == p)

    print("\n-- text input on the settings page")
    # The settings page is selected by the loop above, so its text box exists now.
    step("a typed string reaches the focused field", focus_and_read_field(mcp, "abc"),
         lambda v: "read=abc" in v)

    # And the path a user actually takes: CLICK the box, then type. The check above calls
    # setFocus directly, so it proves a keystroke reaches a focused field and says nothing
    # about what focuses it. That gap hid a real bug — dwm passed LWJGL's zero-based button
    # index straight to qml4j, which reads Qt's bitmask where left is 1, so a left click
    # arrived as Qt.NoButton and never ran the focus branch. Every MouseArea kept working
    # (qml4j treats button 0 as a wildcard when hit-testing them), so nothing else noticed.
    box = item_box(mcp, "settingsName")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        step("clicking the text box focuses it", click_and_focused(mcp, x + w // 2, y + h // 2,
                                                                  "settingsName"),
             lambda v: "focused=true" in v)
        step("and typing then lands in the clicked box", type_and_read(mcp, "xy", "settingsName"),
             lambda v: "read=xy" in v)
    else:
        record("the text box's box is readable", False, box)

    print("\n-- control interaction, on the settings page")
    check_controls(mcp)

    print("\n-- a card's plate reaches the SCREEN, not just the offscreen layer")
    check_card_plate_on_screen(mcp)

    print("\n-- the world still renders behind the UI")
    step("the client survived every interaction", "alive", lambda v: alive())

    print("\n-- a resize does not black the world out")
    step("the surface survives a size change", resize(mcp, 1280, 720),
         lambda v: "isOpen=true" in v and "lastError=null" in v)
    step("and the original size comes back", resize(mcp, 1708, 960),
         lambda v: "isOpen=true" in v and "lastError=null" in v)

    if not args.keep:
        print("\n-- teardown")
        step("the screen closes cleanly", close_ui(mcp), lambda v: "closed" in v)
        step("the client is still running after closing", "alive", lambda v: alive())

    return report()


def check_controls(mcp):
    """Drive the settings page's controls and assert their STATE changed.

    Headless pixel tests already prove these render; what they cannot show is that a real pointer
    sequence arriving through MC's input path moves them. Each assertion is about the control's own
    property afterwards, not about the input call returning — a dispatch that is consumed and does
    nothing looks identical from outside, which is exactly the shape of the key bug fixed earlier.
    """
    # --- toggle switch: a click must flip `checked`.
    before = item_property(mcp, "settingsFullbright", "checked")
    box = item_box(mcp, "settingsFullbright")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        step("clicking the toggle flips it",
             click(mcp, x + w // 2, y + h // 2) + " before=" + before,
             lambda v: "down=true" in v)
        after = item_property(mcp, "settingsFullbright", "checked")
        record("the toggle's checked state actually changed",
               before.strip() in ("true", "false") and after.strip() != before.strip(),
               f"{before.strip()} -> {after.strip()}")
    else:
        record("the toggle's box is readable", False, box)

    # --- the expander must open before anything inside it can be reached.
    #
    # The check box below lives in a collapsed FluentSettingsExpander, so a click at its
    # coordinates lands on clipped-away content. That is CORRECT behaviour, not a bug -- an
    # unopened group's rows are not on screen. Verified the distinction: before this step the
    # check box reported down=false while every other control worked.
    collapsed = item_property(mcp, "settingsAdvanced", "expanded")
    box = item_box(mcp, "settingsAdvanced")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        # Click the header, away from the chevron, so this also proves the whole header is the
        # target rather than just the 32px button.
        step("clicking the expander header opens it", click(mcp, x + 60, y + h // 2),
             lambda v: "down=true" in v)
        opened = item_property(mcp, "settingsAdvanced", "expanded")
        record("the expander actually expanded",
               collapsed.strip() == "false" and opened.strip() == "true",
               f"{collapsed.strip()} -> {opened.strip()}")
    else:
        record("the expander's box is readable", False, box)

    # --- scrolling, which the page now needs: the expanded group's rows sit below the fold.
    step("the page scrolls the expander's rows into view",
         scroll_into_view(mcp, "settingsTelemetry"),
         lambda v: v.strip().startswith("in-view"))

    # --- check box: same shape as the toggle, but inside the expander opened and scrolled to.
    before = item_property(mcp, "settingsTelemetry", "checked")
    box = item_box(mcp, "settingsTelemetry")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        step("clicking the check box flips it", click(mcp, x + 10, y + h // 2),
             lambda v: "down=true" in v)
        after = item_property(mcp, "settingsTelemetry", "checked")
        record("the check box's checked state actually changed",
               before.strip() in ("true", "false") and after.strip() != before.strip(),
               f"{before.strip()} -> {after.strip()}")
    else:
        record("the check box's box is readable", False, box)

    # --- slider: a DRAG, which is the path a click cannot exercise. qml4j delivers
    # positionChanged only to a captured area, so this is the only way to reach it.
    before = item_property(mcp, "settingsGamma", "value")
    box = item_box(mcp, "settingsGamma")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        # From a quarter across to near the right end, so the value must rise substantially.
        step("dragging the slider is dispatched",
             drag(mcp, x + w // 4, y + h // 2, x + w - 12), lambda v: "dragged" in v)
        after = item_property(mcp, "settingsGamma", "value")
        try:
            moved = float(after) > float(before) + 0.2
        except ValueError:
            moved = False
        record("the slider's value rose with the drag", moved,
               f"{before.strip()} -> {after.strip()} (drag right across {w}px)")

        # And back the other way, so the assertion cannot be satisfied by a value that merely
        # snaps to one end regardless of input.
        step("dragging back is dispatched",
             drag(mcp, x + w - 12, y + h // 2, x + 12), lambda v: "dragged" in v)
        back = item_property(mcp, "settingsGamma", "value")
        try:
            fell = float(back) < float(after) - 0.2
        except ValueError:
            fell = False
        record("and fell when dragged the other way", fell,
               f"{after.strip()} -> {back.strip()}")
    else:
        record("the slider's box is readable", False, box)

    # --- hover: the backplate must respond to motion alone, with no button down.
    #
    # Scrolled back to the toggle first. The check box step above scrolled the page down, and the
    # toggle is in the first group -- so by this point it is genuinely off screen and a hover at
    # its coordinates would report false for the correct reason. Reading a box is not enough; the
    # item has to be IN VIEW for a pointer test to mean anything.
    step("the page scrolls back to the first group",
         scroll_into_view(mcp, "settingsFullbright"),
         lambda v: v.strip().startswith("in-view"))
    box = item_box(mcp, "settingsFullbright")
    if "," in box:
        x, y, w, h = (int(n) for n in box.split(","))
        # Move and read in ONE eval, per direction. Splitting them across two MCP calls -- which
        # this did -- lets the game thread run frames in between, and that made the check
        # intermittently report over=false: the move landed, the frame advanced, and the read
        # arrived describing a different moment. It surfaced once the toggle's knob gained an
        # 83ms animation, but the race was always there. Same reasoning as click_and_focused.
        over = hover_and_read(mcp, x + w // 2, y + h // 2, "settingsFullbright")
        off = hover_and_read(mcp, 5, 5, "settingsFullbright")
        record("hovering a control sets containsMouse, and leaving clears it",
               "containsMouse=true" in over and "containsMouse=false" in off,
               f"over=[{over.strip()}] off=[{off.strip()}]")
    else:
        record("the toggle's box is readable for hover", False, box)


def hover_and_read(mcp, x, y, object_name):
    """Move the pointer to a point and report the named control's containsMouse, atomically.

    One eval, so no frame can run between the move and the read. Two calls cannot express this:
    the pointer state the assertion is about only holds until the next frame.
    """
    return mcp.java("HoverRead" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method move = surf.getClass().getMethod("pointerMove", float.class, float.class);
        java.lang.reflect.Method usm = surf.getClass().getDeclaredMethod("uiScale");
        usm.setAccessible(true);
        float scale = (Float) usm.invoke(surf);
        move.invoke(surf, {x}f * scale, {y}f * scale);

        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object it = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (it == null) return "NO-ITEM";
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(it);
        while (!q.isEmpty()) {{
          Object n = q.poll();
          if (n.getClass().getName().endsWith("core.MouseArea")) {{
            Object cm = n.getClass().getField("containsMouse").get(n);
            return "containsMouse=" + cm.getClass().getMethod("peek").invoke(cm);
          }}
          for (Object c : (java.util.List<?>) n.getClass().getField("children").get(n)) q.add(c);
        }}
        return "NO-MOUSEAREA";
""")


def hovered(mcp, object_name):
    """Whether the named control's own MouseArea currently reports the pointer inside it."""
    return mcp.java("Hovered" + object_name, SURFACE_PREAMBLE + f"""
        java.lang.reflect.Method vm = surf.getClass().getDeclaredMethod("view");
        vm.setAccessible(true);
        Object view = vm.invoke(surf);
        Object it = view.getClass().getMethod("findByObjectName", String.class)
            .invoke(view, "{object_name}");
        if (it == null) return "NO-ITEM";
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(it);
        while (!q.isEmpty()) {{
          Object n = q.poll();
          if (n.getClass().getName().endsWith("items.core.MouseArea")) {{
            Object p = n.getClass().getField("containsMouse").get(n);
            return String.valueOf(p.getClass().getMethod("peek").invoke(p));
          }}
          for (Object c : (java.util.List<?>) n.getClass().getField("children").get(n)) q.add(c);
        }}
        return "NO-MOUSEAREA";
""")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(EXIT_FAIL)
