import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import net.marcloud.mcp.core.memory.MemoryStore;
import net.marcloud.mcp.core.memory.MemoryTools;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.registry.SafeToolExecutor;
import net.marcloud.mcp.core.security.PermissionPolicy;
import net.marcloud.mcp.core.security.Ring;
import org.junit.Test;

/**
 * MEDIUM#12 regression: durable memory must NOT report fake success when
 * persistence fails. Before the fix, {@code MemoryStore.save()} swallowed the
 * IOException, the in-memory add/remove was still committed, and the tool
 * reported "remembered"/"deleted" — so the AI believed a memory was saved that
 * would be lost on the next restart.
 *
 * <p>These tests point the store at an UNWRITABLE path (one whose parent is a
 * regular file, so {@code Files.createDirectories} must fail on any real
 * filesystem) and assert (a) the tool reports {@code isError=true} and (b) the
 * in-memory state is unchanged (the speculative add/remove is rolled back).
 * They would fail against the old swallow-and-commit behavior.
 */
public class MemoryDurabilityTest {

    /** A registry wired with a wide-open clearance so the R3 memory tools run. */
    private static CapabilityRegistry openRegistry() {
        SafeToolExecutor exec = new SafeToolExecutor(2, 2000L);
        return new CapabilityRegistry(exec, new PermissionPolicy(Ring.R_MINUS_1, null));
    }

    /** A path guaranteed to be unwritable: its parent segment is a regular file. */
    private static Path unwritableTarget() throws Exception {
        Path blocker = Files.createTempFile("mcp-blocker", ".file");
        blocker.toFile().deleteOnExit();
        // <regularFile>/sub/mcp_memory.json — createDirectories(<regularFile>/sub)
        // must throw because <regularFile> is not a directory.
        return blocker.resolve("sub").resolve("mcp_memory.json");
    }

    @Test
    public void writeToUnwritablePathReportsFailureAndDoesNotCommit() throws Exception {
        MemoryStore store = new MemoryStore(unwritableTarget());
        MemoryTools tools = new MemoryTools(store);
        CapabilityRegistry reg = openRegistry();
        tools.registerAll(reg);

        assertEquals("store starts empty", 0, store.size());

        CallToolResult r = reg.invoke("memory_write",
                Map.of("title", "kicked by anticheat", "content", "avoid fly-hacking on server X"));

        assertTrue("write on an unwritable path must report isError", Boolean.TRUE.equals(r.isError()));
        assertEquals("failed write must NOT leave a phantom in-memory entry", 0, store.size());
        // And the rolled-back state is genuinely empty (not just size-0 by luck).
        assertTrue("no memory should be searchable after a failed write",
                store.search("anticheat", 10).isEmpty());
    }

    @Test
    public void deleteThatCannotPersistReportsFailureAndKeepsEntry() throws Exception {
        // First persist an entry successfully to a writable location.
        Path dir = Files.createTempDirectory("mcp-mem");
        Path sub = dir.resolve("sub");
        Path target = sub.resolve("mcp_memory.json");
        MemoryStore store = new MemoryStore(target);
        MemoryTools tools = new MemoryTools(store);
        CapabilityRegistry reg = openRegistry();
        tools.registerAll(reg);

        CallToolResult wrote = reg.invoke("memory_write",
                Map.of("title", "spawn point", "content", "castle courtyard"));
        assertFalse("initial write should succeed", Boolean.TRUE.equals(wrote.isError()));
        assertEquals(1, store.size());
        assertTrue("entry was persisted", Files.exists(target));

        // Now make the parent directory unwritable: remove it and put a regular
        // file where the directory used to be, so the next save()'s
        // createDirectories(sub) must fail on any filesystem.
        Files.delete(target);
        Files.delete(sub);
        Files.createFile(sub);

        CallToolResult r = reg.invoke("memory_delete", Map.of("id", "m1"));

        assertTrue("delete that cannot persist must report isError", Boolean.TRUE.equals(r.isError()));
        assertEquals("failed delete must NOT drop the entry in memory", 1, store.size());
        assertEquals("the entry is still recallable", 1, store.search("courtyard", 10).size());
    }

    @Test
    public void storeApiPropagatesPersistenceFailure() throws Exception {
        // Direct store-level guard: write() must THROW (not silently succeed) and
        // must not retain the entry, so any caller can trust size()/search().
        MemoryStore store = new MemoryStore(unwritableTarget());
        try {
            store.write("t", "c", List.of("x"));
            throw new AssertionError("expected UncheckedIOException on unwritable path");
        } catch (UncheckedIOException expected) {
            // good
        }
        assertEquals("no entry retained after a propagated failure", 0, store.size());
    }
}
