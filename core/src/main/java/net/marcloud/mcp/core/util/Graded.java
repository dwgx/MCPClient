package net.marcloud.mcp.core.util;

import java.util.Objects;

/**
 * A value together with how it was obtained, and -- unless the value was read directly -- why.
 *
 * <p><b>The carrier rule: belief rides ON the value that was read.</b> There is no
 * {@code Map<Cell, Belief>} and there must never be one. A parallel structure is a second source
 * of truth, and a second source of truth is a second thing to forget to update: a lookup that
 * returns {@link Belief#OBSERVED} for a cell whose value was replaced by an inference is not a
 * conservative failure, it is a confident wrong answer. Grading the value makes that state
 * unconstructible rather than merely unlikely.
 *
 * <p><b>Direction: belief qualifies a READ, not a choice.</b> One read supports many decisions, and
 * a layer that graded the decision would invite inferring the read backwards from it. So this does
 * not belong on {@code MoveTactic} or on any controller: it belongs on the thing that went and
 * looked.
 *
 * <p><b>No {@code double confidence}, and this is not negotiable.</b> An uncalibrated number is
 * consumed downstream as a probability. Nothing in this repository has ever been able to calibrate
 * one -- 1.8.9 sends no verdict back -- so a float here would be a fabricated measurement wearing
 * the costume of one.
 *
 * @param value  what was read. Never null: a grade on nothing grades nothing.
 * @param belief how it was obtained
 * @param why    the derivation, required whenever {@code belief != OBSERVED}; null is permitted for
 *               OBSERVED because there is nothing to derive. Non-null is enforced by the
 *               constructor, not by convention -- see below.
 */
public record Graded<T>(T value, Belief belief, String why) {

    /**
     * Enforces the one invariant that makes the type worth having.
     *
     * <p>{@code why} is required for every grade but OBSERVED, because <b>an INFERRED with no
     * provenance and an INFERRED with an invented one are indistinguishable to a reader</b>, and the
     * second is the one that does damage. This mirrors the treatment of
     * {@code MoveTactic.givenUp} (MoveTactic.java:58-61), which exists for the same reason: "nothing
     * was given up" and "nobody recorded what was given up" must not be the same value. Enforced
     * here rather than documented, because a documented invariant is a convention and conventions
     * are what this repository keeps re-fixing one layer at a time.
     */
    public Graded {
        Objects.requireNonNull(value, "value is required: a belief about nothing is not a grade, "
                + "it is a comment");
        Objects.requireNonNull(belief, "belief is required: 'nobody looked' and 'nobody recorded "
                + "that nobody looked' must not be the same value");
        if (belief != Belief.OBSERVED) {
            Objects.requireNonNull(why, "why is required whenever belief is " + belief
                    + ": a reader cannot tell a derivation from an invention without it, and an "
                    + "invention is worse than an absence because it is acted on");
        }
    }

    /**
     * The only sanctioned way to say OBSERVED, and it is package-private on purpose.
     *
     * <p><b>THE ALLOWLIST. These files, and no others, may call this:</b>
     * <ul>
     * <li>{@code util/BlockProbe.java} -- chunk-loaded check, then the block-state read, this tick
     * ({@code BlockProbe.at}, :113-143). The one place in this repository where the read ordering
     * is already correct.</li>
     * </ul>
     *
     * <p>Every other site in the design's 18 must earn its place here when it is wired, and each
     * addition is a deliberate act checked by {@code GradedCallSitesAreAllowlistedTest}, which
     * fails on one more OR one fewer. Without that test day 19 grows a nineteenth
     * {@code observed(} and nobody reads the diff.
     *
     * <p>Package-private is the coarse half of the guard and the test is the fine half. The coarse
     * half keeps the common case honest by making the cheap claim awkward; the fine half is what
     * actually holds, because a package boundary is not an approval.
     *
     * @param value the value, read directly this tick
     */
    static <T> Graded<T> observed(T value) {
        return new Graded<>(value, Belief.OBSERVED, null);
    }

    /**
     * A value derived from an observation, with the derivation named.
     *
     * <p>Existence over ceremony: the constructor already refuses a null {@code why}, so a caller
     * writing {@code new Graded<>(v, INFERRED, null)} gets an exception rather than a value that
     * looks gradeable and is not.
     */
    public static <T> Graded<T> inferred(T value, String why) {
        return new Graded<>(value, Belief.INFERRED, why);
    }

    /**
     * A value nobody could obtain, with the absence named.
     *
     * <p>The point of the third state is that a caller can branch on it, and a caller can only
     * branch on it if the value that carries it is present. {@code null} would say the same thing
     * as "I did not bother", which is the conflation this whole layer exists to remove.
     */
    public static <T> Graded<T> unknown(T value, String why) {
        return new Graded<>(value, Belief.UNKNOWN, why);
    }

    /**
     * Whether this grade permits acting on the value without looking again.
     *
     * <p>UNKNOWN answers false, and that is the whole point: an unread cell is neither walkable nor
     * standable, so a caller that only asks the practical question gets safe behaviour without
     * having to know the third state exists. Mirrors {@code BlockProbe.Solidity.holdsPlayerUp()}
     * and {@code isEmptySpace()}, deliberately, so the two answer the same question the same way.
     */
    public boolean mayActOn() {
        return belief != Belief.UNKNOWN;
    }
}
