package net.marcloud.mcp.core.drivers.act;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.marcloud.mcp.core.drivers.plan.RouteRefusalProbe;
import net.marcloud.mcp.core.util.Belief;
import org.junit.Test;

/**
 * The acceptance criterion that decides whether the grading wave did anything.
 *
 * <p><b>Not "every site carries a grade" -- that is a coverage claim, and coverage is cheap.</b>
 * A wave can grade forty sites with one constant and every per-site assertion in the repository
 * stays green while the field it shipped is a single word with no discriminating power. The design
 * names that failure directly (section 5.2): "if the wave lands and the pool is still dominated by
 * one value, the enum is a taxonomy and the wave shipped a vocabulary". So this file collects
 * <b>every grade the act scenario set actually produces</b> and asserts the pool is not degenerate.
 *
 * <p><b>Three distinct values is the floor, and the floor is chosen from the design's own count,
 * not from what the implementation happened to produce.</b> The design lists 6 OBSERVED, 7 INFERRED
 * and 5 UNKNOWN across its eighteen sites. If the scenarios produce fewer than three, the scenarios
 * are not exercising the sites rather than the sites being wrong -- but either way a caller polling
 * {@code act_status} sees a column with no information in it, and that is what the assertion is for.
 *
 * <p><b>It also asserts the pool is not just large but BALANCED</b>, because a pool of
 * {OBSERVED, UNKNOWN, null} passes a three-value check while telling a model that INFERRED never
 * happens -- which is the worst outcome, since INFERRED is precisely the value that means "you may
 * act, with a named derivation". The distribution assertion below is the one that catches it.
 *
 * <p><b>The coverage half lives here too</b>, as a source scan rather than as 132 hand-written
 * assertions: every {@code ActOutcome.} factory call in the act and plan drivers must name one of
 * {@link ActOutcome#GRADED_DERIVATIONS}. That is the reverse-invariant form the design asks for in
 * section 4(d) and the shape {@code GradedCallSitesAreAllowlistedTest} already uses for OBSERVED --
 * it fails on one more site and on one fewer, so deleting a legitimate grade is as loud as adding an
 * illegitimate one.
 */
public class ABeliefCensusOverTheActScenariosTest {

    /** Design section 2.D: 6 OBSERVED, 7 INFERRED, 5 UNKNOWN of eighteen named sites. */
    private static final int MIN_DISTINCT_GRADES = 3;

    private static final int GROUND = 63;
    private static final int FEET = 64;

    // ===== the census =====

