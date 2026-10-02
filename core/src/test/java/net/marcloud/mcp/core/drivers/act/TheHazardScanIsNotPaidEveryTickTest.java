package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The straight-line hazard scan must not be paid per tick.
 *
 * <p>The scan samples 48 cells, up to two reads each, plus a readability probe -- 97 block reads
 * on a clear line -- and it ran on <b>every tick</b> of a walk that can run for hundreds of ticks.
 * The world does not change while a player walks a straight line, so every tick after the first
 * re-derived a fact that had not changed.
 *
 * <p>This is measured, not asserted. The Owner's standing instruction is that input realism may
 * not cost capability, and a diagnostic that spends a hundred world reads a tick is spending the
 * walk it is meant to be describing. A warning nobody can afford to print is not a warning.
 *
 * <p>The property pinned is the ratio, not an absolute: a walk must not cost roughly a full scan
 * PER TICK. One scan for a multi-tick walk is fine; one per tick is the regression.
 */
public final class TheHazardScanIsNotPaidEveryTickTest {

    /** Counts blockAt calls, so the cost is a number rather than an impression. */
    private static final class CountingActuator extends FakeActuator {
        int reads;

        @Override
        public String blockAt(int x, int y, int z) {
            reads++;
            return super.blockAt(x, y, z);
        }
    }

    private static NavController walk(CountingActuator act, int ticks) {
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        NavController nav = new NavController(24, 64, 0, 400);
        for (int i = 0; i < ticks; i++) {
            nav.tick(act);
        }
        return nav;
    }

    @Test
    public void aLongWalkCostsOneScanNotOnePerTick() {
        CountingActuator act = new CountingActuator();
        NavController nav = walk(act, 60);
        int reads = act.reads;

        // One scan is at most SCAN_MAX_BLOCKS*2 + 1 = 97. Allow a generous multiple for the
        // other per-tick reads (arrival, grounding) and for re-sampling if the line moved.
        int oneScan = 97 * 4;
        assertTrue("60 ticks of walking cost " + reads + " block reads. The hazard scan alone is "
                        + "up to 97 on a clear line; paying it per tick is " + (reads / 60.0)
                        + " reads a tick, which is the cost ADR-0005 forbids -- the warning is "
                        + "then too expensive to afford and effectively never runs",
                reads <= oneScan);
    }

    @Test
    public void theScanStillRunsAtAllOnce() {
        // The caching must not have become "never scan". A walk still has to ask the world.
        CountingActuator act = new CountingActuator();
        walk(act, 10);
        assertTrue("10 ticks of walking cost " + act.reads + " block reads, so the diagnostic is "
                + "no longer running at all -- a warning that never fires is not a fix", act.reads > 0);
    }

    @Test
    public void aReusedScanStillReportsTheHazard() {
        // And the cached answer must still be delivered, not silently swallowed once the
        // geometry is reused -- that is the failure mode caching invites.
        CountingActuator act = new CountingActuator();
        act.setPosition(0, 64, 0);
        act.yaw = 0f;
        // Lava directly ahead. The walk is along +x from (0,64,0) to (24,64,0), so the hazard
        // has to sit on THAT line: my first attempt put it at z=6, off to the side, where a
        // straight-line scan correctly never looks.
        for (int x = 6; x <= 9; x++) {
            act.putBlock(x, 64, 0, "minecraft:lava");
        }
        NavController nav = new NavController(24, 64, 0, 400);

        String first = null;
        String later = null;
        for (int i = 0; i < 40; i++) {
            String msg = nav.tick(act).message();
            if (msg != null && msg.contains("LAVA")) {
                if (first == null) {
                    first = msg;
                } else {
                    later = msg;
                }
            }
        }
        assertTrue("a hazard on the line must be named: " + first, first != null);
        assertTrue("and must KEEP being named after the scan is reused, because the caller reads "
                + "this every tick and a warning that appears once is a warning nobody acts on: "
                + later, later != null);
    }
}
