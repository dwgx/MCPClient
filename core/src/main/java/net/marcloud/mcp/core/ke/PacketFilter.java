package net.marcloud.mcp.core.ke;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An immutable, reference-free query filter for {@link PacketJournal} entries
 * (PHASE P.6). Matches by direction and by class-name include/exclude patterns.
 * A pattern containing {@code *} or {@code ?} is treated as a glob (matched
 * against both the simple name and the fully-qualified name); otherwise it is a
 * case-insensitive substring test. Precedence is deny-first:
 * <ol>
 *   <li>direction gate — reject if the entry's dir doesn't match;</li>
 *   <li>exclude — reject if any exclude pattern matches;</li>
 *   <li>include — if any include patterns are given, keep only matches;</li>
 *   <li>noise drop — if {@code dropNoise} and NO explicit include is set, drop
 *       high-frequency low-signal packets (keepalive / time / velocity).</li>
 * </ol>
 */
public final class PacketFilter {

    public enum Dir { IN, OUT, ANY }

    /** Pass-through filter (any direction, no include/exclude, no noise drop). */
    public static final PacketFilter NONE = new PacketFilter(Dir.ANY, List.of(), List.of(), false);

    private static final List<Pattern> NOISE = List.of(
            Pattern.compile(".*S00PacketKeepAlive$"),
            Pattern.compile(".*S03PacketTimeUpdate$"),
            Pattern.compile(".*S12PacketEntityVelocity$"));

    private final Dir dir;
    private final List<java.util.function.Predicate<String>> include;
    private final List<java.util.function.Predicate<String>> exclude;
    private final boolean dropNoise;

    private PacketFilter(Dir dir, List<String> include, List<String> exclude, boolean dropNoise) {
        this.dir = dir == null ? Dir.ANY : dir;
        this.include = compileAll(include);
        this.exclude = compileAll(exclude);
        this.dropNoise = dropNoise;
    }

    public static PacketFilter of(Dir dir, List<String> include, List<String> exclude,
                                  boolean dropNoise) {
        return new PacketFilter(dir, include == null ? List.of() : include,
                exclude == null ? List.of() : exclude, dropNoise);
    }

    /** True if an entry with these attributes passes the filter. */
    public boolean accepts(String simpleName, String className, PacketJournal.Dir entryDir) {
        // 1. direction gate
        if (dir == Dir.IN && entryDir != PacketJournal.Dir.IN) {
            return false;
        }
        if (dir == Dir.OUT && entryDir != PacketJournal.Dir.OUT) {
            return false;
        }
        // 2. exclude wins
        for (var p : exclude) {
            if (matches(p, simpleName, className)) {
                return false;
            }
        }
        // 3. include (if any specified, must match at least one)
        if (!include.isEmpty()) {
            boolean any = false;
            for (var p : include) {
                if (matches(p, simpleName, className)) {
                    any = true;
                    break;
                }
            }
            return any;
        }
        // 4. default noise drop, only when no explicit include narrowed the set
        if (dropNoise) {
            for (Pattern n : NOISE) {
                if (className != null && n.matcher(className).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matches(java.util.function.Predicate<String> p,
                                   String simpleName, String className) {
        return (simpleName != null && p.test(simpleName))
                || (className != null && p.test(className));
    }

    private static List<java.util.function.Predicate<String>> compileAll(List<String> pats) {
        List<java.util.function.Predicate<String>> out = new ArrayList<>();
        if (pats == null) {
            return out;
        }
        for (String pat : pats) {
            if (pat != null && !pat.isBlank()) {
                out.add(compile(pat));
            }
        }
        return out;
    }

    /** Glob → regex if it has wildcards, else a case-insensitive substring test. */
    private static java.util.function.Predicate<String> compile(String pattern) {
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            StringBuilder rx = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    rx.append(".*");
                } else if (c == '?') {
                    rx.append('.');
                } else {
                    rx.append(Pattern.quote(String.valueOf(c)));
                }
            }
            Pattern p = Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
            return s -> p.matcher(s).matches();
        }
        String needle = pattern.toLowerCase(Locale.ROOT);
        return s -> s.toLowerCase(Locale.ROOT).contains(needle);
    }
}
