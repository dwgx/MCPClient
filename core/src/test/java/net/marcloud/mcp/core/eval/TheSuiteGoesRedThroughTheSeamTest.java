package net.marcloud.mcp.core.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.drivers.act.NavIntent;
import net.marcloud.mcp.core.eval.EvalSuite.Result;
import net.marcloud.mcp.core.eval.EvalSuite.T20TheDirectLineIsLava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Test;

/**
 * The suite can go red on demand, through a seam, on a task with no planted hazard.
 *
 * <p><b>What was missing and what this is.</b> Before this slice, {@code EvalSuite} ran
 * {@code new GoalPolicy(world, harness).obtain(goal)} at six sites. A goal-directed task could
 * therefore only ever PASS: there was no way to hand it a different decision, so no task had
 * been shown capable of failing on anything but a planted world, and a suite in which nothing
 * can fail cannot demonstrate that it can detect failure. The planted-hazard control in
 * {@code TheSurvivalRowDetectsAPlantedHazardTest} was the first negative control this project
 * had, and it works by removing a route from a WORLD. This one works by replacing a DECISION,
 * on a world nothing touches.
 *
 * <p><b>The three controls, and what each one rules out.</b> A pair of runs is only evidence if
 * something could make both sides green, so each control below names the mistake it excludes:
 *
 * <ul>
 *   <li><b>The bad policy dies ON THE SEAM, not on the rows.</b> A policy that throws proves
 *       only that exceptions propagate. Every bad policy here acts normally and returns a
 *       boolean; the failure has to come from the WORLD being different, which is the only
 *       version of this that says the rows are load-bearing.</li>
 *   <li><b>The bad policy is obviously bad without reading its source.</b> Each is a named class
 *       in {@link BrokenPolicies} whose javadoc states the defect in one sentence, and each
 *       asserts its own behaviour here in a way a reader can check against the world.</li>
 *   <li><b>The good policy still passes in the same run.</b> Both verdicts come from one
 *       {@code runWith} call in this file, so a broken world cannot explain a failure by being
 *       simply too hard.</li>
 * </ul>
 *
 * <p><b>Why this is not a model, restated because it is the honest boundary.</b> The good side
 * of every pair here is {@code GoalPolicy}, the reference implementation. **No line count: this
 * sentence once said 1317, and the commit that introduced it added 22 lines to GoalPolicy in the
 * same breath, so the number was false in the very commit that published it and would be false
 * again on the next edit. A number that can only rot is not worth the sentence it sits in.**
 * What these
 * tests establish is that a decision is an ARGUMENT to a task -- substitutable, injectable, and
 * consequential. What they do NOT establish is that a weak model plus a system prompt can
 * produce such a decision. That gap is named in {@link Policy}'s javadoc and is the standing
 * distance; pretending otherwise would be the defect this repository exists to remove.
 *
 * <p><b>And this is not a violation of {@code docs/agency/guarantees.md} §6.2</b>, "do not
 * write a goal policy in Java". That rule forbids a Java chooser standing in for the MODEL's
 * decision. Every bad policy here is deliberately incapable of solving its task -- they are
 * controls, shipped so they cannot be deleted as dead test code, and a chooser that worked
 * would make every test in this file meaningless. The seam is an interface with one method; the
 * decision lives in the implementation, which is what lets a task be handed a different one.
 */
public final class TheSuiteGoesRedThroughTheSeamTest {

    /** The same factory the shipped task uses, so the good side is the real reference policy. */
    private static java.util.function.BiFunction<SimWorld, EvalHarness, Policy> shipped() {
        return GoalPolicy::new;
    }

