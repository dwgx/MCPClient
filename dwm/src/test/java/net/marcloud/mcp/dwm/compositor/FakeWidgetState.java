package net.marcloud.mcp.dwm.compositor;

/**
 * Minimal {@link WidgetState} for store/compositor tests: counts ticks and reports
 * animating until a set number of ticks elapse.
 */
final class FakeWidgetState implements WidgetState {

    int ticks;
    float accumulatedDt;
    private int animatingForTicks;

    FakeWidgetState() {
        this(0);
    }

    /** Reports animating() true until this many ticks have been applied. */
    FakeWidgetState(int animatingForTicks) {
        this.animatingForTicks = animatingForTicks;
    }

    @Override
    public void tick(float dtSeconds) {
        ticks++;
        accumulatedDt += dtSeconds;
    }

    @Override
    public boolean animating() {
        return ticks < animatingForTicks;
    }
}
