package net.marcloud.mcp.core.ldr;

import java.util.List;
import java.util.Map;

/**
 * Outcome of an in-memory compilation.
 *
 * @param success    whether javac reported no errors
 * @param bytecode   fully-qualified class name -> compiled {@code .class} bytes
 *                   (empty if compilation failed)
 * @param diagnostics human-readable compiler messages (errors/warnings), in order
 */
public record CompileResult(boolean success,
                            Map<String, byte[]> bytecode,
                            List<String> diagnostics) {

    /** The primary class's bytes when exactly one top-level class was compiled. */
    public byte[] singleClass() {
        if (bytecode.size() == 1) {
            return bytecode.values().iterator().next();
        }
        throw new IllegalStateException(
                "expected exactly one compiled class but got " + bytecode.keySet());
    }

    /** Joined diagnostics, one per line — convenient to return to an AI caller. */
    public String diagnosticsText() {
        return String.join("\n", diagnostics);
    }
}
