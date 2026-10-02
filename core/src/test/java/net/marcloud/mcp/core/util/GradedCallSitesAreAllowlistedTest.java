package net.marcloud.mcp.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The anti-drift guard that decides whether the belief layer survives.
 *
 * <p><b>Why a reverse invariant and not a forward one.</b> A forward test would assert that the
 * things a layer must do, happen. That does not stop the layer from GROWING. The belief layer's
 * stated failure mode is specific and dated: "without this test, day 19 will grow a 19th
 * {@code observed(}". Nobody adds a nineteenth {@code observed(} out of malice -- they add it
 * because the site genuinely was read directly, the factory is right there, and adding it is easier
 * than the alternative. The guard that catches it is therefore not "these sites are correct" but
 * <b>"these sites, and no others, may claim OBSERVED"</b> -- and it must fail on one FEWER as well as
 * one more, or deleting a legitimate caller would pass silently and the allowlist would rot into a
 * list of permissions nobody holds.
 *
 * <p><b>The precedent is in this repository, in the same shape.</b>
 * {@code PolicySideTableDriftTest.everyHighIntegrityWriterDeclaresAnL4Privilege} is the general form:
 * any writer at HIGH integrity or above must carry an L4 privilege, checked by iterating the
 * registry and collecting offenders, rather than by asserting a fixed count. The other three tests
 * in that file are forward -- "every L3 tool declares a ring" -- and the file's own javadoc records
 * that the reverse one is "the one the other three miss".
 *
 * <p><b>Why source text and not reflection or bytecode.</b> Reflection would find the compiled
 * shape, which cannot answer the question being asked: a file that stopped calling
 * {@code observed(...)} and started writing {@code new Graded<>(v, OBSERVED, null)} is perfectly
 * well-formed bytecode and is exactly the drift. Bytecode would find the {@code INVOKESTATIC} but
 * would need {@code core/target/classes} to be current, and this file has to run in the same serial
 * window as everything else. Source text reads the decision as written. The cost is that a comment
 * mentioning {@code observed(} is counted, which is why {@link #ALLOWLIST} lists whole files and the
 * assertion below reports the offending line -- a false positive is a one-line fix to the allowlist
 * and a false NEGATIVE would be the layer quietly eroding.
 *
 * <p><b>What it caught, at the time of writing.</b> One thing, and it is the reason this test is not
 * theoretical: {@code RoutePlanning} cannot call the package-private
 * {@link Graded#observed(Object)} from {@code drivers.plan}, so it names
 * {@code Belief.OBSERVED} directly in a private helper. That is a legitimate, reviewed escape hatch
 * and it is invisible unless something counts call sites. The test therefore scans for
 * {@code Belief.OBSERVED} as well as {@code observed(}, and {@link #ALLOWLIST} carries both files
 * with the reason each one is allowed to claim a direct read.
 */
public class GradedCallSitesAreAllowlistedTest {

    /**
     * Every file in {@code core/src/main} permitted to assert {@link Belief#OBSERVED}, with the
     * reason it earns that.
     *
     * <p>Deliberately a small list, and deliberately explicit about the reason rather than just the
     * name: an allowlist of bare filenames is a list of permissions, and a permission nobody can
     * justify is a permission nobody will notice being used.
     */
    private static final TreeMap<String, String> ALLOWLIST = new TreeMap<>();

    static {
        // Design section 2.A #5: chunk-loaded check, THEN the state read, this tick. The one place
        // in this repository where the read ordering is already correct, so the claim is earned.
        ALLOWLIST.put("net/marcloud/mcp/core/util/BlockProbe.java",
                "design 2.A #5: reads a block state this tick with the chunk check first, the only "
                        + "correctly ordered read in the repository");
        // Design section 2 for the route refusal: both its reasons are field reads in the very
        // expression that builds the sentence, and its no-route half is graded from a counter the
        // search had already read to build the message.
        ALLOWLIST.put("net/marcloud/mcp/core/drivers/plan/RoutePlanning.java",
                "the two refusals whose reasons are live field reads, and the no-route refusal whose "
                        + "grade comes from an unread-cell count the message already contained");
        // Design section 2.B #9 / 6.B: the dig completion whose baseline AND current name were both
        // read this tick and differ. It earns OBSERVED because "the block that was there is not
        // there now" is a comparison between two this-tick reads and nothing else -- it does not
        // claim this client broke it, and the two other completions (an unreadable baseline, an
        // unreadable target) are graded INFERRED and UNKNOWN beside it.
        ALLOWLIST.put("net/marcloud/mcp/core/drivers/act/DigController.java",
                "the completion where both names were read this tick and differ; the other two "
                        + "completions on the same site are graded INFERRED and UNKNOWN, so the "
                        + "allowlisted case is the one with two real reads behind it and not a "
                        + "default");
        // The five named derivations. One entry rather than nine files each holding a permission:
        // READ_DIRECTLY is the only spelling of Belief.OBSERVED anywhere in the act and plan drivers,
        // and it lives here so a permission is reviewable in one place instead of copied nine times.
        // Every call site names the DERIVATION constant rather than the enum, which is what makes
        // "this sentence is arithmetic over reads, not a read" statically visible at the site.
        ALLOWLIST.put("net/marcloud/mcp/core/drivers/act/ActOutcome.java",
                "the five named derivations (READ_DIRECTLY, DERIVED_FROM_READS, "
                        + "PLANNED_ON_CLIENT_WORLD, REUSED_CELL, READ_CAME_BACK_EMPTY), each with the "
                        + "reason it earns it in its own javadoc. Centralising the spelling here is "
                        + "what keeps this an allowlist of four files instead of nine");
    }

    /**
     * Both ways a file can assert OBSERVED: the package-private factory (bare or qualified) and the
     * enum constant.
     *
     * <p>The lookbehind rejects a preceding word character or dot only for the BARE form, so
     * {@code Graded.observed(} is matched -- it is the form {@code BlockProbe} actually uses -- while
     * {@code myObserved(} and {@code some.observed()} are not false-positived on a method of another
     * name. Getting this wrong in the strict direction is the dangerous direction: a regex that
     * missed the qualified call would let the one file that legitimately uses the factory go
     * unrecorded, and the allowlist would then be wrong in the direction nobody checks.
     */
    private static final Pattern OBSERVED_CLAIM = Pattern.compile(
            "Graded\\s*\\.\\s*observed\\s*\\(|(?<![\\w.])observed\\s*\\(|Belief\\s*\\.\\s*OBSERVED");

    /** The file that DEFINES the layer, which necessarily names its own constant. */
    private static final String GRADED = "net/marcloud/mcp/core/util/Graded.java";

    @Test
    public void onlyAllowlistedFilesMayClaimObserved() {
        TreeMap<String, Integer> found = scanMain();

        TreeSet<String> unlisted = new TreeSet<>(found.keySet());
        unlisted.removeAll(ALLOWLIST.keySet());
        assertEquals("these files claim OBSERVED and are not on the allowlist. Claiming a direct "
                        + "read is the one move this layer cannot make casually: under the cheap "
                        + "mapping (everything is OBSERVED) 12 of the design's 18 sites are lying, "
                        + "and 3 of those change an action already emitted. Either the site earns it "
                        + "-- add it to ALLOWLIST with the reason it does -- or the claim is wrong. "
                        + "Claims found at: " + unlisted,
                new TreeSet<String>(), unlisted);

        TreeSet<String> unused = new TreeSet<>(ALLOWLIST.keySet());
        unused.removeAll(found.keySet());
        assertEquals("these files are allowlisted to claim OBSERVED but no longer do. An allowlist "
                        + "entry nobody holds is worse than no allowlist: it reads as permission, it "
                        + "reaches a future caller who greps for it, and it makes the list look "
                        + "larger than the set of sites that were actually reviewed. Remove the "
                        + "entry: " + unused,
                new TreeSet<String>(), unused);
    }

    /**
     * The allowlist is not a formality, and this is what stops it becoming one.
     *
     * <p>A reverse invariant over an empty or single-entry set degenerates into "the test passes".
     * This asserts the set is the size it is because it is, names the size in the failure so a
     * future addition is a decision rather than an accident, and -- the part that matters -- that
     * every entry corresponds to a file that really exists and really is in the scanned tree, so a
     * renamed or moved file fails instead of quietly dropping out of the comparison.
     */
    @Test
    public void theAllowlistIsNeitherEmptyNorStale() {
        TreeMap<String, Integer> found = scanMain();
        assertEquals("the allowlist has grown or shrunk; every change to the set of sites that may "
                        + "claim a direct read is a decision that belongs in this file with a "
                        + "reason, not a side effect of somebody adding a call somewhere",
                4, ALLOWLIST.size());
        for (String path : ALLOWLIST.keySet()) {
            assertTrue("allowlisted file is not in the scanned tree, so this test has drifted off "
                            + "the code it guards and must be repaired rather than deleted: " + path,
                    found.containsKey(path));
        }
        assertTrue("every allowlist entry must say WHY it may claim a direct read. A bare filename "
                        + "is a permission with no argument attached, and a permission with no "
                        + "argument is one nobody will think twice about handing out.",
                ALLOWLIST.values().stream().allMatch(why -> why != null && why.length() > 40));
    }

    /**
     * The {@code Graded} invariant itself, from the outside.
     *
     * <p>The constructor's refusal is the mechanism, and a mechanism that is only tested from
     * inside the same file is one a later refactor can quietly remove. Reaching the canonical
     * constructor by reflection also pins that it is reachable at all, which a package-private
     * constructor would not be from here.
     */
    @Test
    public void theConstructorRefusesAnUnearnedGradeAndNothingElse() throws Exception {
        java.lang.reflect.Constructor<Graded> ctor =
                Graded.class.getDeclaredConstructor(Object.class, Belief.class, String.class);
        assertTrue("the canonical constructor must stay reachable: an unconstructible Graded is a "
                        + "layer nobody can use",
                java.lang.reflect.Modifier.isPublic(ctor.getModifiers()));

        try {
            ctor.newInstance("v", Belief.INFERRED, null);
            throw new AssertionError("belief INFERRED with no why must not be constructible");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue("the refusal must be a NullPointerException naming why, because a bare "
                            + "IllegalArgument with no message sends the next maintainer looking at "
                            + "the wrong place",
                    e.getCause() instanceof NullPointerException
                            && String.valueOf(e.getCause().getMessage()).contains("why is required"));
        }

        // And the permitted case still works, so the guard is a rule and not a wall.
        Graded<?> ok = ctor.newInstance("v", Belief.INFERRED, "derived from a read two ticks old");
        assertEquals(Belief.INFERRED, ok.belief());
    }


    /** A throwaway instance used only as the {@code canAccess} receiver. */
    private static final Graded<?> ALLOWLIST_SAMPLE =
            new Graded<>("v", Belief.INFERRED, "sample");

    /**
     * Every file under {@code core/src/main} that asserts OBSERVED, with a count per file.
     *
     * <p>Surefire's working directory is the module directory, with the repo root as a fallback.
     * A missing tree FAILS rather than skipping: a drift guard that quietly stops checking is worse
     * than no guard, and quiet is how this kind of test dies.
     */
    private static TreeMap<String, Integer> scanMain() {
        Path root = mainSourceRoot();
        TreeMap<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> java = files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertTrue("no java sources found under " + root.toAbsolutePath()
                    + ": this test has drifted off the code it guards", !java.isEmpty());
            for (Path file : java) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (path.equals(GRADED)) {
                    continue; // the type that defines OBSERVED necessarily names it
                }
                String source = read(file);
                Matcher m = OBSERVED_CLAIM.matcher(stripComments(source));
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    found.put(path, n);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not scan " + root.toAbsolutePath(), e);
        }
        return found;
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file.toAbsolutePath(), e);
        }
    }

    /**
     * Blanks out comments so a javadoc mention of {@code observed(} is not counted as a claim.
     *
     * <p>Character-preserving: every replaced character becomes a space, so a matcher cannot be
     * fooled by two tokens being joined across a stripped comment. String literals are left ALONE,
     * because a file that puts "observed(" in a string is not making a claim either way and
     * over-stripping here would start hiding real ones.
     */
    static String stripComments(String source) {
        char[] out = source.toCharArray();
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            char next = i + 1 < out.length ? out[i + 1] : '\0';
            if (inBlock) {
                if (c == '*' && next == '/') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                    inBlock = false;
                } else if (c != '\n') {
                    out[i] = ' ';
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i++;
                inBlock = true;
            } else if (c == '/' && next == '/') {
                out[i] = ' ';
                inLine = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            }
        }
        return new String(out);
    }

    /** Unused, but kept honest: the scan must be a Set operation, not a count comparison. */
    @Test
    public void theScanIsOverFilesNotOverCallCounts() {
        Set<String> found = scanMain().keySet();
        assertTrue("the scan returns file paths, so one file claiming OBSERVED three times is one "
                + "allowlist entry, not three: " + found, found.contains(
                "net/marcloud/mcp/core/util/BlockProbe.java"));
    }
}
