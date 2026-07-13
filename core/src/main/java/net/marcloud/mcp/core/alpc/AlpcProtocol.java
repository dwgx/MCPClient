package net.marcloud.mcp.core.alpc;

/**
 * The wire contract between the game JVM (P-NORMAL) and the P-SECURE decision
 * process (L1 VTL). Newline-delimited JSON over a 127.0.0.1 socket, framed
 * exactly like {@link net.marcloud.mcp.core.io.transport.SocketTransportServer} but with a
 * trivial request/response schema and a mandatory auth handshake.
 *
 * <p><b>Security posture.</b> The game JVM sends only the <i>tool identity</i>
 * (name, builtIn, and the argument keys/types it wants validated) — never its own
 * subject. The P-SECURE process holds the authoritative {@link
 * net.marcloud.mcp.core.se.SeReferenceMonitor} + subject and returns the verdict.
 * This is the point of the separate address space: a compromised in-JVM hook
 * cannot forge a wide-open subject to smuggle past the gate, because it never
 * gets to state the subject.
 *
 * <p>Frames:
 * <pre>
 *   handshake →  {"auth":"&lt;shared-secret&gt;"}
 *   handshake ←  {"authenticated":true}                (else the socket is closed)
 *   decide    →  {"id":N,"method":"evaluate","tool":"redefine_class","builtIn":true}
 *   decide    ←  {"id":N,"allow":false,"layer":"L4 privilege","reason":"..."}
 *   admin     →  {"id":N,"method":"clearance|dropTo|tryRestore|restorable",...}
 * </pre>
 *
 * <p>All keys are constants here so client and server never drift.
 */
public final class AlpcProtocol {

    private AlpcProtocol() {
    }

    /** Default loopback port for the P-SECURE process. */
    public static final int DEFAULT_PORT = 25601;

    /** System property naming the shared auth secret (both processes read it). */
    public static final String TOKEN_PROPERTY = "mcp.core.psecureToken";

    /** System property enabling the remote engine in the game JVM. */
    public static final String ENABLE_PROPERTY = "mcp.core.psecure";

    // request keys
    public static final String K_AUTH = "auth";
    public static final String K_AUTHED = "authenticated";
    public static final String K_ID = "id";
    public static final String K_METHOD = "method";
    public static final String K_TOOL = "tool";
    public static final String K_BUILTIN = "builtIn";
    public static final String K_TARGET = "target";
    public static final String K_TOKEN = "token";

    // response keys
    public static final String K_ALLOW = "allow";
    public static final String K_LAYER = "layer";
    public static final String K_REASON = "reason";
    public static final String K_CLEARANCE = "clearance";
    public static final String K_RESULT = "result";
    public static final String K_RESTORABLE = "restorable";

    // methods
    public static final String M_EVALUATE = "evaluate";
    public static final String M_CLEARANCE = "clearance";
    public static final String M_DROP_TO = "dropTo";
    public static final String M_TRY_RESTORE = "tryRestore";
    public static final String M_RESTORABLE = "restorable";

    // ---- additive compat channel (v2: short-TTL Ed25519 tickets) ----
    // Methods / keys are purely additive. A compat-unaware authority hits the
    // server default case and returns {allow:false, reason:"unknown method"};
    // the client treats missing K_COMPAT_* fields as fail-closed.

    /** Ephemeral X25519 session binding + server identity (Ed25519 over transcript). */
    public static final String M_COMPAT_HELLO = "compatHello";
    /** Request short-TTL Ed25519 tickets for candidate patches (v2 authorization root). */
    public static final String M_COMPAT_TICKET = "compatTicket";

    public static final String K_COMPAT_CLIENT_PUB = "compatClientPub";
    public static final String K_COMPAT_CLIENT_NONCE = "compatClientNonce";
    public static final String K_COMPAT_SERVER_PUB = "compatServerPub";
    public static final String K_COMPAT_SERVER_NONCE = "compatServerNonce";
    public static final String K_COMPAT_SESSION = "compatSession";
    public static final String K_COMPAT_TRANSCRIPT_SIG = "compatTranscriptSig";
    public static final String K_COMPAT_KEY_ID = "compatKeyId";
    public static final String K_COMPAT_PROTOCOL_VER = "compatProtocolVer";
    public static final String K_COMPAT_PATCHES = "compatPatches";
    public static final String K_COMPAT_TICKETS = "compatTickets";
    public static final String K_COMPAT_REASON = "compatReason";
    public static final String K_COMPAT_CLIENT_VER = "compatClientVer";

    /** Wire protocol version for hello/ticket frames (pinned into transcript + tickets). */
    public static final String COMPAT_PROTOCOL_VER = "1";

    /** System property: base64 X.509 SPKI of the authority long-term Ed25519 public key. */
    public static final String COMPAT_PUBKEY_PROPERTY = "mcp.core.compatAuthorityPub";
}
