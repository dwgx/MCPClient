package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the Windows sampler-object restore in source, because the live IT's snapshot
 * used to omit {@code GL_SAMPLER_BINDING} and therefore stayed green while Skia leaked
 * a sampler onto unit 0 — the "settings/singleplayer screen corruption" already proven
 * on the previous dwm-gl guard.
 */
public class GlStateGuardContractTest {

    private static String src() {
        try {
            return new String(Files.readAllBytes(
                    Paths.get("src/main/java/net/marcloud/mcp/dwm/qml/GlStateGuard.java")),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("cannot read GlStateGuard.java", e);
        }
    }

    @Test
    public void leaveRestoresSamplerObjectBindings() {
        String s = src();
        assertTrue("leave() must bind sampler objects; Skia leaves one on unit 0 and "
                + "glPushAttrib cannot save it",
                s.contains("glBindSampler"));
        assertTrue("enter() must read GL_SAMPLER_BINDING, not hardcode unbind-to-zero, "
                + "matching the restore-what-was-there rule",
                s.contains("GL_SAMPLER_BINDING"));
        assertTrue("sampler restore must be gated on the glBindSampler entry point so Apple "
                + "GL 2.1 still loads",
                s.contains("glBindSampler != 0"));
    }
}
