package net.marcloud.mcp.core.util;

/**
 * How a value was obtained, as a value rather than as prose.
 *
 * <p><b>Why this exists.</b> A belief is only meaningful once something is choosing. Until today
 * nothing in {@code core/src/main} carried one: {@code BlockProbe.Solidity} answers in three states
 * but only about ONE read, {@code LocalGrid.WALK_UNKNOWN} and {@code BlockView.WALK_UNKNOWN} each
 * encode the same third state for their own reader, and none of the three ever flowed back to a
 * decision. The shape has therefore been written three times in this repository and all three
 * stopped on the observation side. This is the downstream consumer they were missing, and the
 * reason it is a separate type rather than a fourth {@code Solidity} constant is that
 * {@code Solidity} is already three-valued: a second encoding of the same three states is how two
 * taxonomies come to disagree.
 *
 * <p><b>Exactly three constants, and the rejections are recorded here so they survive.</b> A fourth
 * was considered and each candidate is refused for a reason that is still true:
 *
 * <ul>
 * <li><b>Not coarser ({@code KNOWN} / {@code UNKNOWN}).</b> That folds UNKNOWN into INFERRED, so
 * "I inferred it" and "nobody looked" become one value -- and they require OPPOSITE behaviour.
 * INFERRED may act: weak grounds, but grounds. UNKNOWN must refuse or go load the chunk first.
 * {@code BlockProbe.Solidity.isEmptySpace()} (BlockProbe.java:62-72) already states this in other
 * words: an unread cell is neither walkable nor standable, so a caller asking only those two
 * questions gets safe behaviour without knowing a third state exists. Two values lose that.
 * <li><b>{@code STALE} -- rejected.</b> Staleness is a property of the VALUE (a tick stamp), not of
 * how it was obtained. {@code SlotRecord} already carries {@code submittedTick} /
 * {@code effectiveTick} / {@code lastAppliedTick} (SlotRecord.java:31-40); a STALE constant would
 * give one thing two sources of truth, and the two could disagree.
 * <li><b>{@code PARTIAL} -- rejected.</b> It means "sampled rather than exhaustive".
 * {@code NavHazard.java:14-17} already says in prose "Sampled, not exhaustive", and it changes no
 * caller's behaviour: nobody changes their mind because a line sampled 48 cells rather than 49. A
 * case that changes no behaviour is decoration, and decoration is the specific failure this layer
 * has to avoid being.
 * <li><b>{@code CONTRADICTED} -- rejected.</b> It needs cross-tick comparison, and this layer holds
 * no history. A value that would need yesterday's read to grade is not gradeable here.
 * </ul>
 *
 * <p><b>No numeric confidence, ever.</b> An uncalibrated {@code double} gets consumed downstream as
 * a probability and is not one. Three enum constants cannot be mistaken for an ordering, and nothing
 * in this repository has ever measured a calibration against a server that will not report one.
 *
 * <p><b>What this is not.</b> Not attestation. This layer is a measurement of what THIS client could
 * see; it says nothing about what the server thinks, because the 1.8.9 protocol carries no such
 * signal back. It is also not humanisation, and must not become it: a human player and an agent
 * reading the same public state hold exactly the same OBSERVED set.
 *
 * @see Graded
 */
public enum Belief {

    /**
     * Read this tick, from a live vanilla field, with no intermediary and nothing unexamined behind
     * it.
     *
     * <p>The strongest claim available here, and still only about the CLIENT's copy: the server
     * re-simulates and may pull us back. {@code posX} observed is "where I claim to be", not "where I
     * am".
     */
    OBSERVED,

    /**
     * Derived from an observation, by a derivation that is honest about what it assumed.
     *
     * <p>The middle value exists because collapsing it is the specific lie this layer is for. An
     * action built on a belief declared incomplete is a real, shippable thing -- the crit window is
     * one, and its own javadoc says so (CritWindow.java:26-30) -- and the defect is not that it is
     * an inference. The defect is that nothing typed it as one.
     */
    INFERRED,

    /**
     * The answer is not about the world at all. Nobody looked, or the look could not be made.
     *
     * <p>Must be handled differently from {@link #INFERRED}, or the layer is decoration. The shape
     * already exists and is correct: {@code BlockProbe.Solidity.UNKNOWN} answers false to both
     * {@code isEmptySpace()} and {@code holdsPlayerUp()}, so a caller that only asks the two
     * practical questions behaves safely without knowing the third state is there.
     */
    UNKNOWN
}
