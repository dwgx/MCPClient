package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.regex.Pattern;
import java.nio.file.Path;

import org.junit.Test;

/**
 * A root document the ceremony produces must carry an expiry, because the client refuses one
 * that does not.
 *
 * <p>The defect: {@code RootCeremonyCli} built its {@code RootMetadata} with the five-argument
 * constructor and left the expiry at 0, and {@code metadataJson} had no {@code expires} field at
 * all. {@code RootTrust} reads a missing expiry as 0, and the §5.3.10 freeze rule in
 * {@link RootTrust} rejects a document that declares none — so the tool the project ships for
 * rotating the root of trust produced a document its own client would refuse. The rotation was
 * not an emergency: it had simply never been run on a key that mattered, so the hole was
 * invisible.
 *
 * <p>Reflected rather than called, because the ceremony needs real key files and a real signing
 * round to run, and this is a unit test. What is asserted is the decision the tool makes and the
 * field it emits, both of which are reachable without any of that.
 */
public class RootCeremonyDeclaresAnExpiryTest {

    private static final Path CLI = Path.of(
            "src/main/java/net/marcloud/mcp/core/compat/tools/RootCeremonyCli.java");

    private static String source() throws Exception {
        return Files.readString(CLI);
    }

    @Test
    public void theMetadataIsBuiltWithAnExpiryDerivedFromTheDays() throws Exception {
        String src = source();

        // Source text, and it has to be: the expiry is computed inside main() and reaching it
        // reflectively means running the whole ceremony with real key files. This used to assert
        // the literal arithmetic "expiresDays * 24L * 60L * 60L * 1000L" and the substring
        // "expires)", which is a formatting artefact -- renaming the local or reformatting the
        // expression turned the suite red with no behaviour change. What is actually being
        // claimed is structural, and is stated as two structural facts:
        //
        //   1. the day count is converted into a millisecond duration, and
        //   2. the result is handed to the RootMetadata constructor, which is the object that
        //      gets signed.
        //
        // The value itself is pinned behaviourally by the two reflective tests below, which read
        // the emitted JSON rather than the source that produced it.
        assertTrue("the ceremony must convert the day count into a duration: the client's freeze "
                + "rule rejects a root document that declares no expiry, so a ceremony that omits "
                + "it produces a document the client will not accept",
                Pattern.compile("expiresDays\\s*\\*").matcher(src).find());
        assertTrue("and it must reach the RootMetadata that gets signed, or the signature covers "
                + "a document that does not contain the value",
                Pattern.compile("new\\s+RootMetadata\\s*\\([^)]*expires", Pattern.DOTALL)
                        .matcher(src).find());
    }

    @Test
    public void theEmittedJsonCarriesTheExpiryField() throws Exception {
        Method m = Class.forName("net.marcloud.mcp.core.compat.tools.RootCeremonyCli")
                .getDeclaredMethod("metadataJson", RootMetadata.class,
                        java.security.PublicKey.class, java.security.PublicKey.class);
        m.setAccessible(true);

        long expiry = 1_800_000_000_000L;
        // A real ed25519 public key encoding is not needed to prove the field is emitted: the
        // method only concatenates the encoded form, and the assertion is about the key name.
        java.security.PublicKey anyKey = new java.security.PublicKey() {
            @Override
            public String getAlgorithm() {
                return "Ed25519";
            }

            @Override
            public String getFormat() {
                return "X.509";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[32];
            }
        };

        String json = (String) m.invoke(null,
                new RootMetadata(2, 1, java.util.Map.of("r", anyKey), java.util.Map.of("k", anyKey),
                        expiry),
                anyKey, anyKey);

        assertTrue("the signed document must carry the expiry, or RootTrust reads it as 0 and the "
                + "freeze rule rejects the very document the ceremony just produced: " + json,
                json.contains("\"expires\":" + expiry));
    }

    @Test
    public void aZeroOrNegativeExpiryIsRefusedRatherThanEmitted() throws Exception {
        Method main = Class.forName("net.marcloud.mcp.core.compat.tools.RootCeremonyCli")
                .getDeclaredMethod("main", String[].class);
        main.setAccessible(true);

        try {
            main.invoke(null, (Object) new String[]{
                "--keys", "k", "--resources", "r", "--expires-days", "0"});
            fail("--expires-days 0 must be refused: it is not a way to opt out of the freeze "
                    + "rule, it is a way to ship a document the client rejects");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue("expected the non-positive day count to be rejected, got: " + e.getCause(),
                    e.getCause() instanceof IllegalArgumentException);
            assertTrue("and the message must say why, since 0 looks like a legitimate 'never "
                    + "expires' to anyone who has not read RootTrust: " + e.getCause().getMessage(),
                    e.getCause().getMessage().contains("no expiry"));
        }
    }

    @Test
    public void theDefaultIsAPositiveDayCount() throws Exception {
        // A default of 0 would reintroduce the exact defect this test exists for, and it would
        // only bite on the runs where nobody passed the flag.
        String src = source();
        assertTrue("the default day count must be positive or the omission is silent breakage: "
                + "a ceremony run without --expires-days must still declare one",
                src.matches("(?s).*long expiresDays = \\d+L;.*"));
        assertFalse("a default of 0L reproduces the defect", src.contains("expiresDays = 0L;"));
        assertTrue("365 days is a year, not a decade: " + src,
                src.contains("expiresDays = 365L;"));
    }

    @Test
    public void theExpirySurvivesTheJsonReaderTheClientActuallyUses() throws Exception {
        // The point of the field is that RootTrust can read it back, and loadMetadata() reads a
        // fixed classpath resource, so it cannot be pointed at a string. What CAN be checked --
        // and is the real risk -- is that the value lands in the parsed map as a NUMBER.
        //
        // loadMetadata's expiry arm is `if (expires != null) { if (!(expires instanceof Number))
        // return null; }`: a quoted expiry is not a Number, so the document is REJECTED
        // wholesale. Emitting "expires":"1800000000000" would pass every assertion that only
        // looks for the field name and would break the very rotation this was added to enable.
        Method m = Class.forName("net.marcloud.mcp.core.compat.tools.RootCeremonyCli")
                .getDeclaredMethod("metadataJson", RootMetadata.class,
                        java.security.PublicKey.class, java.security.PublicKey.class);
        m.setAccessible(true);

        java.security.PublicKey anyKey = new java.security.PublicKey() {
            @Override
            public String getAlgorithm() {
                return "Ed25519";
            }

            @Override
            public String getFormat() {
                return "X.509";
            }

            @Override
            public byte[] getEncoded() {
                return new byte[32];
            }
        };

        long expiry = 1_800_000_000_000L;
        String json = (String) m.invoke(null,
                new RootMetadata(2, 1, java.util.Map.of("r", anyKey), java.util.Map.of("k", anyKey),
                        expiry),
                anyKey, anyKey);

        java.util.Map<String, Object> parsed = net.marcloud.mcp.core.io.http.Json.readObject(json);
        Object read = parsed.get("expires");
        assertTrue("the expiry must be emitted unquoted: loadMetadata returns null for a "
                + "declared-but-not-a-Number expiry, so a quoted one makes the client reject the "
                + "whole document. Emitted: " + json,
                read instanceof Number);
        assertEquals("and it must be the value that was signed",
                expiry, ((Number) read).longValue());
    }
}