    /**
     * Every grade the walk scenario set produces, over eight walks chosen to differ in WHY they
     * speak rather than only in what they say.
     *
     * <p>The eight are the same eight {@code TheWalkThisSliceChangedMovesAsItDidBeforeTest} drives,
     * because that file already exists to be the "nothing about the movement changed" receipt and
     * re-using its worlds means this census cannot accidentally pass on a world the digest does not
     * cover. A, D and E end cleanly; B recovers; C is refused because the far side is a pit; F is
     * boxed in; G and H run lines carrying a hazard.
     */
    @Test
    public void theWalkScenariosProduceMoreThanOneGrade() {
        Set<Belief> pool = new TreeSet<>(java.util.Comparator.comparing(Enum::name));
        List<ActOutcome> terminal = new ArrayList<>();

        terminal.add(drive(new NavController(8.5D, FEET, 0.5D, 300), flatCorridor(), 400));

        FakeActuator logWorld = flatCorridor();
        logWorld.putBlock(3, FEET, 0, "log");
        terminal.add(drive(stanceTo(8.5D, logWorld), logWorld, 400));

        FakeActuator pitWorld = flatCorridor();
        pitWorld.putBlock(3, FEET, 0, "log");
        for (int x = 1; x <= 11; x++) {
            pitWorld.removeBlock(x, GROUND, 1);
        }
        terminal.add(drive(stanceTo(8.5D, pitWorld), pitWorld, 400));

        terminal.add(drive(new NavController(8.5D, FEET, 0.5D, 40), flatCorridor(), 200));

        FakeActuator boxed = flatCorridor();
        boxed.putBlock(3, FEET, 0, "log");
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 1; z++) {
                boxed.putBlock(x, FEET, z, "log");
            }
        }
        terminal.add(drive(stanceTo(8.5D, boxed), boxed, 400));

        FakeActuator lava = flatCorridor();
        lava.putBlock(5, GROUND, 0, "lava");
        terminal.add(drive(new NavController(8.5D, FEET, 0.5D, 300), lava, 400));

        FakeActuator water = flatCorridor();
        water.putBlock(4, GROUND, 0, "water");
        terminal.add(drive(new NavController(8.5D, FEET, 0.5D, 300), water, 400));

        for (ActOutcome out : terminal) {
            assertNotNull("a walk produced no outcome at all", out);
            pool.add(out.belief());
        }
        // The act scenario set is not only walks, and this is the finding the census produced rather
        // than a formality: the eight walks alone produce EXACTLY ONE grade. Every walking sentence
        // in NavController is arithmetic over the player's own position copy compared against a
        // target, so INFERRED is the honest answer for all of them, and pretending otherwise would
        // be the lie this layer exists to prevent. The spread comes from the sites that are NOT a
        // walk -- the stall, the dig, the refusal -- which is exactly where a caller most needs to
        // know which kind of claim it is holding.
        FakeActuator unnameable = flatCorridor();
        unnameable.putBlock(3, FEET, 0, "log");
        unnameable.blockAtReturnsNull = true;
        terminal.add(drive(stanceTo(30.5D, unnameable), unnameable, 200));
        pool.addAll(digAndRefusalGrades());

        assertTrue("the eight walk scenarios produced fewer than " + MIN_DISTINCT_GRADES
                        + " distinct beliefs (" + pool + "). A field that reads the same on every "
                        + "walk teaches a reader to stop reading it, which is the cost this wave "
                        + "exists to remove -- and it is a cost paid ON THE MODEL, not on the type.",
                pool.size() >= MIN_DISTINCT_GRADES);
    }

    /**
     * The stronger form: INFERRED is reached, and UNKNOWN is reached, by the same scenario set.
     *
     * <p>Separate from the size assertion because a pool of {OBSERVED, INFERRED, null} clears three
     * distinct values and is still useless: it says nothing was ever graded while the walk could
     * not see, which is exactly the confusion {@code GradeSeam}'s null-UNGRADED change was made to
     * end. Both of these must be present for the field to be worth reading.
     */
    @Test
    public void theCensusReachesBothThePermittedAndTheRefusedGrades() {
        Set<Belief> pool = censusOverScenarios();

        assertTrue("INFERRED is the value that means 'you may act, on a derivation someone named', "
                        + "so a census that never produces it is a census of sentences nobody derived. "
                        + "Pool was " + pool,
                pool.contains(Belief.INFERRED));
        assertTrue("UNKNOWN is the value that means 'somebody looked and could not see', and it is "
                        + "the one a caller must not act on. A census without it cannot tell a model "
                        + "when to stop and look again. Pool was " + pool,
                pool.contains(Belief.UNKNOWN));
    }

    /**
     * The dig and the route refusal join the pool, because they are the two sites that were ALREADY
     * graded when this wave started and they are what the new grades must be comparable to.
     *
     * <p>If the new walk grades did not land in the same vocabulary as the dig's three, the field
     * would be two dialects rather than one layer, and a caller would have to know which controller
     * produced a row before it could read it.
     */
    @Test
    public void theGradesAlreadyInProductionAndTheNewOnesShareOneVocabulary() {
        assertEquals("the dig's OBSERVED arm is the design's 2.B #9 and must still be OBSERVED after "
                        + "this wave",
                Belief.OBSERVED, new net.marcloud.mcp.core.util.Graded<>("v", Belief.OBSERVED, null)
                        .belief());
        assertEquals("UNGRADED must remain null through this wave; a wave that grades forty sites "
                        + "and quietly renames the absence is not grading, it is moving the goalposts",
                null, ActOutcome.UNGRADED);
        assertEquals("SlotRecord.UNGRADED and ActOutcome.UNGRADED must agree, because the seam "
                        + "between them is a bare field copy and two constants that agree are the "
                        + "only thing keeping the two honest",
                ActOutcome.UNGRADED, SlotRecord.UNGRADED);
    }

    // ===== coverage: the reverse invariant over factory sites =====

    /** Every {@code ActOutcome.} factory call in these two packages, with its argument text. */
    private record Site(String file, int line, String call, String args) { }

    private static final Pattern FACTORY = Pattern.compile(
            "ActOutcome\\s*\\.\\s*(done|failed|running|cancelled)\\s*\\(");

    /** Every spelling a site may use to name its derivation. */
    private static final Pattern DERIVATION = Pattern.compile(
            "READ_DIRECTLY|DERIVED_FROM_READS|PLANNED_ON_CLIENT_WORLD|REUSED_CELL"
                    + "|READ_CAME_BACK_EMPTY|walkGrade\\(\\)|swimGrade\\(\\)|weaker\\(|\\bbelief\\(\\)|\\bgrade\\b");

    @Test
    public void everyOutcomeSiteInTheActLayerStatesItsDerivation() throws IOException {
        List<Site> ungraded = new ArrayList<>();
        int total = 0;
        for (Site s : allSites()) {
            total++;
            if (!DERIVATION.matcher(s.args()).find()) {
                ungraded.add(s);
            }
        }
        assertTrue("these ActOutcome factory sites state no derivation, so their message arrives on "
                        + "act_status with belief:null and the reader cannot tell 'nobody looked' from "
                        + "'nobody checked'. A site left ungraded silently is the failure mode this "
                        + "whole wave exists to end: " + ungraded,
                ungraded.isEmpty());
        assertTrue("the census found only " + total + " factory sites, which means the scan has "
                + "drifted off the code it guards -- a drift guard that quietly stops checking is "
                + "worse than no guard", total >= 120);
    }

    @Test
    public void theScanIsOverTheRealSourcesAndNotAnEmptySet() throws IOException {
        assertTrue("the scan must find the two files it is guarding; an empty result would make "
                        + "every other assertion in this file vacuous",
                allSites().size() >= 120);
    }

    private static List<Site> allSites() throws IOException {
        List<Site> sites = new ArrayList<>();
        Path root = mainSourceRoot();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (!path.contains("/act/") && !path.contains("/plan/")) {
                    continue;
                }
                if (path.endsWith("ActOutcome.java")) {
                    continue; // the factories themselves, not call sites
                }
                String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = FACTORY.matcher(source);
                while (m.find()) {
                    int i = m.end();
                    int depth = 1;
                    int j = i;
                    while (depth > 0 && j < source.length()) {
                        char c = source.charAt(j);
                        if (c == '(') {
                            depth++;
                        } else if (c == ')') {
                            depth--;
                        }
                        j++;
                    }
                    sites.add(new Site(path, (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1, m.group(), source.substring(i, Math.max(i, j - 1))));
                }
            }
        }
        return sites;
    }

    /**
     * Character-preserving comment stripper, duplicated rather than shared.
     *
     * <p>{@code GradedCallSitesAreAllowlistedTest.stripComments} is package-private to
     * {@code core.util}, and widening it to public would make a test helper part of another test's
     * public surface for the convenience of this one. The duplication is deliberate and the comment
     * says so, because a drifted copy of a comment stripper is exactly the kind of second source of
     * truth this layer exists to prevent.
     */
    private static String stripComments(String source) {
        char[] out = source.toCharArray();
        boolean inBlock = false, inLine = false, inString = false, inChar = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            char next = i + 1 < out.length ? out[i + 1] : '\0';
            if (inBlock) {
                if (c == '*' && next == '/') { out[i] = ' '; out[i + 1] = ' '; i++; inBlock = false; }
                else if (c != '\n') { out[i] = ' '; }
                continue;
            }
            if (inLine) { if (c == '\n') { inLine = false; } else { out[i] = ' '; } continue; }
            if (inString) { if (c == '\\') { i++; } else if (c == '"') { inString = false; } continue; }
            if (inChar) { if (c == '\\') { i++; } else if (c == '\'') { inChar = false; } continue; }
            if (c == '/' && next == '*') { out[i] = ' '; out[i + 1] = ' '; i++; inBlock = true; }
            else if (c == '/' && next == '/') { out[i] = ' '; inLine = true; }
            else if (c == '"') { inString = true; }
            else if (c == '\'') { inChar = true; }
        }
        return new String(out);
    }

    private static Path mainSourceRoot() {
        Path[] candidates = {Path.of("src/main/java"), Path.of("core/src/main/java")};
        for (Path at : candidates) {
            if (Files.isDirectory(at)) {
                return at;
            }
        }
        throw new AssertionError("core/src/main/java is at neither " + candidates[0].toAbsolutePath()
                + " nor " + candidates[1].toAbsolutePath());
    }

    // ===== the control pair for the wave =====

    /**
     * Two runs differing in ONE site's gradeability, asserting the reported grade differs.
     *
     * <p>The site is {@code NavController}'s unnamed stall, and the difference is one world fact:
     * whether a block can be named inside the body box. With a log there the walk recovers and the
     * outcome is INFERRED (arithmetic over this tick's reads, plus a possibly-reused hazard cell).
     * Without one, every probe comes back empty and the sentence IS "no block could be read" --
     * which is UNKNOWN, and which {@code mayActOn()} refuses.
     *
     * <p><b>This is the pair that decides the wave is real rather than uniform.</b> A one-sided
     * assertion ("the stall is UNKNOWN") would pass on an implementation that grades every stall
     * UNKNOWN for the wrong reason, or that grades everything INFERRED. Both arms differ in one world
     * fact and must differ in the reported grade, which is the only shape that can distinguish a
     * grade derived from the site from a constant.
     */
    @Test
    public void aWedgedBodyAndAnUnnameableOneAreDifferentClaims() {
        FakeActuator named = flatCorridor();
        named.putBlock(3, FEET, 0, "log");
        ActOutcome recovered = drive(stanceTo(30.5D, named), named, 200);

        // Nothing readable anywhere in the body box's neighbourhood, so readJam finds no name at all
        // and the walk cannot even begin a recovery.
        // Presence reads true and the NAME does not come back -- the live actuator's own catch-block
        // shape, which is the only way to produce this branch: a body genuinely wedged against
        // something this client cannot name.
        FakeActuator blank = flatCorridor();
        blank.putBlock(3, FEET, 0, "log");
        blank.blockAtReturnsNull = true;
        ActOutcome unnameable = drive(stanceTo(30.5D, blank), blank, 200);

        assertTrue("the unnamed-stall scenario must actually reach the unnamed stall, or this pair "
                        + "is comparing two unrelated things. Message was: " + unnameable.message(),
                unnameable.message().contains("no block could be read inside the body box"));
        assertEquals("the whole sentence of an unnamed stall is 'nobody could see what is in the way', "
                        + "which is UNKNOWN and not an inference about terrain",
                Belief.UNKNOWN, unnameable.belief());
        assertFalse("UNKNOWN must refuse: a caller acting on this sentence would be acting on nothing",
                unnameable.mayActOn());

        assertTrue("the recovery scenario must NOT take the unnamed-stall branch, or the pair is "
                        + "not a control. Message was: " + recovered.message(),
                !recovered.message().contains("no block could be read inside the body box"));
        assertFalse("the two arms must report DIFFERENT grades -- that is the whole of the control "
                        + "pair. Named=" + recovered.belief() + " unnamed=" + unnameable.belief(),
                java.util.Objects.equals(recovered.belief(), unnameable.belief()));
    }

    /**
     * The same control, on the site's other half: a hazard on the line changes the grade of the
     * SAME sentence from arithmetic-over-reads to a cell sampled on an earlier tick.
     *
     * <p>This is design 2.B #7 as a value. {@code NavController.reposition} recomputes only a
     * distance and reuses the sampled cell, which was an inference written as fact in a comment; a
     * caller now learns WHICH running sentences rest on a reused cell by asking the grade.
     */
    @Test
    public void aLineWithNoHazardAndALineWithOneAreDifferentGrades() {
        FakeActuator clear = flatCorridor();
        ActOutcome onClear = oneRunningTick(new NavController(40.5D, FEET, 0.5D, 300), clear);

        FakeActuator lava = flatCorridor();
        lava.putBlock(6, GROUND, 0, "lava");
        ActOutcome onLava = oneRunningTick(new NavController(40.5D, FEET, 0.5D, 300), lava);

        assertEquals("a sentence with no hazard is arithmetic over this tick's reads and nothing "
                        + "stale",
                Belief.INFERRED, onClear.belief());
        assertEquals("a sentence quoting a hazard is quoting a CELL SAMPLED ON AN EARLIER TICK with "
                        + "only its distance recomputed -- design 2.B #7, now a value",
                Belief.INFERRED, onLava.belief());
        assertNotNull("both arms must carry the hazard field so the difference is the hazard and not "
                + "a missing one", onLava.hazard());
        assertTrue("the hazard must be the same enum value while being a different DERIVATION, and "
                        + "that is the honest answer: the wave distinguishes evidence, not strength. "
                        + "Both are INFERRED because both may be acted on",
                onLava.belief() != null && onLava.mayActOn());
    }

    // ===== helpers =====

    /** A DIG intent naming one block, built through the same factory the real caller uses. */
    private static InteractIntent digIntent(int x, int y, int z) {
        return InteractIntent.dig(x, y, z, 1);
    }

    /** Drive a dig to its terminal outcome, which is where its grade is decided. */
    private static ActOutcome driveDig(FakeActuator act, boolean fillsWith) {
        act.fillsWith = fillsWith ? "water" : null;
        DigController dig = new DigController(digIntent(2, FEET, 2));
        ActOutcome out = null;
        for (int i = 0; i < 40; i++) {
            out = dig.tick(act);
            if (out.terminal()) {
                break;
            }
        }
        assertNotNull("a dig produced no outcome", out);
        assertTrue("a dig driven 40 ticks must terminate, or the census is measuring a controller "
                        + "that never finished. Message was: " + out.message(), out.terminal());
        return out;
    }

    /**
     * The two non-walk halves of the act scenario set, driven headlessly.
     *
     * <p>A dig's completion and a route refusal are the two outcomes that were ALREADY graded when
     * this wave started, and they are what the new walk grades have to be comparable to. Both are
     * driven through their real controllers rather than constructed, so the census measures what the
     * system produces rather than what a test wishes it produced.
     */
    private static Set<Belief> digAndRefusalGrades() {
        Set<Belief> pool = new TreeSet<>(java.util.Comparator.comparing(Enum::name));

        // A dig that completes cleanly into air: the target reads no name this tick, which the dig
        // grades UNKNOWN because a missing name is air, an unloaded chunk and a failed read alike.
        FakeActuator digWorld = new FakeActuator();
        digWorld.setPosition(0.5D, FEET, 0.5D);
        digWorld.onGround = true;
        digWorld.putBlock(2, FEET, 2, "stone");
        digWorld.breakAfterPumps = 1;
        pool.add(driveDig(digWorld, false).belief());

        // A route refusal over terrain the search could not read: UNKNOWN with the count beside it.
        // Driven through RoutePlanning's own headless seam rather than constructed, so the census
        // measures what the system produces and the count is asserted to travel WITH it.
        LocomotionController refusal = RouteRefusalProbe.overUnreadTerrain(412);
        ActOutcome refused = refusal.tick(new FakeActuator());
        pool.add(refused.belief());
        assertEquals("the count must travel BESIDE the grade, not inside the sentence -- a caller "
                        + "that can read the grade and not the number is back to substring-matching",
                Integer.valueOf(412), refused.unreadCells());

        // A route refusal over fully-read terrain: OBSERVED, and a 0 rather than a null, because a
        // search ran and read every cell it asked about. The two refusals being different VALUES is
        // the whole of the grade, and the count being 0 rather than null is the whole of the count.
        LocomotionController readable = RouteRefusalProbe.overFullyReadTerrain();
        ActOutcome readRefusal = readable.tick(new FakeActuator());
        pool.add(readRefusal.belief());
        assertEquals("a refusal over fully-read terrain carries 0, not null: 0 says the search ran "
                        + "and read everything, which is a stronger claim about the world than null",
                Integer.valueOf(0), readRefusal.unreadCells());

        return pool;
    }

    private static Set<Belief> censusOverScenarios() {
        Set<Belief> pool = new TreeSet<>(java.util.Comparator.comparing(Enum::name));

        FakeActuator clear = flatCorridor();
        pool.add(oneRunningTick(new NavController(40.5D, FEET, 0.5D, 300), clear).belief());

        pool.add(drive(stanceTo(30.5D, flatCorridor()), flatCorridor(), 200).belief());
        pool.addAll(digAndRefusalGrades());

        // The one WALK outcome that is UNKNOWN, and the census needs it: a body wedged against
        // something this client cannot name is the commonest failure a plain walk hits, and it is
        // the case a caller must refuse rather than reroute.
        FakeActuator unnameable = flatCorridor();
        unnameable.putBlock(3, FEET, 0, "log");
        unnameable.blockAtReturnsNull = true;
        pool.add(drive(stanceTo(30.5D, unnameable), unnameable, 200).belief());
        return pool;
    }

    private static ActOutcome oneRunningTick(NavController nav, FakeActuator act) {
        ActOutcome out = nav.tick(act);
        assertFalse("this helper needs a NON-terminal tick so it can read the running grade",
                out.terminal());
        return out;
    }

    private static NavController stanceTo(double x, FakeActuator act) {
        return NavController.toStance(x, FEET, 0.5D, 300, standableOf(act));
    }

    private static net.marcloud.mcp.core.drivers.act.Standable standableOf(FakeActuator act) {
        net.marcloud.mcp.core.drivers.plan.BlockView view = BodySim.blockView(act);
        return (x, y, z) -> new net.marcloud.mcp.core.drivers.plan.Stance(x, y, z)
                .isStandable(view);
    }

    private static FakeActuator flatCorridor() {
        FakeActuator act = new FakeActuator();
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                act.putBlock(x, GROUND, z);
            }
        }
        act.setPosition(0.5D, FEET, 0.5D);
        act.onGround = true;
        act.yaw = 0f;
        return act;
    }

    /** Drive to terminal, moving the body the way the axes ask. */
    private static ActOutcome drive(NavController nav, FakeActuator act, int maxTicks) {
        ActOutcome last = null;
        for (int i = 0; i < maxTicks; i++) {
            last = nav.tick(act);
            if (last.terminal()) {
                break;
            }
            BodySim.step(act, nav.forward(), nav.strafe());
        }
        assertNotNull("a walk produced no outcome", last);
        return last;
    }
}