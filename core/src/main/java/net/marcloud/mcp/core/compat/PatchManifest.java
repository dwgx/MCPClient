package net.marcloud.mcp.core.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The identity + provenance of one compat patch — the NT AppCompat analogue of a
 * shim's {@code .inf} description plus its {@code .cat} catalog hash. Immutable.
 *
 * <p>See {@code .ai-notes/docs/architecture/07-COMPAT-SHIM.md} for the field↔Windows
 * mapping. A patch is trusted only when {@link #signature} is a valid Ed25519
 * signature over its canonical signing input (target class + {@link #contentHash} +
 * keyId) under a key pinned in {@link TrustAnchors} (checked by {@link PatchSigner} /
 * {@link Ed25519PatchSigner}).
 *
 * <p><b>Content addressing.</b> {@link #patchId} is derived deterministically from
 * the target class, the transform's content hash, the KI reference and the
 * publisher, so any change to what the patch does (its transform bytes) yields a
 * different id — a patch cannot silently mutate under a fixed identity. Build one
 * via {@link Builder}, then bind the transform hash + signature through
 * {@link #withTransform(String, String)} once the transform is known.
 */
public final class PatchManifest {

    /** Trust/lifecycle state of a patch. Mirrors a shim's enabled/superseded flags. */
    public enum Status {
        /** Backed by a confirmed KI (evidence) and intended to apply. */
        VERIFIED,
        /** Replaced by a newer patch ({@link #supersedes}); kept for the record. */
        SUPERSEDED,
        /** Present in the database but deliberately not applied. */
        DISABLED
    }

    private final String patchId;
    private final String code;
    private final String name;
    private final String version;
    private final String kiRef;
    private final String targetClass;
    private final String platformCondition;
    private final String publisher;
    private final String builtAt;
    private final String contentHash;
    private final String signature;
    private final String supersedes;
    private final String evidence;
    private final Status status;

    private PatchManifest(Builder b, String contentHash, String signature, String patchId) {
        this.code = require(b.code, "code");
        this.name = require(b.name, "name");
        this.version = b.version == null ? "1.0.0.0" : b.version;
        this.kiRef = require(b.kiRef, "kiRef");
        this.targetClass = require(b.targetClass, "targetClass");
        this.platformCondition = b.platformCondition == null ? "" : b.platformCondition;
        this.publisher = require(b.publisher, "publisher");
        this.builtAt = require(b.builtAt, "builtAt");
        this.evidence = b.evidence == null ? "" : b.evidence;
        this.supersedes = b.supersedes; // nullable by design
        this.status = b.status == null ? Status.VERIFIED : b.status;
        this.contentHash = contentHash; // nullable until withTransform
        this.signature = signature;     // nullable until withTransform/withSignature
        this.patchId = patchId;         // nullable until withTransform
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("PatchManifest." + field + " is required");
        }
        return v;
    }

    // ---- identity / provenance accessors -----------------------------------

    /** Content-addressed id, or null before {@link #withTransform}. */
    public String patchId() {
        return patchId;
    }

    /** Human patch number, e.g. {@code MCP-KI0001} (KB-number analogue). */
    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** The confirmed known-issue this patch closes, e.g. {@code KI-1}. */
    public String kiRef() {
        return kiRef;
    }

    /** Dotted FQN of the vanilla class this patch transforms. */
    public String targetClass() {
        return targetClass;
    }

    public String platformCondition() {
        return platformCondition;
    }

    public String publisher() {
        return publisher;
    }

    public String builtAt() {
        return builtAt;
    }

    /** Hash of the transform's logic, or null before {@link #withTransform}. */
    public String contentHash() {
        return contentHash;
    }

    /**
     * The Ed25519 integrity signature in wire form {@code ed25519:v1:<keyId>:<b64url>},
     * or null when unsigned. Verified by {@link Ed25519PatchSigner} against the
     * canonical signing input (see {@link PatchCanonicalizer}).
     */
    public String signature() {
        return signature;
    }

    /** patchId this supersedes, or null. */
    public String supersedes() {
        return supersedes;
    }

    public String evidence() {
        return evidence;
    }

    public Status status() {
        return status;
    }

    /** True once a transform hash + content-addressed id have been bound. */
    public boolean isBound() {
        return patchId != null && contentHash != null;
    }

    // ---- binding the transform ---------------------------------------------

    /**
     * Bind this manifest to its transform: compute the content hash, derive the
     * content-addressed {@link #patchId}, and attach {@code signature} (may be null
     * = unsigned, which {@link PatchSigner} will refuse to trust). Returns a new
     * manifest; the receiver is unchanged.
     *
     * @param transformHash a stable hash of the transform's bytecode/logic
     *                       (the patch author computes this over what the transform
     *                       actually does, e.g. sha256 of the emitted class bytes)
     * @param signature      the Ed25519 signature in wire form
     *                       {@code ed25519:v1:<keyId>:<b64url>}, or null if unsigned
     */
    public PatchManifest withTransform(String transformHash, String signature) {
        String ch = require(transformHash, "transformHash");
        String id = derivePatchId(targetClass, ch, kiRef, publisher);
        return new PatchManifest(toBuilder(), ch, signature, id);
    }

    /** Attach/replace the signature on an already-bound manifest. */
    public PatchManifest withSignature(String signature) {
        if (!isBound()) {
            throw new IllegalStateException("cannot sign an unbound manifest; call withTransform first");
        }
        return new PatchManifest(toBuilder(), contentHash, signature, patchId);
    }

    private Builder toBuilder() {
        return new Builder()
                .code(code).name(name).version(version).kiRef(kiRef)
                .targetClass(targetClass).platformCondition(platformCondition)
                .publisher(publisher).builtAt(builtAt).supersedes(supersedes)
                .evidence(evidence).status(status);
    }

    /** Ordered map for JSON display (list_compat_patches). Null fields render as "". */
    public Map<String, Object> toDisplayMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patchId", nz(patchId));
        m.put("code", code);
        m.put("name", name);
        m.put("version", version);
        m.put("kiRef", kiRef);
        m.put("targetClass", targetClass);
        m.put("platformCondition", platformCondition);
        m.put("publisher", publisher);
        m.put("builtAt", builtAt);
        m.put("contentHash", nz(contentHash));
        m.put("signed", signature != null && !signature.isBlank());
        m.put("supersedes", nz(supersedes));
        m.put("evidence", evidence);
        m.put("status", status.name());
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---- hashing helpers ----------------------------------------------------

    /**
     * Deterministic content-addressed id: {@code sha256(targetClass | transformHash
     * | kiRef | publisher)}, hex, prefixed. Changing the transform (its hash)
     * changes the id, so identity is bound to behavior.
     */
    public static String derivePatchId(String targetClass, String transformHash,
                                       String kiRef, String publisher) {
        String canonical = targetClass + "|" + transformHash + "|" + kiRef + "|" + publisher;
        return "cp-" + sha256Hex(canonical);
    }

    /** Lowercase hex SHA-256 of a UTF-8 string. */
    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- builder ------------------------------------------------------------

    public static final class Builder {
        private String code;
        private String name;
        private String version;
        private String kiRef;
        private String targetClass;
        private String platformCondition;
        private String publisher;
        private String builtAt;
        private String supersedes;
        private String evidence;
        private Status status;

        public Builder code(String v) { this.code = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder kiRef(String v) { this.kiRef = v; return this; }
        public Builder targetClass(String v) { this.targetClass = v; return this; }
        public Builder platformCondition(String v) { this.platformCondition = v; return this; }
        public Builder publisher(String v) { this.publisher = v; return this; }
        public Builder builtAt(String v) { this.builtAt = v; return this; }
        public Builder supersedes(String v) { this.supersedes = v; return this; }
        public Builder evidence(String v) { this.evidence = v; return this; }
        public Builder status(Status v) { this.status = v; return this; }

        /** Build an UNBOUND manifest (no transform hash / patchId yet). */
        public PatchManifest build() {
            return new PatchManifest(this, null, null, null);
        }
    }
}
