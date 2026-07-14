package net.marcloud.mcp.core.compat.patches;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * The zero-fill runtime helper the {@link Ki1MipmapZeroFillPatch} bytecode calls into.
 *
 * <p>KI-1 root cause: {@code TextureUtil.allocateTextureImpl} allocates each mip level with a
 * NULL pixel pointer ({@code glTexImage2D(..., (IntBuffer) null)}). Under LWJGL2 / older
 * drivers that memory was implicitly zeroed; modern NVIDIA drivers under LWJGL3 do NOT zero
 * it, so atlas regions never written at level {@code i} sample as garbage — blue specks on
 * grass, white flecks/ripples on water, colored seams on distant blocks (one root cause,
 * three visual surfaces; see known-issues.md KI-1).
 *
 * <p>The fix (proven live twice on an RTX 5070 Ti, then reverted from client source and
 * re-shipped as this load-time patch): immediately after each level is allocated, upload
 * transparent-black (BGRA 0) over the whole level in row batches through a shared, always-zero
 * direct {@code IntBuffer} via {@code glTexSubImage2D} — the same batching pattern the vanilla
 * sprite-upload path uses. Level 0 is zero-filled too but is harmlessly overwritten by the
 * normal sprite upload afterward.
 *
 * <p><b>Why a core helper (not raw ASM loop).</b> The patch injects a single {@code
 * INVOKESTATIC MipFill.zero} after the allocation call rather than emitting the whole
 * row-batch loop as bytecode — far more robust. This class lives in Core (on the game
 * classpath at runtime), so the injected call resolves at load time.
 *
 * <p><b>Cannot reach {@code TextureUtil.dataBuffer}.</b> The reverted client fix reused
 * TextureUtil's private 4M-int {@code dataBuffer}; a Core helper cannot, so it owns its own
 * lazily-created direct {@code IntBuffer} of the same capacity. It is written once (JVM
 * zero-initializes direct buffers) and only ever read as zeros, so no per-batch re-zeroing is
 * needed and no sprite data can leak through it.
 *
 * <p><b>Live-only effect.</b> {@link #zero} issues real GL calls, so it runs only with a GL
 * context (in the game). The GL-free {@link #forEachBatch} row-batch arithmetic is unit-tested
 * headless; the actual disappearance of the specks is a live/GPU property.
 */
public final class MipFill {

    /** Capacity of the shared zero buffer, in ints — matches {@code TextureUtil.dataBuffer}. */
    static final int BUFFER_INTS = 4194304;

    /** Lazily-created, always-zero direct scratch buffer (16 MB); see class javadoc. */
    private static IntBuffer zeroBuffer;

    private MipFill() {
    }

    /**
     * Zero-fill mip {@code level} of a texture whose FULL dimensions are {@code width} x
     * {@code height} (the level's own size is {@code width>>level} x {@code height>>level},
     * matching the {@code glTexImage2D(..., width>>i, height>>i, ...)} allocation). No-op for a
     * degenerate (&le; 0) level size. Issues {@code glTexSubImage2D} per row batch; requires a
     * current GL context.
     */
    public static void zero(int level, int width, int height) {
        int w = width >> level;
        int h = height >> level;
        if (w <= 0 || h <= 0) {
            return;
        }
        IntBuffer buf = buffer();
        forEachBatch(w, h, (y, rows, texels) -> {
            buf.position(0).limit(texels);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, level, 0, y, w, rows,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        });
    }

    /** The shared always-zero direct buffer, created on first use. */
    static synchronized IntBuffer buffer() {
        if (zeroBuffer == null) {
            zeroBuffer = ByteBuffer.allocateDirect(BUFFER_INTS * 4)
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
        }
        return zeroBuffer;
    }

    /** Receives one row batch: starting row {@code y}, {@code rows} tall, {@code texels} ints. */
    interface BatchConsumer {
        void batch(int y, int rows, int texels);
    }

    /**
     * The one definition of the row-batch tiling of a {@code levelWidth} x {@code levelHeight}
     * level, GL-free so it is unit-testable. {@link #zero} drives GL through it, so the tested
     * arithmetic is exactly what runs live. Rows are batched so each upload fits the shared
     * buffer: {@code rowsPerBatch = max(1, BUFFER_INTS / levelWidth)}. No-op for a degenerate
     * (&le; 0) size.
     */
    static void forEachBatch(int levelWidth, int levelHeight, BatchConsumer c) {
        if (levelWidth <= 0 || levelHeight <= 0) {
            return;
        }
        int rowsPerBatch = Math.max(1, BUFFER_INTS / levelWidth);
        for (int y = 0; y < levelHeight; y += rowsPerBatch) {
            int rows = Math.min(rowsPerBatch, levelHeight - y);
            c.batch(y, rows, levelWidth * rows);
        }
    }
}