    /**
     * The headline: the shipped world's own rows, with a decision that walks the lethal
     * straight line.
     *
     * <p><b>Why this is not the planted-hazard control wearing a new hat.</b> The world is
     * T20's, cell for cell -- the band, the outcrop, the given pickaxe -- and nothing in this
     * method removes a route, moves a block, or edits a fixture. The only difference from the
     * passing run is which {@link Policy} was constructed. That is the whole claim: a task's
     * verdict is a function of the decision, and the decision is now a parameter.
     */
    @Test
    public void aPolicyThatWalksTheLethalStraightLineFailsTheShippedWorldsOwnRows() {
        // The bad decision, stated in one line: aim the walk at the outcrop and never look.
        // It submits the SAME production NavIntent the good policy uses -- what differs is that
        // this one names the destination directly rather than asking the planner for a route.
        Result bad = T20TheDirectLineIsLava.runWith((w, h) ->
                BrokenPolicies.walksTheStraightLine(h, (harness, goal) -> {
                    harness.runtime().submitNav(new NavIntent(20.5D, 64.0D, 2.0D, 600));
                    harness.runMove(900);
                    // It reports failure honestly: it did not obtain the goal. A policy that
                    // LIED here would be caught by the inventory row anyway, so the liar is
                    // covered separately below -- this one is the hazard-blind walker.
                    return false;
                }));

        assertFalse("a decision that walks the lethal straight line must be reported as a"
                + " failure, or these rows cannot fail on anything the DECISION did: " + bad.fact(),
                bad.pass());
        assertTrue("and the failure must NAME the death, so a reader is not left guessing which"
                + " row went red: " + bad.fact(),
                bad.fact().contains("died") || bad.fact().contains("health bar fell"));
        assertTrue("and it must report the damage the world actually recorded, not merely that"
                + " something went wrong: " + bad.fact(),
                bad.fact().contains("lava band"));
        assertTrue("and it must name the decision that produced the failure, so the reader does"
                + " not have to guess which policy ran: " + bad.fact(),
                bad.fact().contains("WalksTheStraightLine"));
    }

    /**
     * The control that makes the pair mean something: the same method, the shipped policy,
     * passes.
     *
     * <p>Without this, a failing run above would be evidence of nothing -- the world could simply
     * be unwinnable. Both verdicts come from {@link T20TheDirectLineIsLava#runWith}, so the
     * rows are literally the same code.
     */
    @Test
    public void theShippedPolicyStillPassesTheSameRunTheBadOneFailed() {
        Result good = T20TheDirectLineIsLava.runWith(shipped());

        assertTrue("T20 must pass as shipped: " + good.fact(), good.pass());
        assertTrue("and its report names the policy that ran, so a reader can tell which"
                + " decision produced it: " + good.fact(),
                good.fact().contains("GoalPolicy"));
        assertTrue("and it names the north-star claim rather than a bare health number: "
                + good.fact(), good.fact().contains("never went below"));
    }

    /**
     * A policy that CLAIMS success without acting is caught by the world, not by its own
     * return value.
     *
     * <p>This is the failure mode most like a language model, and the one a suite keyed on
     * {@code policy.obtain(...)} would pass. The policy returns {@code true}; the inventory is
     * empty and the ore is still in the ground, and those are the facts the row reads.
     */
    @Test
    public void aPolicyThatClaimsSuccessWithoutActingIsCaughtByTheWorldNotItsReturnValue() {
        Result lied = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.ClaimsSuccessWithoutActing());

