package net.marcloud.mcp.core.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The §1 table of {@code docs/agency/command-to-action.md} — the "already built, do not rebuild"
 * table — must not carry {@code File.java:<digits>} anchors in its 位置 column.
 *
 * <p><b>Why this asserts the ABSENCE of a shape instead of the PRESENCE of a fact.</b>
 * On 2026-10-03 that table was swept line by line. It had gone a month with zero edits, and
 * nine of its eleven rows had rotted: five entirely, two partially, two still accurate. The
 * proximate cause was not that the things stopped existing — it was that the <em>line numbers</em>
 * they pointed at had drifted, while the symbols were still there. So the tempting guard,
 * "every symbol the table names still resolves in the tree", would have caught almost none of
 * that batch: the objects were alive, only the addresses were wrong. A guard that cannot catch
 * the disease it was written for is a placebo. This one therefore forbids the half-life itself.
 *
 * <p><b>Scope is the table, not the file, and the scope is load-bearing.</b> §1.2 of the same
 * document keeps the old line numbers <em>on purpose</em>: that table is the evidence of where
 * the sweep was wrong, and deleting it would destroy the reason the sweep happened. A guard that
 * scanned the whole document would therefore be red on today's bytes for the right reason and be
 * deleted for the wrong one. The unit of measurement here is the §1 table's 位置 column, and
 * {@link #theScopeIsTheTableAndNotTheWholeDocument()} proves the exclusion is real rather than
 * assumed.
 *
 * <p><b>Every failure mode of the LOCATOR is a failure, never an empty scan.</b> The shape this
 * project has already paid for is a guard whose only discriminator is something that does not
 * change when the thing it guards does — a package name that survives a move between trees. Here
 * the analogous hazard is inverted and just as fatal: if {@link #repoDoc()} or
 * {@link #sectionOneTable(String)} failed quietly and returned nothing, then "the table has no
 * line-number anchors" would be <em>vacuously true</em> and the guard would be green on a
 * document it never read. So every locator throws with a diagnostic instead of returning an
 * empty collection, and {@link #aLocatorThatFindsNothingMustFailRatherThanPass()} drives each of
 * those branches.
 */
public final class AgencyTableNamesOnlyLiveSymbolsTest {

    /** The document, relative to the repository root. */
    private static final String DOC_RELATIVE = "docs/agency/command-to-action.md";

    /**
     * The half-life shape: a source-file name immediately followed by {@code :} and a digit.
     *
     * <p>Only {@code .java}, deliberately. {@code guarantees.md} is cited as {@code §6.14}, not
     * {@code guarantees.md:114}, so widening this to every extension would add false reds over
     * prose conventions this repository does not use without buying coverage of anything.
     */
    private static final Pattern LINE_ANCHOR = Pattern.compile("[\\w$./-]+\\.java:\\d+");

    /** The header cell this guard measures. Today it reads {@code 位置(符号)}. */
    private static final String LOCATION_COLUMN = "位置";

    // ------------------------------------------------------------------
    // 1. the guard itself
    // ------------------------------------------------------------------

    /**
     * The assertion this file exists for: no {@code File.java:<digits>} anchor in §1's 位置 column.
     *
     * <p>Red on the pre-sweep table (nine rows of {@code ActSlot.java:15-21} and friends), green
     * on the symbols-only table.
     */
    @Test
    public void theSectionOneLocationColumnCarriesNoLineNumberAnchor() {
        assertNoLineNumberAnchor(readDoc(repoDoc()));
    }

    // ------------------------------------------------------------------
    // 2. the extraction is real, not an empty set
    // ------------------------------------------------------------------

    /**
     * Non-vacuity: the extraction returned cells with content, and at least one of them is a
     * symbol rather than blank.
     *
     * <p>This is deliberately a floor of ONE row, not a count like "at least eight rows". A row
     * count would be coverage — it would catch somebody deleting half the table — and this guard
     * does not claim that. What it must rule out is only the empty-scan reading: "there are no
     * line numbers in there" being true because "there" was empty. Content assertions catch that
     * and say nothing about table size.
     */
    @Test
    public void theTableAndItsLocationColumnAreActuallyExtracted() {
        Table table = sectionOneTable(readDoc(repoDoc()));
        assertTrue("the §1 table must yield at least one data row; zero rows would make"
                + " 'no line-number anchors' vacuously true",
                table.body.size() >= 1);
        int col = table.locationColumn();
        boolean anySymbol = false;
        for (int i = 0; i < table.body.size(); i++) {
            String cell = table.cell(i, col);
            assertTrue("§1 table row " + (i + 1) + " has an empty 位置 cell — the column this guard"
                    + " reads is empty there, which is indistinguishable from a column that was"
                    + " never located",
                    !cell.isEmpty());
            anySymbol |= cell.indexOf('`') >= 0;
        }
        assertTrue("no 位置 cell in the §1 table contains a backticked symbol, so the cells this"
                + " guard scans are not the location column this guard thinks they are",
                anySymbol);
    }

    // ------------------------------------------------------------------
    // 3. the pattern is not inert
    // ------------------------------------------------------------------

    /**
     * The detector fires on the real pre-sweep row shapes, so a green §1 above means "clean" and
     * not "the regex never matched".
     *
     * <p>The fixtures are the verbatim 位置 cells of {@code git show HEAD} of the document: the
     * drift produced by a single edit moving a method down a file, so the anchor still resolves
     * to the right <em>file</em> and the wrong place inside it. That is the exact event a
     * "does the symbol still exist" guard is blind to.
     */
    @Test
    public void theAnchorPatternFiresOnTheShapesTheSweepRemoved() {
        List<String> rotten = List.of(
                "`ActSlot.java:15-21`、`ActRuntime.java:104-108`",
                "`ActActuator.java:3-17`、`core/src/test/.../act/FakeActuator.java`",
                "`LocalGrid.java:38-98`",
                "`WorldViewCapture.java:41,53-54`",
                "`se/Ring.java:116-118`、`PolicySideTableDriftTest.java:74-77`");
        for (String cell : rotten) {
            assertTrue("the pattern must flag a cell shaped like the pre-sweep table's: " + cell,
                    LINE_ANCHOR.matcher(cell).find());
        }
        assertTrue("and it must not fire on a bare path with no line number, or the guard would"
                + " red on today's `core/src/test/.../LiveGameGate.java` row",
                !LINE_ANCHOR.matcher("`core/src/test/java/net/marcloud/mcp/core/LiveGameGate.java`")
                        .find());
    }

    // ------------------------------------------------------------------
    // 4. the scope is the table, not the file
    // ------------------------------------------------------------------

    /**
     * The §1.2 drift record keeps its old line numbers as evidence, and this guard must not read
     * them as violations.
     *
     * <p>Two halves, and the second is the one that keeps the first honest. The synthetic half
     * shows the scope works on a table shaped like today's §1.2. The real half shows the scope is
     * still doing work on the bytes actually in the file: if the document had no line-number
     * anchors outside §1's table, the synthetic half would be proving nothing about the live
     * document, and a future reader would have no way to tell the two apart.
     */
    @Test
    public void theScopeIsTheTableAndNotTheWholeDocument() {
        String synthetic = syntheticDoc(
                "| 东西 | 位置(符号) | 今天 | 为什么 |\n"
                        + "|---|---|---|---|\n"
                        + "| **三通道** | `ActSlot`、`GameClock.lastCompletedTick()` | 仍成立 | 为什么 |",
                "| 原行 | 原位置 |\n|---|---|\n"
                        + "| 三通道 | `ActSlot.java:15-21`、`ActRuntime.java:104-108` | 位置失效 |");
        assertEquals("§1.2's drift record deliberately keeps line numbers, so a scope that leaked"
                + " past §1's table would flag them",
                List.of(), anchorsIn(synthetic));

        String doc = readDoc(repoDoc());
        assertTrue("this document is expected to still carry line-number anchors in its §1.2 drift"
                + " record; if that evidence is ever deleted this test's premise changes and the"
                + " scope exclusion above stops being exercised on real bytes",
                countAnchors(doc) > countAnchors(String.join("\n", sectionOneTable(doc).raw)));
        assertEquals("and the §1 table itself is clean today",
                List.of(), anchorsIn(doc));
    }

    // ------------------------------------------------------------------
    // 5. every locator failure is red
    // ------------------------------------------------------------------

    /**
     * Nothing about locating the document, the section, the table or the column may degrade into
     * an empty scan — that is the one failure mode that would make this guard worse than useless,
     * because it would report compliance it never checked.
     */
    @Test
    public void aLocatorThatFindsNothingMustFailRatherThanPass() {
        assertLocatorFails("a document with no §1 section at all",
                "# 别的文件\n\n没有这一节。\n");
        assertLocatorFails("a §1 section with no table in it",
                "## 1. 已经建好的部分(不要重建)\n\n只有散文。\n");
        assertLocatorFails("a §1 table whose header has no 位置 column",
                syntheticDoc("| 东西 | 在哪 | 今天 | 为什么 |\n|---|---|---|---|\n"
                        + "| 甲 | `A` | 仍成立 | 为什么 |", ""));
        assertLocatorFails("a §1 table with a header row and nothing else",
                syntheticDoc("| 东西 | 位置(符号) | 今天 | 为什么 |", ""));
        assertLocatorFails("a §1 table whose rows are not a rectangle",
                "## 1. 已经有了\n\n| 东西 | 位置(符号) | 今天 |\n|---|---|---|\n"
                        + "| 甲 | `A` | 仍成立 |\n| 乙 | `B` |\n");
    }

    /**
     * Drives the full measure-and-scan path, not just the table parse, so the column locator is
     * covered too.
     *
     * <p>The {@link #ran} flag rather than {@code fail()} inside the try: {@code fail} throws
     * {@link AssertionError}, which is exactly what the catch below is here to swallow, so the
     * "it did not throw" case would have read as a pass.
     */
    private static void assertLocatorFails(String what, String doc) {
        boolean ran = false;
        try {
            anchorsIn(doc);
        } catch (AssertionError expected) {
            ran = true;
            assertTrue("the diagnostic must name the failure rather than be blank",
                    expected.getMessage() != null && expected.getMessage().length() > 40);
        }
        assertTrue("a guard that measures the §1 table must FAIL when it cannot find it — got a"
                + " clean scan for: " + what, ran);
    }

    // ==================================================================
    // the machinery, package-visible so the red/green evidence can be driven
    // ==================================================================

    /**
     * The whole assertion, as a function of document text.
     *
     * <p>Kept separate from the file read so the pre-sweep version of this document can be run
     * through the identical code path to produce a real red, rather than a demonstration that a
     * similar string would fail.
     */
    public static void assertNoLineNumberAnchor(String docText) {
        List<String> offenders = anchorsIn(docText);
        if (!offenders.isEmpty()) {
            fail("the §1 「已经建好的部分(不要重建)」 table's 位置 column must name symbols, never"
                    + " File.java:<digits> anchors. A line number into a file that is being edited"
                    + " is a half-life, not a reference (guarantees.md §6.14) — the symbols outlive"
                    + " the refactors that move them. Offending cells:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /** Every offending 位置 cell, labelled by the row's 东西 cell. Empty when the table is clean. */
    public static List<String> anchorsIn(String docText) {
        Table table = sectionOneTable(docText);
        int col = table.locationColumn();
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < table.body.size(); i++) {
            String cell = table.cell(i, col);
            Matcher m = LINE_ANCHOR.matcher(cell);
            while (m.find()) {
                offenders.add("row " + (i + 1) + " [" + table.cell(i, 0) + "] -> " + m.group());
            }
        }
        return offenders;
    }

    // ------------------------------------------------------------------
    // locating the file
    // ------------------------------------------------------------------

    /**
     * The document, found by walking up from two independent starting points.
     *
     * <p>Why not {@code user.dir} alone: surefire runs with the <em>module</em> directory as the
     * working directory, so a relative path resolves into {@code core/}. Why not the code source
     * alone: it is the right answer under maven and under an IDE with per-module output, but a
     * test runner that loads classes from somewhere unusual leaves it null or somewhere useless.
     * Probing both and requiring the same file under both shapes is the difference between a guard
     * that is right in the build and a guard that is right in one IDE.
     *
     * <p>The sibling {@code pom.xml} requirement is not decoration: it stops a stray copy of the
     * document from satisfying the search.
     *
     * <p><b>Throws rather than returning null.</b> See the class javadoc — a locator that returns
     * nothing here would make this guard pass on a document it never opened.
     */
    static Path repoDoc() {
        List<Path> starts = new ArrayList<>();
        starts.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        CodeSource cs = AgencyTableNamesOnlyLiveSymbolsTest.class
                .getProtectionDomain().getCodeSource();
        if (cs != null && cs.getLocation() != null) {
            try {
                starts.add(Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize());
            } catch (URISyntaxException | IllegalArgumentException e) {
                // Not a file: URL. The working directory above still gets probed.
            }
        }
        List<String> probed = new ArrayList<>();
        for (Path start : starts) {
            for (Path p = start; p != null; p = p.getParent()) {
                probed.add(p.toString());
                if (Files.isRegularFile(p.resolve(DOC_RELATIVE))
                        && Files.isRegularFile(p.resolve("pom.xml"))) {
                    return p.resolve(DOC_RELATIVE);
                }
            }
        }
        throw new AssertionError("no " + DOC_RELATIVE + " beside a pom.xml above any of " + starts
                + " (probed: " + probed + "). This guard measures that document, so a run that"
                + " cannot find it must FAIL. Returning an empty scan instead would report"
                + " 'the table has no line-number anchors' about a table it never read — the same"
                + " class of defect as a guard whose discriminator does not change when the thing"
                + " it guards does.");
    }

    private static String readDoc(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                throw new AssertionError(file + " is empty; an empty document makes this guard"
                        + " vacuously green");
            }
            return text;
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + file + ": " + e, e);
        }
    }

    // ------------------------------------------------------------------
    // locating and parsing the table
    // ------------------------------------------------------------------

    /** The §1 table, or a loud failure. Never an empty table. */
    static Table sectionOneTable(String docText) {
        List<String> lines = docText.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isHeading(lines.get(i), 2) && rest(lines.get(i), 2).startsWith("1.")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new AssertionError("no `## 1.` section heading in the document; this guard"
                    + " measures §1 and must fail rather than scan nothing");
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#")) {
                end = i;
                break;
            }
        }

        int header = -1;
        for (int i = start + 1; i < end; i++) {
            if (isTableRow(lines.get(i))) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw new AssertionError("§1 of the document (lines " + (start + 1) + "-" + end
                    + ") has no markdown table; this guard measures that table and must fail"
                    + " rather than conclude it holds no line numbers");
        }
        List<String> raw = new ArrayList<>();
        for (int i = header; i < end && isTableRow(lines.get(i)); i++) {
            raw.add(lines.get(i));
        }
        if (raw.size() < 2 || !isSeparator(raw.get(1))) {
            throw new AssertionError("the §1 table has no `|---|---|` separator after its header"
                    + " row (rows found: " + raw.size() + "); a one-line block is not a table and"
                    + " must not be read as a clean one");
        }
        return new Table(raw);
    }

    private static int countAnchors(String text) {
        Matcher m = LINE_ANCHOR.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static boolean isTableRow(String line) {
        String s = line.trim();
        return s.startsWith("|") && s.indexOf('|', 1) >= 0;
    }

    private static boolean isSeparator(String line) {
        return line.replace("|", "").replace(" ", "").replace("-", "").replace(":", "")
                .isEmpty();
    }

    private static boolean isHeading(String line, int level) {
        for (int i = 0; i < level; i++) {
            if (line.length() <= i || line.charAt(i) != '#') {
                return false;
            }
        }
        return line.length() > level && line.charAt(level) == ' ';
    }

    private static String rest(String line, int level) {
        return line.substring(level + 1).trim();
    }

    /**
     * A document whose §1 holds {@code table} and whose §1.2 holds {@code drift}.
     *
     * <p>Two arguments, not one, because the point of the scope is the boundary BETWEEN two
     * tables: a fixture that concatenated them into one run would be testing a merged table and
     * would go red for the wrong reason.
     */
    private static String syntheticDoc(String table, String drift) {
        return "## 1. 已经建好的部分(不要重建)\n\n"
                + table + "\n\n"
                + "### 1.2 这张表的漂移记录\n\n"
                + drift + "\n";
    }

    // ------------------------------------------------------------------

    /** A parsed markdown table whose cells are addressable by column index. */
    private static final class Table {

        final List<String> raw;
        final List<String> header;
        final List<String> body;

        Table(List<String> raw) {
            this.raw = List.copyOf(raw);
            this.header = cells(raw.get(0));
            this.body = new ArrayList<>();
            for (int i = 2; i < raw.size(); i++) {
                body.add(raw.get(i));
            }
            if (body.isEmpty()) {
                throw new AssertionError("the §1 table has a header and a separator but no data"
                        + " row; that is an empty table, and 'an empty table has no line numbers'"
                        + " is not a thing this guard is allowed to conclude");
            }
            int width = header.size();
            for (String row : body) {
                if (cells(row).size() != width) {
                    throw new AssertionError("the §1 table is not a rectangle — header has "
                            + width + " columns, this row has " + cells(row).size() + ": " + row
                            + ". Cell splitting is ambiguous, so the column this guard reads is"
                            + " undefined; failing is the only honest reading");
                }
            }
        }

        /** Index of the 位置 column, or a loud failure. */
        int locationColumn() {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).startsWith(LOCATION_COLUMN)) {
                    return i;
                }
            }
            throw new AssertionError("no column whose header starts with `" + LOCATION_COLUMN
                    + "` in the §1 table; header is " + header + ". This guard measures that"
                    + " column, so a rename must fail here rather than leave the guard reading"
                    + " some other column, or nothing");
        }

        String cell(int row, int col) {
            return cells(body.get(row)).get(col);
        }

        private static List<String> cells(String row) {
            String s = row.trim();
            if (s.startsWith("|")) {
                s = s.substring(1);
            }
            if (s.endsWith("|")) {
                s = s.substring(0, s.length() - 1);
            }
            List<String> out = new ArrayList<>();
            for (String c : s.split("\\|", -1)) {
                out.add(c.trim());
            }
            return out;
        }
    }
}