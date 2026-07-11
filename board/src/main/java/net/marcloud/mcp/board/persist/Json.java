package net.marcloud.mcp.board.persist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON codec — just enough for {@link Store}'s envelopes.
 * Zero external deps (board rule): hand-written recursive parser + writer over
 * the value types {@link DataView} produces (nested {@link Map}, {@link String},
 * {@link Long}, {@link Double}, {@link Boolean}, {@code null}) plus arrays
 * ({@link List}) so a hand-edited or foreign file does not choke the parser.
 *
 * <p>Numbers are read back as {@link Long} when integral, {@link Double}
 * otherwise, matching how {@link DataView} stores them. The writer emits a
 * stable, indented, UTF-8-friendly document (only the standard JSON control
 * escapes, plus backslash-u hex escapes for other control chars) so files are
 * diff-able and portable.
 *
 * <p>{@link #parse} throws {@link JsonException} on a malformed document; callers
 * ({@link Store}) treat that as corruption and recover.
 */
public final class Json {

    private Json() {
    }

    /** Thrown by {@link #parse} when the input is not well-formed JSON. */
    public static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    // ---- writer -------------------------------------------------------------

    /** Serialize a root object to an indented JSON document. */
    public static String write(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, root, 0);
        sb.append('\n');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            writeObject(sb, (Map<String, Object>) v, indent);
        } else if (v instanceof List) {
            writeArray(sb, (List<Object>) v, indent);
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v.toString());
        } else {
            // Unknown type: store its string form so writing never fails.
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        int last = map.size() - 1;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            pad(sb, indent + 1);
            writeString(sb, e.getKey());
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (i++ < last) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- parser -------------------------------------------------------------

    /**
     * Parse a JSON document whose root must be an object. Returns the root map
     * (values: nested {@link Map}, {@link List}, {@link String}, {@link Long},
     * {@link Double}, {@link Boolean}, {@code null}).
     *
     * @throws JsonException if the text is malformed or the root is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String text) {
        Parser p = new Parser(text);
        Object root = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("trailing content at " + p.pos);
        }
        if (!(root instanceof Map)) {
            throw new JsonException("root is not an object");
        }
        return (Map<String, Object>) root;
    }

    /** A minimal recursive-descent JSON parser over a char sequence. */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new JsonException("unexpected char '" + c + "' at " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("expected key string at " + pos);
                }
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return map;
                }
                throw new JsonException("expected ',' or '}' at " + (pos - 1));
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return list;
                }
                throw new JsonException("expected ',' or ']' at " + (pos - 1));
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonException("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new JsonException("bad \\u escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException nfe) {
                                throw new JsonException("bad \\u escape at " + pos);
                            }
                            pos += 4;
                            break;
                        default:
                            throw new JsonException("bad escape '\\" + e + "' at " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            boolean floating = false;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            try {
                if (floating) {
                    return Double.valueOf(Double.parseDouble(num));
                }
                return Long.valueOf(Long.parseLong(num));
            } catch (NumberFormatException nfe) {
                // Overflowing or odd-but-numeric token: fall back to double so a
                // foreign file still parses rather than being deemed corrupt.
                try {
                    return Double.valueOf(Double.parseDouble(num));
                } catch (NumberFormatException nfe2) {
                    throw new JsonException("bad number '" + num + "' at " + start);
                }
            }
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("bad literal at " + pos);
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("bad literal at " + pos);
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new JsonException("expected '" + c + "' but got '" + actual + "' at " + (pos - 1));
            }
        }
    }
}