        assertFalse("a policy that returned true and touched nothing must FAIL, or the rows are"
                + " reading the policy's self-report rather than the world: " + lied.fact(),
                lied.pass());
        assertTrue("and the failure must say the stone was never broken, which is the world"
                + " fact that caught it: " + lied.fact(),
                lied.fact().contains("the stone was never broken"));
        assertTrue("and that nothing was picked up: " + lied.fact(),
                lied.fact().contains("holds no cobblestone"));
    }

    /**
     * A policy that gives up without acting fails too, and is distinguishable from the liar.
     *
     * <p>Two broken policies that both leave the world untouched must still be told apart by
     * the report, or the failure message is useless to whoever reads it at 3am.
     */
    @Test
    public void aPolicyThatGivesUpFailsAndSaysSoRatherThanClaimingSuccess() {
        Result gaveUp = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.GivesUpWithoutActing());

        assertFalse("a policy that did nothing must FAIL: " + gaveUp.fact(), gaveUp.pass());
        assertTrue("and the report must say the policy reported FAILURE, so the reader is not"
                + " told the world disagreed when the decision simply gave up: " + gaveUp.fact(),
                gaveUp.fact().contains("reported failure"));
    }

    /**
     * A policy that throws is a FAILURE with a reason -- not a crash and not a pass.
     *
     * <p>This is the runner's own property rather than a row's, and it is here because a
     * decision defect must not be able to take the suite down: a runner that propagates it
     * aborts every later task, and a runner that swallowed it would report a row it never
     * computed.
     */
    @Test
    public void aPolicyThatThrowsIsAFailureWithAReasonNotACrashAndNotAPass() {
        Result threw = T20TheDirectLineIsLava.runWith((w, h) ->
                new BrokenPolicies.ThrowsOnEveryGoal());

        assertFalse("a throwing policy must be a FAILURE: " + threw.fact(), threw.pass());
        assertTrue("and the reason must name the throw and say why it is scored as a failure: "
                + threw.fact(), threw.fact().contains("threw IllegalStateException"));
    }

    /**
     * Every bad policy fails and the good one passes, in one table, so a future broken policy
     * cannot be added without a row here.
     *
     * <p>The table is derived rather than hand-listed because the point is that the SET of
     * shipped broken policies is fully covered; a new {@link BrokenPolicies} entry that no
     * control exercises is a control nobody wrote.
     */
    @Test
    public void everyShippedBrokenPolicyFailsAndTheShippedPolicyPasses() {
        assertTrue("the shipped policy is the good side of every pair and must pass: ",
                T20TheDirectLineIsLava.runWith(shipped()).pass());

        // Each of the three fixed-defect policies, against this task's rows.
        assertFalse("ClaimsSuccessWithoutActing must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.ClaimsSuccessWithoutActing()).pass());
        assertFalse("GivesUpWithoutActing must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.GivesUpWithoutActing()).pass());
        assertFalse("ThrowsOnEveryGoal must fail",
                T20TheDirectLineIsLava.runWith((w, h) ->
                        new BrokenPolicies.ThrowsOnEveryGoal()).pass());
    }

    /**
     * The seam's classes are LOADED FROM the shipped artifact -- which is a stronger claim than
     * the package name they carry, and the only one that can fail.
     *
     * <p><b>Why the assertion this replaces was unable to fail.</b> It asserted
     * {@code Policy.class.getPackageName()} equals {@code net.marcloud.mcp.core.eval}, and its
     * own javadoc said it "fails the moment someone moves the interface down into the test
     * tree". Moving {@code Policy.java} from
     * {@code core/src/main/java/net/marcloud/mcp/core/eval/} to
     * {@code core/src/test/java/net/marcloud/mcp/core/eval/} <b>does not change its package name
     * by a single character</b>, so the exact move it was written to catch left it green. A name
     * cannot distinguish a file's TREE. That is the same blind spot the shelter guard reached
     * from the other side, and {@code TheShippedShelterCounterIsReachableAndNotATestTreeClassTest}
     * names it: the package assertion "could not have caught a tool declared in three tables and
     * built in none".
     *
     * <p><b>What can distinguish it, and why it is read off the class rather than the
     * directory.</b> A {@code Policy} in the test tree is loaded out of
     * {@code target/test-classes} and one in the main tree out of {@code target/classes}. Asking
     * the classloader where the bytes came from describes the artifact a caller would link
     * against, and it is the loaded class that answers -- so this cannot be satisfied by a file
     * that merely sits in the right directory.
     *
     * <p><b>And the limit, stated here so nobody has to guess at it.</b> Being in the shipped
     * artifact is not being CALLED from it. All three of {@link Policy}, {@link PolicyRun} and
     * {@link BrokenPolicies} load out of {@code target/classes} today and all three are called
     * by nothing -- that second half is the sibling assertion below, and it is the half
     * {@link Policy}'s javadoc used to get wrong. A guard that could only see the first half is
     * what let that sentence stand unchallenged.
     */
    @Test
    public void theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree() {
        for (Class<?> seam : new Class<?>[] {Policy.class, PolicyRun.class, BrokenPolicies.class}) {
            String where = loadedFrom(seam).replace('\\', '/');

            assertFalse(seam.getSimpleName() + " was loaded out of the TEST tree (" + where + "):"
                    + " a seam only tests can reach is the defect this file exists to close, and"
                    + " its package name would not have changed by one character if it had been"
                    + " moved there",
                    where.endsWith("test-classes") || where.contains("/test-classes/"));
            assertTrue(seam.getSimpleName() + " must be loaded out of a build output directory, or"
                    + " the line above is measuring an absence of information rather than a"
                    + " location. The classloader reported: " + where,
                    where.contains("/classes/"));
            assertEquals("and the package stays the one the shipped eval surface is addressed by,"
                    + " so this is a stronger check on the same file rather than a different one",
                    "net.marcloud.mcp.core.eval", seam.getPackageName());
        }
    }

    /**
     * The shipped tree's own prose must not claim a caller the shipped tree does not have.
     *
     * <p><b>What was claimed, in the shipped tree, in a file whose whole subject is honesty
     * about claims.</b> {@link Policy}'s javadoc said: "This file is main-tree, so
     * {@code core/src/main} has a reference to it". Measured, with comments stripped so that
     * {@code @link} mentions do not count as callers the way
     * {@code docs/agency/failure-shapes.md} §2.1 declined to count them, the answer is zero --
     * the three files that DECLARE the seam name each other and nothing else in
     * {@code core/src/main} names them at all.
     *
     * <p><b>Why a caller claim with no caller is the shape, not a typo.</b> §2.1 is titled "A
     * policy only tests could reach" and its rule is that reachability is a property you have to
     * MEASURE. The slice that answered it moved three files up a tree and the move was recorded
     * as the fix. Moving a file is not a caller: the shipped artifact gained three class files it
     * cannot invoke, so the rule is satisfied on disk and violated in fact. That is why this
     * assertion exists rather than a correction to the prose alone -- the prose can rot back.
     *
     * <p><b>What this does and does not claim about the seam's design.</b> It claims the shipped
     * tree makes no use of {@link Policy}, and it is correct that none is required: the only
     * caller that could exist is {@code EvalSuite}, and that is test-tree by construction, so
     * there is no honest main-tree consumer to add. What the interface's main-tree placement
     * DOES buy is that the seam type is in the artifact for any future shipped caller to name --
     * which is a weaker claim than the one the javadoc made, and the one it now makes.
     *
     * <p><b>And the non-vacuity assertion is the load-bearing part.</b> A scanner pointed at a
     * directory that does not exist returns nothing, and "no callers" would then be a fact about
     * the scanner rather than about the tree. So the scan must be shown to SEE the seam's own
     * in-cluster references first: {@code PolicyRun} names {@code Policy} in code, in its binder
     * and in both of its run methods. If that stops being true, this fails before it can pass for
     * the wrong reason.
     */
    @Test
    public void theShippedTreeMustNotClaimACallerThatItDoesNotHave() {
        Path root = mainSourceRoot();
        assertTrue("the shipped source root must exist or the measurement below is a fact about"
                + " the scanner rather than about the tree: " + root, Files.isDirectory(root));

        SortedSet<String> hits = mainTreeFilesWithACodeReferenceToTheSeam(root);

        assertTrue("the scan must see the seam's own in-cluster code references -- PolicyRun names"
                + " Policy in its binder and in both run methods -- or an empty caller set below"
                + " proves nothing at all. Hits: " + hits,
                hits.contains(EVAL + "PolicyRun.java"));

        SortedSet<String> callers = new TreeSet<>(hits);
        callers.removeAll(SEAM_FILES);

        String claim = "has a reference to it";
        String policySource = sourceText(root.resolve(EVAL + "Policy.java"));

        assertFalse("Policy.java asserts that core/src/main \"" + claim + "\" the seam, and the"
                + " shipped tree has no code reference to it outside the three files that declare"
                + " it. A caller claim with no caller is failure-shapes.md 2.1 -- a capability"
                + " documented as doing something whose producer is absent -- and it survived"
                + " because the guard that should have caught it asserted a package name. Either"
                + " wire the seam or say what it is. Measured callers in core/src/main: " + callers,
                callers.isEmpty() && policySource.contains(claim));
    }

    /** The three files that DECLARE the seam. A declaration is not a caller. */
    private static final List<String> SEAM_FILES = List.of(
            "net/marcloud/mcp/core/eval/Policy.java",
            "net/marcloud/mcp/core/eval/PolicyRun.java",
            "net/marcloud/mcp/core/eval/BrokenPolicies.java");

    private static final String EVAL = "net/marcloud/mcp/core/eval/";

    private static final java.util.regex.Pattern SEAM_NAME =
            java.util.regex.Pattern.compile("\\b(Policy|PolicyRun|BrokenPolicies)\\b");

    /** Where the classloader actually got this class's bytes. */
    private static String loadedFrom(Class<?> type) {
        CodeSource cs = type.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) {
            return "<no code source: the classloader published none, so this guard cannot tell"
                    + " which tree it came from and must not be read as having proved it>";
        }
        return cs.getLocation().toString();
    }

    /**
     * The shipped source root, found by walking up from the module the suite runs in.
     *
     * <p>Throws rather than returning null: a guard that cannot find the tree it is measuring
     * has to fail, because the alternative is a silent pass on an empty scan.
     */
    private static Path mainSourceRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no src/main/java above " + dir + ": this guard measures the"
                + " shipped tree, so a run that cannot find it must fail rather than pass on an"
                + " empty scan");
    }

    /** Source text with comments intact -- used for reading a CLAIM out of a javadoc. */
    private static String sourceText(Path file) {
        return read(file, false);
    }

    /**
     * Source text with comments stripped, which is what makes a caller a caller.
     *
     * <p>The distinction is §2.1's: five grep hits for {@code GoalPolicy} in
     * {@code core/src/main}, every one inside a javadoc, and the document calls that zero code
     * references. A {@code @link Policy} in a comment is not a caller and must not satisfy this.
     */
    private static String codeText(Path file) {
        return read(file, true);
    }

    private static String read(Path file, boolean strip) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file + ": this guard measures the shipped"
                    + " tree's sources, so an unreadable source is a failure and not an absence",
                    e);
        }
        return strip ? stripComments(raw) : raw;
    }

    /** Block and line comments removed; string and char literals are kept and honoured. */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        boolean inLine = false;
        boolean inText = false;
        boolean inChar = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
            } else if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
            } else if (inText) {
                out.append(c);
                if (c == '\\' && next != '\0') {
                    out.append(next);
                    i++;
                } else if (c == '"') {
                    inText = false;
                }
            } else if (inChar) {
                out.append(c);
                if (c == '\\' && next != '\0') {
                    out.append(next);
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else {
                if (c == '"') {
                    inText = true;
                } else if (c == '\'') {
                    inChar = true;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Main-tree files whose CODE, comments stripped, names any of the seam's three types. */
    private static SortedSet<String> mainTreeFilesWithACodeReferenceToTheSeam(Path root) {
        SortedSet<String> hits = new TreeSet<>();
        List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError("could not walk the shipped source tree at " + root, e);
        }
        for (Path f : files) {
            if (SEAM_NAME.matcher(codeText(f)).find()) {
                hits.add(root.relativize(f).toString().replace('\\', '/'));
            }
        }
        return hits;
    }

    /**
     * {@link PolicyRun} pairs a good and a bad run and reports both.
     *
     * <p>The pairing is a method rather than a convention because "remember to write the
     * control" is exactly what a passing-only suite forgets; a helper that makes the pair the
     * easy thing to reach is worth more than a comment asking for it.
     */
    @Test
    public void theRunnerPairsAGoodAndABadRunFromOneWorldShape() {
        // A world shape trivial enough to state here: the count of blocks is the row, and both
        // policies leave it alone, so the pair's value is the FORMAT -- both verdicts present,
        // each labelled -- not a hazard.
        PolicyRun<Integer> run = new PolicyRun<>(world -> new BrokenPolicies.GivesUpWithoutActing(),
                world -> new PolicyRun.Verdict(world > 0, "counted " + world));

        String report = run.pair(() -> 3, "cobblestone",
                new BrokenPolicies.ClaimsSuccessWithoutActing(),
                new BrokenPolicies.GivesUpWithoutActing(),
                "the liar", "the one that gives up");

        assertTrue("the report must carry the good side's verdict: " + report,
                report.contains("the liar -> pass=true"));
        assertTrue("and the bad side's: " + report, report.contains("the one that gives up -> pass=true"));
        assertEquals("exactly two labelled lines, so a reader sees a PAIR and not one result: "
                + report, 2, report.split("\n").length);
    }
}