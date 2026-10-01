package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading shipped sources as text, for the guards that cannot be behavioural.
 *
 * <p>Every one of these guards pins a property that no API reports and no headless test can reach:
 * whether a dismissal retains a surface (needs a live Skia context and a Minecraft instance),
 * whether a failed GL wrap is retried and reported (needs the failure), whether a page wires its
 * buttons (needs a display to click them). Each is a policy that reverts silently -- the audit found
 * three of them at once -- so each gets a guard that runs on every build, including the CI runner
 * where the live ITs self-skip. Source-level is the honest way to do that, and this is the boring,
 * shared part of it: read, strip comments to code, take a declaration's body.
 *
 * <p>The failure mode of the comment stripper matters here and is worth stating: it is not
 * string-aware, and it does not need to be for the files it scans (none declares a literal
 * containing a comment marker). If one ever does, the worst case is a guard that reads a shorter
 * comment than it is -- a red test, not a wrong behaviour.
 */
final class SourceScan {

    private SourceScan() {
    }

    /** The file's text, asserting it exists so a moved file fails loudly rather than vacuously. */
    static String read(Path path) {
        assertTrue(path + " must exist", Files.isRegularFile(path));
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    /** Source with {@code //} and block comments removed. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); ) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length()
                        && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * The comment-stripped body of {@code method}'s DECLARATION, opening brace to its match.
     *
     * <p>Declarations only. A method named in prose goes away with the comments, and a CALL site is
     * skipped by taking the first occurrence whose parameter list is followed by a body rather than
     * by {@code ;} — which is not a hypothetical: {@code rebuild()} is called a few lines above
     * where it is declared, and reading that call as the declaration would make a guard assert
     * against the wrong block.
     */
    static String bodyOf(String source, String method) {
        String code = stripComments(source);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\b" + method + "\\s*\\(").matcher(code);
        while (m.find()) {
            int paramsEnd = matchingDelimiter(code, m.end() - 1, '(', ')');
            int body = code.indexOf('{', paramsEnd);
            assertTrue(method + " must have a body", body > 0);
            if (!code.substring(paramsEnd, body).contains(";")) {
                return code.substring(body, matchingDelimiter(code, body, '{', '}') + 1);
            }
        }
        throw new AssertionError("no declaration of " + method + " with a body in the source");
    }

    /** Index of the delimiter matching the one at {@code open}. */
    static int matchingDelimiter(String code, int open, char openCh, char closeCh) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == openCh) {
                depth++;
            } else if (c == closeCh) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("unbalanced " + openCh + closeCh + " starting at " + open);
    }
}
