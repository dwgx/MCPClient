package net.marcloud.mcp.core.compat.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * The root private key must never be silently clobbered.
 *
 * <p><b>Why this is the most expensive line in the repository.</b> There is exactly one root key. It
 * lives outside the repository, and losing it is not recoverable: the shipped client trusts the
 * root that signed the current document, so a destroyed root cannot be re-minted into a document
 * that client accepts — every compat patch stops arming, silently, one stderr line each, and there
 * is no second copy anywhere. Losing the kernel key is just as bad and is easier to survive only
 * because a new kernel key CAN be introduced through a fresh root rotation; losing the ROOT ends
 * that chain.
 *
 * <p>{@link KernelKeygenCli} has refused to overwrite without {@code --force} since it was written,
 * with the reasoning in its own error message. {@link RootCeremonyCli} did not, so running the
 * ceremony to "just regenerate a document" destroyed the key it was supposed to be using. The two
 * tools are siblings; one had the guard and one did not, which is the shape this project keeps
 * paying for.
 *
 * <p>The test drives the real writer through a temp directory. It cannot call {@code main} — that
 * would mint real keys and write real resources — so the guard is pinned at the seam where the
 * decision is made, and the refusal message is asserted because the message is what an operator
 * reads at the moment they are one keystroke from an unrecoverable loss.
 */
public class RootCeremonyRefusesToClobberTheRootKeyTest {

    @Test
    public void anExistingKeyIsRefusedWithoutForce() throws Exception {
        Path dir = Files.createTempDirectory("root-ceremony-guard");
        Path key = dir.resolve("root-ed25519.key.b64");
        Files.writeString(key, "THE-ONLY-COPY-OF-THE-ROOT-PRIVATE-KEY\n");

        try {
            RootCeremonyCli.writeOwnerOnlyForTest(key, "a-different-key");
            fail("the writer overwrote the only root private key without --force");
        } catch (IOException expected) {
            assertTrue("the refusal must name the file: " + expected.getMessage(),
                    expected.getMessage().contains(key.toString()));
            assertTrue("and it must say what forcing it costs, because the message is what an "
                            + "operator reads one keystroke from an unrecoverable loss: "
                            + expected.getMessage(),
                    expected.getMessage().contains("--force"));
            assertTrue("and it must say the loss is not recoverable: " + expected.getMessage(),
                    expected.getMessage().contains("cannot be re-minted"));
        }

        assertTrue("THE FILE MUST BE UNTOUCHED -- that is the entire property",
                Files.readString(key).contains("THE-ONLY-COPY-OF-THE-ROOT-PRIVATE-KEY"));
    }

    @Test
    public void withForceTheWriteProceeds() throws Exception {
        Path dir = Files.createTempDirectory("root-ceremony-force");
        Path key = dir.resolve("root-ed25519.key.b64");
        Files.writeString(key, "old\n");
        RootCeremonyCli.writeOwnerOnlyForTest(key, "new", true);
        assertTrue("an explicit --force is the only way through",
                Files.readString(key).startsWith("new"));
    }

    @Test
    public void aFreshPathNeedsNoForceAtAll() throws Exception {
        Path dir = Files.createTempDirectory("root-ceremony-fresh");
        Path key = dir.resolve("root-ed25519.key.b64");
        RootCeremonyCli.writeOwnerOnlyForTest(key, "first");
        assertTrue(Files.readString(key).startsWith("first"));
    }

    /**
     * The two tools are siblings and must not drift apart again: if either drops its guard the
     * other has to notice, because the failure is invisible until a key is gone.
     */
    @Test
    public void theSiblingToolAlsoRefusesToOverwrite() throws Exception {
        Path dir = Files.createTempDirectory("kernel-keygen-guard");
        Path key = dir.resolve("kernel-ed25519.key.b64");
        Files.writeString(key, "old\n");
        try {
            KernelKeygenCli.writeOwnerOnlyForTest(key, "new", false);
            fail("KernelKeygenCli overwrote an existing key without --force");
        } catch (IOException | IllegalStateException expected) {
            assertTrue("must refuse: " + expected.getMessage(),
                    expected.getMessage().contains("--force")
                            || expected.getMessage().toLowerCase().contains("exist"));
        }
        assertTrue(Files.readString(key).startsWith("old"));
    }
}