package net.marcloud.mcp.board;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * PHASE E architecture guard: there are exactly TWO event buses in the system —
 * core's {@code EventBus} (for {@code GameEvent}s) and board's {@code Trace} (for
 * {@code Signal}s). Everything else that touches both a subscribe- and a
 * publish-surface must be a NAMED, sanctioned adapter between those two buses, not
 * a third home-grown bus quietly fanning events on the side.
 *
 * <p>The teeth: scan every {@code main} source file in core and board for one that
 * contains BOTH a {@code subscribe(} and a {@code publish(} call (the signature of
 * "defines or wires a bus"), and assert the set of such files is a subset of the
 * allowlist below. The two real buses define both methods; the sanctioned bridges
 * ({@code BoardClockBridge}, {@code BoardWorldEventBridge}) subscribe on one bus
 * and publish on the other. A NEW unlisted class matching this shape — a third bus
 * — fails this test, forcing a deliberate review.
 *
 * <p>{@code BoardTraceLink} (a publish-only facade) and {@code DebugEventQueue} (a
 * native offer/drain queue) do not match the both-calls shape today, but are named
 * in the allowlist so that if they ever grow a subscribe surface they remain
 * sanctioned rather than tripping this guard by accident.
 */
public class NoThirdBusTest {

    /** Simple class names sanctioned to carry both a subscribe- and publish-surface. */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            // the two real buses
            "EventBus",              // core: GameEvent bus
            "Trace",                 // board: Signal bus
            // sanctioned core→board adapters
            "BoardClockBridge",      // TickEvent -> TickSignal
            "BoardWorldEventBridge", // world GameEvents -> world Signals
            "BoardTraceLink",        // reflective publish facade (chat veto, generic publish)
            "DebugEventQueue"        // native debug-event offer/drain queue
    ));

    private static final Pattern SUBSCRIBE = Pattern.compile("\\bsubscribe\\s*\\(");
    private static final Pattern PUBLISH = Pattern.compile("\\bpublish\\s*\\(");

    @Test
    public void noUnsanctionedThirdBus() throws IOException {
        Path root = repoRoot();
        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("core/src/main/java"));
        roots.add(root.resolve("board/src/main/java"));

        Set<String> offenders = new LinkedHashSet<>();
        int scanned = 0;
        for (Path srcRoot : roots) {
            if (!Files.isDirectory(srcRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(srcRoot)) {
                List<Path> javaFiles = paths
                        .filter(p -> p.toString().endsWith(".java"))
                        .collect(Collectors.toList());
                for (Path p : javaFiles) {
                    scanned++;
                    String src = readString(p);
                    if (SUBSCRIBE.matcher(src).find() && PUBLISH.matcher(src).find()) {
                        String simple = p.getFileName().toString().replace(".java", "");
                        if (!ALLOWED.contains(simple)) {
                            offenders.add(srcRoot.relativize(p).toString());
                        }
                    }
                }
            }
        }

        assertTrue("expected to scan core+board main sources; found none — "
                + "repo root resolution is wrong (user.dir=" + System.getProperty("user.dir")
                + ", root=" + root + ")", scanned > 0);

        if (!offenders.isEmpty()) {
            fail("Unsanctioned subscribe+publish surface (a possible THIRD bus) — "
                    + "add a deliberate entry to NoThirdBusTest.ALLOWED only after "
                    + "confirming it is EventBus, Trace, or a named adapter between "
                    + "them, never a new bus:\n  " + String.join("\n  ", offenders));
        }
    }

    private static String readString(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Locate the reactor root by walking up from {@code user.dir} until a directory
     * containing BOTH {@code core/} and {@code board/} is found. Robust whether the
     * test runs with the module dir (surefire) or the reactor root as cwd.
     */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isDirectory(d.resolve("core")) && Files.isDirectory(d.resolve("board"))) {
                return d;
            }
        }
        // Fallback: parent of the module dir (…/board -> …).
        return dir.getParent() == null ? dir : dir.getParent();
    }
}
