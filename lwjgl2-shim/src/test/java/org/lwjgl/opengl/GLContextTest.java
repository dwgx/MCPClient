package org.lwjgl.opengl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Headless regression tests for {@link GLContext} caching + {@link ContextCapabilities}
 * construction. MC reaches capabilities through {@code GLContext.getCapabilities()}
 * (8 sites). Without a live GL context, ContextCapabilities finds no LWJGL3
 * GLCapabilities and leaves every flag false rather than throwing — that
 * fail-soft behaviour and the per-thread caching are what we pin here. The actual
 * flag population against a real context is RUNTIME_ONLY.
 */
public class GLContextTest {

    /** getCapabilities never returns null even with no bound GL context. */
    @Test
    public void capabilitiesNeverNull() {
        assertNotNull(GLContext.getCapabilities());
    }

    /** Repeated calls on the same thread return the identical cached instance. */
    @Test
    public void capabilitiesCachedPerThread() {
        ContextCapabilities first = GLContext.getCapabilities();
        ContextCapabilities second = GLContext.getCapabilities();
        assertSame("same thread must get the cached instance", first, second);
    }

    /** A different thread gets its own instance (ThreadLocal isolation). */
    @Test
    public void capabilitiesDistinctAcrossThreads() throws InterruptedException {
        ContextCapabilities mine = GLContext.getCapabilities();
        final AtomicReference<ContextCapabilities> other = new AtomicReference<ContextCapabilities>();

        Thread t = new Thread(new Runnable() {
            public void run() {
                other.set(GLContext.getCapabilities());
            }
        });
        t.start();
        t.join();

        assertNotNull(other.get());
        assertNotSame("each thread must have its own capability snapshot", mine, other.get());
    }

    /**
     * With no GL context bound (headless), constructing ContextCapabilities must
     * fail soft: no exception, and every advertised flag stays false. This is the
     * exact contract MC relies on when it reads e.g. OpenGL21 / GL_ARB_* fields.
     */
    @Test
    public void noContextLeavesFlagsFalse() {
        ContextCapabilities caps = new ContextCapabilities();
        assertFalse(caps.OpenGL13);
        assertFalse(caps.OpenGL20);
        assertFalse(caps.OpenGL21);
        assertFalse(caps.OpenGL30);
        assertFalse(caps.GL_ARB_multitexture);
        assertFalse(caps.GL_ARB_vertex_buffer_object);
        assertFalse(caps.GL_ARB_shader_objects);
        assertFalse(caps.GL_EXT_framebuffer_object);
        assertFalse(caps.GL_ARB_framebuffer_object);
        assertFalse(caps.GL_NV_fog_distance);
    }
}
