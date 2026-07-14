package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Headless teeth for {@link MipFill}'s row-batch arithmetic — the reverted
 * {@code MipZeroFillCoverageTest} equivalent. {@link MipFill#zero} drives GL through
 * {@link MipFill#forEachBatch}, so proving the batching covers EXACTLY {@code w*h} texels per
 * level with no gap, no overlap, and no dropped partial batch proves the live upload's
 * coverage without a GL context (the actual disappearance of the specks is a live/GPU
 * property, deferred to the owner's live check).
 *
 * <p>Every assertion fails on a broken tiling: a batch loop that dropped the final partial
 * batch, double-counted, or mis-sized a row batch would fail the exact {@code w*h} equality
 * and the contiguous-coverage walk below.
 */
public final class MipFillCoverageTest {

    /** One recorded batch: [startRow, rows, texels]. */
    private static List<int[]> batches(int levelWidth, int levelHeight) {
        List<int[]> out = new ArrayList<>();
        MipFill.forEachBatch(levelWidth, levelHeight, (y, rows, texels) -> out.add(new int[]{y, rows, texels}));
        return out;
    }

    /** Assert the batches tile [0,height) contiguously and sum to exactly width*height texels. */
    private static void assertExactCoverage(int width, int height) {
        List<int[]> bs = batches(width, height);
        long totalTexels = 0;
        int expectedNextRow = 0;
        for (int[] b : bs) {
            int y = b[0], rows = b[1], texels = b[2];
            assertEquals("batch must start exactly where the previous ended (no gap/overlap)",
                    expectedNextRow, y);
            assertTrue("each batch must cover at least one row", rows >= 1);
            assertTrue("no batch may run past the level height",
                    y + rows <= height);
            assertEquals("texels must be width*rows for this batch", (long) width * rows, texels);
            totalTexels += texels;
            expectedNextRow = y + rows;
        }
        assertEquals("batches must reach exactly the level height", height, expectedNextRow);
        assertEquals("total texels must equal exactly width*height (no gap/overlap/drop)",
                (long) width * height, totalTexels);
    }

    @Test
    public void coversExactlyForTypicalAtlasLevels() {
        // A 512x512 atlas across mip levels 0..4 (the atlas TextureUtil logs at startup).
        int fullW = 512, fullH = 512;
        for (int level = 0; level <= 4; level++) {
            int w = fullW >> level;
            int h = fullH >> level;
            assertExactCoverage(w, h);
        }
    }

    @Test
    public void coversExactlyForNonPowerOfTwoAndTallLevels() {
        int[][] sizes = {
                {1, 1}, {1, 999}, {999, 1}, {3, 7}, {17, 4099}, {4096, 4096}, {13, 13}, {1024, 3},
        };
        for (int[] s : sizes) {
            assertExactCoverage(s[0], s[1]);
        }
    }

    @Test
    public void batchIsMultipleRowsWhenWidthSmallAndSingleRowWhenWide() {
        // width small -> many rows fit one batch (BUFFER_INTS / width rows per batch).
        List<int[]> small = batches(2, 100);
        assertEquals("first batch should hold min(rowsPerBatch, height) rows",
                Math.min(MipFill.BUFFER_INTS / 2, 100), small.get(0)[1]);

        // width == BUFFER_INTS -> exactly one row per batch (rowsPerBatch == 1).
        List<int[]> wide = batches(MipFill.BUFFER_INTS, 3);
        assertEquals("a full-width level must upload one row per batch", 3, wide.size());
        for (int[] b : wide) {
            assertEquals(1, b[1]);
        }
    }

    @Test
    public void handlesPartialFinalBatchWithoutDropping() {
        // height not a multiple of rowsPerBatch => the last batch is a smaller partial batch.
        int width = 3;                                  // rowsPerBatch = BUFFER_INTS/3
        int rowsPerBatch = MipFill.BUFFER_INTS / width;
        int height = rowsPerBatch + 5;                  // one full batch + a 5-row partial
        List<int[]> bs = batches(width, height);
        assertEquals("expected one full batch + one partial", 2, bs.size());
        assertEquals(rowsPerBatch, bs.get(0)[1]);
        assertEquals("the partial final batch must not be dropped", 5, bs.get(1)[1]);
        assertExactCoverage(width, height);
    }

    @Test
    public void degenerateSizeIsNoOp() {
        assertTrue("zero width -> no batches", batches(0, 10).isEmpty());
        assertTrue("zero height -> no batches", batches(10, 0).isEmpty());
        assertTrue("negative size -> no batches (never throws)", batches(-1, -1).isEmpty());
    }

    @Test
    public void bufferCapacityMatchesTextureUtilDataBuffer() {
        // The shared zero buffer must hold a full row batch of the widest level we ever fill.
        assertEquals(4194304, MipFill.BUFFER_INTS);
        assertTrue("shared buffer must have capacity for a full BUFFER_INTS batch",
                MipFill.buffer().capacity() >= MipFill.BUFFER_INTS);
    }

    @Test
    public void sharedBufferIsAllZero() {
        // The whole point: the scratch buffer we upload as "transparent black" must be all zeros,
        // so nothing but BGRA 0 ever reaches the texture. A single non-zero int would reintroduce
        // garbage.
        java.nio.IntBuffer buf = MipFill.buffer();
        boolean anyNonZero = false;
        for (int i = 0; i < buf.capacity(); i++) {
            if (buf.get(i) != 0) {
                anyNonZero = true;
                break;
            }
        }
        assertFalse("the shared zero buffer must be entirely zero (transparent black)", anyNonZero);
    }
}
