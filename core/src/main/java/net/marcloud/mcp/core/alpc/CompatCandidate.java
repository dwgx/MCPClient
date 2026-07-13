package net.marcloud.mcp.core.alpc;

/**
 * One candidate patch offered for online ticket authorization. Bound fields must
 * match {@code PatchManifest.patchId()} / {@code contentHash()} — authorization
 * must never bind id alone (crypto-core v2).
 */
public record CompatCandidate(String patchId, String contentHash) {
    public CompatCandidate {
        if (patchId == null || patchId.isBlank()) {
            throw new IllegalArgumentException("patchId required");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash required");
        }
    }
}
