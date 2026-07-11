package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Phase-2 regression tests for the frozen {@link Matrix} manager contract:
 * add/byId/contains indexing, removeById, batch {@code enableAll}/{@code
 * disableAll}/{@code clear}, insertion-ordered snapshots, snapshot
 * immutability, and duplicate-id rejection. Complements the Phase-1
 * {@code MatrixTest} with the removeById path, clear() unload semantics, and
 * unmodifiable-snapshot enforcement.
 */
public class MatrixManagementTest {

    static final class Feature extends Chip {
        final String id;
        int loads;
        int unloads;
        int enables;
        int disables;

        Feature(String id) {
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
    public void addIndexesByIdAndContains() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        Feature a = matrix.add(new Feature("alpha"));
        Feature b = matrix.add(new Feature("beta"));
        assertSame(a, matrix.byId("alpha"));
        assertSame(b, matrix.byId("beta"));
        assertTrue(matrix.contains("alpha"));
        assertTrue(matrix.contains("beta"));
        assertFalse(matrix.contains("gamma"));
        assertNull(matrix.byId("gamma"));
        assertEquals(2, matrix.size());
    }

    @Test
    public void removeByIdDisablesFiresUnloadAndReturnsChip() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        Feature f = matrix.add(new Feature("gone"));
        f.setEnabled(true);
        assertEquals(1, f.enables);

        Feature removed = matrix.removeById("gone");
        assertSame(f, removed);
        assertEquals(1, f.disables);
        assertEquals(1, f.unloads);
        assertFalse(f.isEnabled());
        assertFalse(matrix.contains("gone"));
        assertEquals(0, matrix.size());
    }

    @Test
    public void removeByIdOnAbsentReturnsNull() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        assertNull(matrix.removeById("nope"));
    }

    @Test
    public void removeAbsentChipReturnsFalse() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        Feature detached = new Feature("floating");
        assertFalse(matrix.remove(detached));
    }

    @Test
    public void enableAllThenDisableAllAcrossManyChips() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        List<Feature> features = new ArrayList<Feature>();
        for (int i = 0; i < 5; i++) {
            features.add(matrix.add(new Feature("f" + i)));
        }
        matrix.enableAll();
        for (Feature f : features) {
            assertTrue(f.isEnabled());
            assertEquals(1, f.enables);
        }
        matrix.disableAll();
        for (Feature f : features) {
            assertFalse(f.isEnabled());
            assertEquals(1, f.disables);
        }
    }

    @Test
    public void clearDisablesUnloadsAndEmpties() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        Feature a = matrix.add(new Feature("a"));
        Feature b = matrix.add(new Feature("b"));
        a.setEnabled(true);
        b.setEnabled(true);

        matrix.clear();

        assertEquals(0, matrix.size());
        assertTrue(matrix.all().isEmpty());
        assertEquals(1, a.disables);
        assertEquals(1, a.unloads);
        assertEquals(1, b.disables);
        assertEquals(1, b.unloads);
        assertFalse(matrix.contains("a"));
        assertFalse(matrix.contains("b"));
    }

    @Test
    public void allReturnsInsertionOrderSnapshot() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        matrix.add(new Feature("one"));
        matrix.add(new Feature("two"));
        matrix.add(new Feature("three"));
        List<Feature> all = matrix.all();
        assertEquals(3, all.size());
        assertEquals("one", all.get(0).id());
        assertEquals("two", all.get(1).id());
        assertEquals("three", all.get(2).id());
    }

    @Test
    public void allSnapshotDoesNotReflectLaterMutation() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        matrix.add(new Feature("stable"));
        List<Feature> snapshot = matrix.all();
        assertEquals(1, snapshot.size());
        // Add another chip AFTER taking the snapshot; the old snapshot is frozen.
        matrix.add(new Feature("added-later"));
        assertEquals(1, snapshot.size());
        assertEquals(2, matrix.all().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void allSnapshotIsUnmodifiable() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        matrix.add(new Feature("x"));
        matrix.all().add(new Feature("intruder"));
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateIdIsRejected() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        matrix.add(new Feature("dup"));
        matrix.add(new Feature("dup"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void addNullIsRejected() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        matrix.add(null);
    }

    @Test
    public void reAddAfterRemoveSucceedsAndReloads() {
        Matrix<Feature> matrix = new Matrix<Feature>();
        Feature f = matrix.add(new Feature("cycle"));
        assertEquals(1, f.loads);
        matrix.removeById("cycle");
        // id is free again; a fresh chip with the same id can be added
        Feature again = matrix.add(new Feature("cycle"));
        assertEquals(1, again.loads);
        assertTrue(matrix.contains("cycle"));
        assertEquals(1, matrix.size());
    }

    /**
     * Re-entrancy regression (review finding M2): a chip whose {@code onEnable}
     * mutates the matrix must NOT throw ConcurrentModificationException and abort
     * enableAll half-applied. Before the snapshot fix, enableAll iterated the live
     * map and this threw CME.
     */
    @Test
    public void enableAllToleratesChipThatMutatesMatrixDuringOnEnable() {
        final Matrix<Chip> matrix = new Matrix<Chip>();
        final Chip late = new Chip() {
            @Override
            public String id() {
                return "late";
            }
        };
        // A coordinator chip that, when enabled, adds another chip to the matrix.
        Chip coordinator = new Chip() {
            @Override
            public String id() {
                return "coordinator";
            }

            @Override
            protected void onEnable() {
                if (!matrix.contains("late")) {
                    matrix.add(late);
                }
            }
        };
        matrix.add(coordinator);
        matrix.enableAll(); // must not throw CME
        assertTrue("coordinator enabled", coordinator.isEnabled());
        assertTrue("late chip was added mid-batch", matrix.contains("late"));
        assertEquals(2, matrix.size());
    }

    /**
     * Review finding S3: Matrix (and HudMatrix) implement a shared {@link Manager}
     * interface so code can treat any manager uniformly instead of knowing each
     * concrete type. This pins that Matrix IS a Manager and behaves correctly
     * through the interface reference.
     */
    @Test
    public void matrixIsUsableThroughTheManagerInterface() {
        Manager<Feature> mgr = new Matrix<Feature>();
        Feature f = mgr.add(new Feature("via-iface"));
        assertEquals(1, f.loads);
        assertTrue(mgr.contains("via-iface"));
        assertSame(f, mgr.byId("via-iface"));
        assertEquals(1, mgr.size());
        assertEquals(1, mgr.all().size());
        mgr.clear();
        assertEquals(0, mgr.size());
        assertEquals(1, f.unloads); // clear() ran the unload hook through the interface
    }
}
