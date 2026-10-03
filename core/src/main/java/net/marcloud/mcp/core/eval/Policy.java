package net.marcloud.mcp.core.eval;


/**
 * The thing the eval runs in place of a model, and the seam a deliberately broken one is
 * injected through.
 *
 * <p><b>Why this exists and what it is not.</b> Before this interface, every goal-directed row
 * in {@code EvalSuite} called {@code new GoalPolicy(world, harness).obtain(goal)} directly --
 * a {@code public final} class in the test tree, constructed at eleven sites, with no way to
 * stand anything else in its place. So every task could only ever pass, no task had been
 * shown capable of failing, and a suite in which nothing can fail cannot demonstrate that it
 * can detect failure. This is that hole, named.
 *
 * <p><b>What it deliberately is NOT.</b> It is not a model, and this javadoc is the place to
 * say so rather than let the interface imply it. A policy is handed a goal STRING and returns
 * a boolean; a model is handed a world and a system prompt and emits tool calls one at a time
 * from whatever it can observe. The two differ in every way that matters for the question this
 * project exists to answer:
 *
 * <ul>
 *   <li>a policy is told the goal and nothing about the world; a model has to read
 *       {@code describe} / {@code act_status} to learn anything at all;</li>
 *   <li>a policy cannot be wrong in the way a language model is wrong -- it has no
 *       misreading of a tool description, no confident hallucinated coordinate, no forgetting
 *       what it did forty turns ago;</li>
 *   <li>a policy's step count is a property of the recipe table, not of anything the caller
 *       had to remember.</li>
 * </ul>
 *
 * <p>So a PASS here is evidence that <b>given a decision, do the production controllers execute
 * it against a real world?</b> That is a real and necessary claim -- it is the composition
 * claim, and it is what localises a failure to a controller rather than to a planner. It is
 * <b>NOT</b> evidence that a weak model plus a system prompt could produce the decision, and
 * no test in this repository measures that. The distance between the two is stated, not
 * papered over: see {@code TheSuiteGoesRedThroughTheSeamTest} for the control this interface
 * makes possible and {@code .ai-notes/docs/audits/} for the standing gap.
 *
 * <p><b>Why it lives in {@code core/src/main} rather than beside {@code GoalPolicy}, stated as
 * what that does and does not buy.</b> A seam defined in the test tree could only ever be
 * substituted from the test tree, which would have reproduced in this slice the exact failure the
 * slice exists to close -- a capability whose producer only tests can reach. So this file is
 * main-tree, and {@code EvalSuite} does not own the decision.
 *
 * <p><b>An earlier draft of this paragraph said "so {@code core/src/main} has a reference to
 * it", and that was false.</b> Measured on the tree this file shipped in, with javadoc mentions
 * excluded the way {@code docs/agency/failure-shapes.md} §2.1 excludes them, {@code
 * core/src/main} names {@link Policy} in code ZERO times outside this file's own cluster --
 * {@link PolicyRun} and {@link BrokenPolicies}, which declare and implement it. There is no
 * caller. The sentence asserted a reachability that moving a file does not create, which is §2.1
 * under a new name: reachability is a property you have to measure.
 *
 * <p><b>So the honest version of the claim is narrower, and it is the one worth having.</b> The
 * interface TYPE is in the shipped artifact, so any future main-tree caller can name it and
 * substitute a decision without this seam being moved first. That is what main-tree placement
 * buys. What it does not buy is a caller, and today there is no honest way to add one: the only
 * code that runs a policy is {@code EvalSuite}, which is test-tree by construction, so a
 * main-tree consumer would have to be invented rather than found. The test
 * {@code TheSuiteGoesRedThroughTheSeamTest} asserts both halves separately -- that these classes
 * load out of the shipped artifact, and that no main-tree file claims a caller it does not have.
 * (Named in {@code @code} and not {@link}: this file is main-tree and that class is test-tree,
 * and a javadoc link between them would be the very dependency this placement keeps
 * one-directional.)
 *
 * <p><b>Why there is no {@code trace()} here.</b> The decision's own words are how a reader
 * judges whether the policy reasoned or flailed, and the reference implementation has them.
 * Adding an accessor to the interface would mean every implementation carries a transcript
 * whether or not it has one; the tasks that want a transcript ask the implementation for it.
 *
 * <p><b>And {@code docs/agency/guarantees.md} §6.2, "do not write a goal policy in Java".</b>
 * This interface is not a chooser and implements none of one: it declares a single method
 * taking a goal string, and every decision about how to satisfy it lives in the
 * implementation, not here. The rule exists because a Java chooser standing in for the
 * MODEL's decision would make the acceptance criterion untestable. An interface that lets a
 * caller CHOOSE the decision is the opposite of that, and what it makes testable is the half
 * that was missing: whether the controllers and the rows notice a decision being wrong.
 */
public interface Policy {

    /**
     * Try to hold {@code item}, however many steps that takes.
     *
     * <p>The one method, because one method is the whole contract and a wider one would be a
     * policy API nobody needs. Implementations decide everything else: which blocks to mine,
     * which recipes to craft, whether to dig a shelter, when to give up.
     *
     * @param item the item name, as the recipe table and the drop table spell it
     * @return whether the policy believes the item is in hand when it returns. A caller that
     *         wants to know WHY reads the world, not this boolean: {@code true} with an empty
     *         inventory is a lie the world contradicts, and the eval's own rows are world facts
     *         for exactly that reason.
     */
    boolean obtain(String item);
}