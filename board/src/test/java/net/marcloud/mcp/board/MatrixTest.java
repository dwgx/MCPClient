package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for the {@link Chip} lifecycle and {@link Matrix} manager. */
public class MatrixTest {

    static final class CountingChip extends Chip {
        final String id;
        int loads;
        int unloads;
        int enables;
        int disables;

        CountingChip(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        protected void onLoad() {
            loads++;
        }

        @Override
        protected void onUnload() {
            unloads++;
        }

        @Override
        protected void onEnable() {
            enables++;
        }

        @Override
        protected void onDisable() {
            disables++;
        }
    }

    @Test
    public void addFiresOnLoadAndIndexesById() {
        Matrix<CountingChip> matrix = new Matrix<CountingChip>();
        CountingChip chip = matrix.add(new CountingChip("fly"));
        assertEquals(1, chip.loads);
        assertSame(chip, matrix.byId("fly"));
        assertEquals(1, matrix.size());
    }

    @Test
    public void toggleFiresEnableDisableOncePerChange() {
        CountingChip chip = new CountingChip("x");
        assertFalse(chip.isEnabled());
        assertTrue(chip.toggle());
        assertTrue(chip.isEnabled());
        assertEquals(1, chip.enables);
        // idempotent set: no extra callback
        chip.setEnabled(true);
        assertEquals(1, chip.enables);
        assertFalse(chip.toggle());
        assertEquals(1, chip.disables);
    }

    @Test
    public void removeFiresUnloadAndDisables() {
        Matrix<CountingChip> matrix = new Matrix<CountingChip>();
        CountingChip chip = matrix.add(new CountingChip("y"));
        chip.setEnabled(true);
        assertTrue(matrix.remove(chip));
        assertEquals(1, chip.unloads);
        assertEquals(1, chip.disables);
        assertFalse(chip.isEnabled());
        assertNull(matrix.byId("y"));
    }

    @Test
    public void batchEnableAllAndDisableAll() {
        Matrix<CountingChip> matrix = new Matrix<CountingChip>();
        CountingChip a = matrix.add(new CountingChip("a"));
        CountingChip b = matrix.add(new CountingChip("b"));
        matrix.enableAll();
        assertTrue(a.isEnabled());
        assertTrue(b.isEnabled());
        matrix.disableAll();
        assertFalse(a.isEnabled());
        assertFalse(b.isEnabled());
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateIdRejected() {
        Matrix<CountingChip> matrix = new Matrix<CountingChip>();
        matrix.add(new CountingChip("dup"));
        matrix.add(new CountingChip("dup"));
    }

    @Test
    public void optionalAttributesDefaultAndSet() {
        CountingChip chip = new CountingChip("z");
        assertNull(chip.category());
        assertEquals(Chip.NO_PIN, chip.pin());
        chip.setPin(42);
        assertEquals(42, chip.pin());
    }

    @Test
    public void allReturnsInsertionOrderSnapshot() {
        Matrix<CountingChip> matrix = new Matrix<CountingChip>();
        matrix.add(new CountingChip("first"));
        matrix.add(new CountingChip("second"));
        assertEquals("first", matrix.all().get(0).id());
        assertEquals("second", matrix.all().get(1).id());
        assertEquals(2, matrix.all().size());
    }
}
