package net.marcloud.mcp.core.alpc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Shared crypto for the compat ALPC channel (v2):
 * <ul>
 *   <li>X25519 ephemeral ECDH for session binding / optional transport MAC material</li>
 *   <li>Ed25519 long-term identity for transcript + short-TTL tickets</li>
 *   <li>Canonical encodings for cross-end verification</li>
 * </ul>
 * Fail-closed callers must treat any thrown exception as "deny".
 */
public final class CompatCrypto {

    public static final String SUITE = "X25519/Ed25519/SHA-256";
    public static final int NONCE_LEN = 16;
    public static final long DEFAULT_TICKET_TTL_MS = 120_000L;

    private static final SecureRandom RNG = new SecureRandom();

    private CompatCrypto() {
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_LEN];
        RNG.nextBytes(n);
        return n;
    }

    public static KeyPair generateX25519() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
            kpg.initialize(NamedParameterSpec.X25519);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    public static KeyPair generateEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    public static PublicKey decodeSpki(String algorithm, byte[] spki) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad " + algorithm + " SPKI", e);
        }
    }

    public static PrivateKey decodePkcs8(String algorithm, byte[] pkcs8) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad " + algorithm + " PKCS8", e);
        }
    }

    public static byte[] x25519Shared(PrivateKey ourPriv, PublicKey theirPub) {
        try {
            javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("X25519");
            ka.init(ourPriv);
            ka.doPhase(theirPub, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("X25519 ECDH failed", e);
        }
    }

    /**
     * Session binding material (NOT the authorization root). HKDF-lite:
     * SHA-256(Z || salt || info) truncated to 32 bytes.
     */
    public static byte[] deriveSessionKey(byte[] sharedZ, byte[] clientNonce, byte[] serverNonce, String sessionId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sharedZ);
            md.update(clientNonce);
            md.update(serverNonce);
            md.update(sessionId.getBytes(StandardCharsets.UTF_8));
            md.update(SUITE.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("session KDF failed", e);
        }
    }

    /** Transcript signed by the authority long-term Ed25519 key (identity binding). */
    public static byte[] transcriptBytes(
            String protocolVer,
            byte[] clientPubSpki,
            byte[] serverPubSpki,
            byte[] clientNonce,
            byte[] serverNonce,
            String sessionId) {
        // Length-prefixed fields — deterministic, no delimiter ambiguity.
        ByteBuffer buf = ByteBuffer.allocate(
                4 + protocolVer.getBytes(StandardCharsets.UTF_8).length
                        + 4 + clientPubSpki.length
                        + 4 + serverPubSpki.length
                        + 4 + clientNonce.length
                        + 4 + serverNonce.length
                        + 4 + sessionId.getBytes(StandardCharsets.UTF_8).length
                        + 4 + SUITE.getBytes(StandardCharsets.UTF_8).length);
        putLenPrefixed(buf, protocolVer.getBytes(StandardCharsets.UTF_8));
        putLenPrefixed(buf, clientPubSpki);
        putLenPrefixed(buf, serverPubSpki);
        putLenPrefixed(buf, clientNonce);
        putLenPrefixed(buf, serverNonce);
        putLenPrefixed(buf, sessionId.getBytes(StandardCharsets.UTF_8));
        putLenPrefixed(buf, SUITE.getBytes(StandardCharsets.UTF_8));
        return buf.array();
    }

    /** Canonical ticket payload for Ed25519 (authorization root). */
    public static byte[] ticketSigningInput(
            String protocolVer,
            String sessionId,
            String patchId,
            String contentHash,
            String minClientVer,
            long epoch,
            long expEpochMs,
            byte[] nonce) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        // Grow if needed via wrap of assembled bytes instead — keep simple with builder.
        byte[] ver = protocolVer.getBytes(StandardCharsets.UTF_8);
        byte[] sid = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] pid = patchId.getBytes(StandardCharsets.UTF_8);
        byte[] ch = contentHash.getBytes(StandardCharsets.UTF_8);
        byte[] mcv = minClientVer.getBytes(StandardCharsets.UTF_8);
        int size = 4 + ver.length + 4 + sid.length + 4 + pid.length + 4 + ch.length
                + 4 + mcv.length + 8 + 8 + 4 + nonce.length;
        ByteBuffer out = ByteBuffer.allocate(size);
        putLenPrefixed(out, ver);
        putLenPrefixed(out, sid);
        putLenPrefixed(out, pid);
        putLenPrefixed(out, ch);
        putLenPrefixed(out, mcv);
        out.putLong(epoch);
        out.putLong(expEpochMs);
        putLenPrefixed(out, nonce);
        return out.array();
    }

    public static byte[] ed25519Sign(PrivateKey priv, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(message);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    public static boolean ed25519Verify(PublicKey pub, byte[] message, byte[] sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(message);
            return s.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    public static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    public static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static void putLenPrefixed(ByteBuffer buf, byte[] data) {
        buf.putInt(data.length);
        buf.put(data);
    }
}
