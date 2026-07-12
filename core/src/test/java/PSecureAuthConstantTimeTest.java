import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import net.marcloud.mcp.core.alpc.AlpcServer;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.Ring;
import org.junit.Test;

/**
 * L1 VTL auth handshake: the shared-secret comparison must be constant-time
 * (MessageDigest.isEqual over UTF-8 bytes), mirroring SeClearancePolicy, so a
 * rejection does not leak the secret's length or first-diff position through
 * timing. See {@code AlpcServer.tokenMatches}.
 *
 * <p>This drives the private {@code tokenMatches} helper by reflection: on the
 * pre-fix code the inline {@code authToken.equals(presented)} check had no such
 * helper, so this test fails to resolve the method there (teeth). It asserts the
 * helper's correctness — including the equal-length wrong token that
 * {@code equals} would also reject, so behavior is provably preserved — and the
 * safe handling of non-String / null presented values. It does NOT measure wall-
 * clock timing; constant-time-ness rests on using MessageDigest.isEqual.
 */
public class PSecureAuthConstantTimeTest {

    private static Method tokenMatches() throws Exception {
        Method m = AlpcServer.class.getDeclaredMethod("tokenMatches", Object.class);
        m.setAccessible(true);
        return m;
    }

    private static AlpcServer serverWithToken(String token) {
        SeLocalMonitor authority =
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "restore"));
        // Not started: we only exercise the auth-comparison helper, no socket.
        return new AlpcServer(authority, 0, token);
    }

    private static boolean matches(AlpcServer server, Object presented) throws Exception {
        return (Boolean) tokenMatches().invoke(server, presented);
    }

    @Test
    public void acceptsCorrectToken() throws Exception {
        AlpcServer server = serverWithToken("the-real-secret");
        assertTrue("correct token must authenticate", matches(server, "the-real-secret"));
    }

    @Test
    public void rejectsEqualLengthWrongToken() throws Exception {
        // Same length as the secret, differing only in the last char: equals()
        // would reject this too, so the constant-time path must reject it as well.
        AlpcServer server = serverWithToken("the-real-secret");
        assertFalse("equal-length wrong token must be rejected",
                matches(server, "the-real-secreX"));
    }

    @Test
    public void rejectsDifferentLengthWrongToken() throws Exception {
        AlpcServer server = serverWithToken("the-real-secret");
        assertFalse("wrong token of different length must be rejected",
                matches(server, "nope"));
    }

    @Test
    public void rejectsNonStringPresentedValue() throws Exception {
        AlpcServer server = serverWithToken("the-real-secret");
        assertFalse("a non-String presented value must not authenticate",
                matches(server, Integer.valueOf(42)));
    }

    @Test
    public void rejectsNullPresentedValue() throws Exception {
        AlpcServer server = serverWithToken("the-real-secret");
        assertFalse("a null presented value must not authenticate",
                matches(server, null));
    }
}
