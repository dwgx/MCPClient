package net.marcloud.mcp.core.compat;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.se.SeProtectedObjects;

/**
 * The compat patch engine — the NT AppCompat / Shim Engine analogue. At premain,
 * before any {@code net.minecraft.*} class loads, it collects patches from a
 * {@link CompatDatabase}, verifies each via {@link PatchSigner}, filters by
 * {@link CompatPatch#appliesToRuntime()}, and registers a single
 * {@link ClassFileTransformer} that patches each affected vanilla class as it is
 * first loaded. The {@code client/} source is never edited; this is load-time
 * patching, not {@code ldr} hot-redefine.
 *
 * <p><b>One arming rule (no bypass).</b> A patch arms if and ONLY if
 * {@code signer.verify(manifest)} passes — a valid Ed25519 signature over the canonical
 * signing input under a key pinned in {@link TrustAnchors}. In-code registration confers
 * NO trust: an unsigned in-code patch never arms. With {@link TrustAnchors#empty() empty
 * anchors} the signer trusts nothing, so the engine arms nothing (fail-safe default).
 *
 * <p><b>Safety posture (signer-independent gauntlet, in addition to the signature):</b>
 * <ul>
 *   <li>A patch may never target a {@linkplain SeProtectedObjects protected} Core
 *       class — defense-in-depth so a compat patch cannot rewrite the guard itself,
 *       matching the same rule the redefine/hook installers enforce.</li>
 *   <li>Only {@code VERIFIED}-status, runtime-applicable patches arm; when an online
 *       authorized set is supplied, the patchId must be in it (ticket channel).</li>
 *   <li>A transform that throws is caught and treated as "no change" (original
 *       bytes preserved) — a buggy patch can never corrupt a class or crash class
 *       loading.</li>
 *   <li>No applicable patch for a class ⇒ the transformer returns null (no change),
 *       so class loading is untouched for everything else.</li>
 * </ul>
 */
public final class CompatEngine {

    /** Applicable, verified patches indexed by JVM internal class name (slashes). */
    private final Map<String, List<CompatPatch>> byInternalName;

    /** Content-addressed ids of the patches that passed the BUILD-time gauntlet
     *  (offline verify + protected-class + runtime + optional online snapshot).
     *  This is the set eligible to arm; whether one actually applies at load/redefine
     *  time is additionally gated by the live {@link #lease} when present. */
    private final java.util.Set<String> armedPatchIds;

    /** OPTIONAL live authorization lease (BLUE-1 fix). When non-null, {@link #apply}
     *  additionally requires each patch to be authorized RIGHT NOW — so a de-listed
     *  or lease-expired patch is disarmed at the moment of use, not frozen at build.
     *  Null = offline-only / static snapshot behavior (unchanged). */
    private volatile PatchLease lease;

    private CompatEngine(Map<String, List<CompatPatch>> byInternalName,
                         java.util.Set<String> armedPatchIds) {
        this.byInternalName = byInternalName;
        this.armedPatchIds = armedPatchIds;
    }

    /**
     * Attach a live authorization lease (BLUE-1). With a lease, {@link #apply} checks
     * {@code lease.isAuthorized(patchId)} at load/redefine time, so authorization is
     * evaluated at the moment of effect (not frozen at premain) and de-list / lease
     * expiry disarm an already-built patch. Pass null to detach (static behavior).
     */
    public void setLease(PatchLease lease) {
        this.lease = lease;
    }

    /** The live lease, or null when running offline-only / static snapshot. */
    public PatchLease lease() {
        return lease;
    }

    /**
     * Offline-only build: same as {@link #build(CompatDatabase, PatchSigner, java.util.Set)}
     * with an allow-all online set (every offline-verified patch is eligible). Used when
     * P-SECURE online tickets are disabled.
     */
    public static CompatEngine build(CompatDatabase db, PatchSigner signer) {
        return build(db, signer, null);
    }

    /**
     * Build the engine from a database, keeping only patches that (1) {@code VERIFIED}
     * status, (2) do not target a protected Core class, (3) {@code signer.verify(manifest)}
     * true (a valid Ed25519 signature against {@link TrustAnchors} — the ONLY arming path;
     * in-code registration grants no trust), (4) apply to the current runtime, and (5) when
     * {@code authorizedIds} is non-null, have a patchId in that online-authorized set (ALPC
     * ticket channel). Pass {@code null} for {@code authorizedIds} to skip the online filter
     * (offline-only path); an empty set arms nothing (fail-closed when the authority is
     * down). With empty anchors the signer trusts nothing, so nothing arms. Never throws.
     */
    public static CompatEngine build(
            CompatDatabase db, PatchSigner signer, java.util.Set<String> authorizedIds) {
        Map<String, List<CompatPatch>> index = new LinkedHashMap<>();
        java.util.Set<String> armed = new java.util.LinkedHashSet<>();
        int applied = 0;
        int skipped = 0;
        // TUF L1 — chain structure (承前 + 不可回退). supersedes is a tamper-evident hash pointer:
        // it names the predecessor by its content-addressed patchId, and PatchChain enforces that
        // a superseding patch (a) points at a well-formed predecessor and (b) carries a strictly
        // higher version than the predecessor it replaces when that predecessor is present (rollback
        // protection). A superseded patch does not arm — the newer chain tip wins. A cyclic
        // supersedes graph has no defined tip, so exactly the members of that cycle are refused
        // (scoped per-chain — F5); independent chains are unaffected.
        // This is the IN-CODE chain (order among registered patches); the signed, delivered
        // chain-metadata (L2 root/targets, L3 snapshot/timestamp) sign OVER this structure later.
        List<CompatPatch> all = db.all();
        // S3 F5: scope the cycle poison to its OWN chain. A supersedes cycle has no defined
        // tip, so patches whose patchId is a member of a cycle cannot arm and cannot suppress
        // a predecessor — but patches on OTHER, independent chains must still arm and still
        // supersede normally. The old code set a single global `cycle != null` flag that (a)
        // emptied supersededIds and (b) skipped EVERY patch carrying a supersedes value, so one
        // injected 2-cycle disabled all superseding patches engine-wide. cycleMembers holds
        // only the patchIds actually looping; the acyclic remainder is unaffected.
        java.util.Set<String> cycleMembers = PatchChain.findCycleMembers(all);
        if (!cycleMembers.isEmpty()) {
            System.err.println("[MCP Compat] REFUSING cyclic supersedes chain — patchIds "
                    + cycleMembers + " form a cycle (no defined chain tip); scoped to that chain "
                    + "only, other chains still arm.");
        }
        // Only a VALID, SIGNED, VERIFIED chain link may suppress its predecessor. Three
        // gates, all required:
        //   (1) valid chain link (PatchChain: well-formed prev pointer, same target,
        //       version strictly newer, not in a cycle),
        //   (2) status == VERIFIED,
        //   (3) a trusted Ed25519 signature (signer.verify).
        // Without (2)+(3) an UNSIGNED patch could set supersedes=<a signed patch's id> with
        // a higher version and silently DISARM that signed patch (which itself never arms) —
        // a real HIGH bug (S3 red-team F1, 2026-07-15): in-code registration confers NO trust
        // in the ARM direction, so it must confer none in the DISARM direction either. An
        // attacker who can register a CompatPatch object already has code-exec today, but this
        // becomes directly exploitable once patches ship as data — close it now.
        java.util.Set<String> supersededIds = new java.util.HashSet<>();
        for (CompatPatch patch : all) {
            PatchManifest m = patch.manifest();
            String s = m.supersedes();
            if (s == null || s.isBlank()) {
                continue;
            }
            // A patch that is itself a member of a cycle has no defined tip, so it may not
            // suppress anything (scoped to its own chain — F5). A patch on an acyclic chain
            // still supersedes its predecessor normally even if some UNRELATED chain loops.
            boolean inCycle = cycleMembers.contains(m.patchId());
            if (inCycle || PatchChain.rejectionReason(m, all) != null) {
                continue;
            }
            // A superseding patch may only suppress its predecessor if it would itself be
            // trusted to arm: VERIFIED status + a signature verifying against the anchors.
            if (m.status() != PatchManifest.Status.VERIFIED || !signer.verify(m)) {
                System.err.println("[MCP Compat] IGNORING supersede from " + m.code()
                        + " (patchId " + m.patchId() + ") — unverified/unsigned patches cannot "
                        + "disarm a signed predecessor.");
                continue;
            }
            supersededIds.add(s);
        }
        for (CompatPatch patch : db.all()) {
            PatchManifest m = patch.manifest();
            // Canonicalize the target to a JVM internal name (slashes) up front, and
            // gate the protected-class gard on its DOTTED form. The guard
            // (SeProtectedObjects) matches only dotted names, so a patch that names
            // its target in slash form ("net/marcloud/.../se/Ring") would otherwise
            // slip past isProtected() yet still match at dispatch (the transformer
            // keys on the JVM internal name) — rewriting a guard class un-gated. This
            // signer-INDEPENDENT backstop must hold regardless of the signer, so it
            // runs on the canonical name.
            String internal = internalName(m.targetClass());
            String dotted = internal.replace('/', '.');
            if (m.status() != PatchManifest.Status.VERIFIED) {
                skipped++;
                continue;
            }
            // Superseded by a newer registered patch -> do not arm the older one (it stays in the
            // database for the record). Reported as superseded, not armed.
            if (supersededIds.contains(m.patchId())) {
                System.err.println("[MCP Compat] SUPERSEDED patch " + m.code() + " (patchId "
                        + m.patchId() + ") — replaced by a newer registered patch; not arming.");
                skipped++;
                continue;
            }
            // TUF L1 (F5): a patch that is a MEMBER of a supersedes cycle has no defined chain
            // tip -> refuse just that patch. Patches on other, acyclic chains are untouched.
            if (cycleMembers.contains(m.patchId())) {
                System.err.println("[MCP Compat] REJECT patch " + m.code() + " (patchId "
                        + m.patchId() + "): member of a supersedes cycle — no defined chain tip.");
                skipped++;
                continue;
            }
            // TUF L1: reject an invalid chain link — a malformed prev pointer, a cross-target
            // supersede, or a version that does not exceed a PRESENT predecessor (rollback).
            String chainReason = PatchChain.rejectionReason(m, all);
            if (chainReason != null) {
                System.err.println("[MCP Compat] REJECT patch " + m.code()
                        + ": broken chain link — " + chainReason + ".");
                skipped++;
                continue;
            }
            if (SeProtectedObjects.isProtected(dotted)) {
                System.err.println("[MCP Compat] REJECT patch " + m.code() + ": targets protected class "
                        + m.targetClass());
                skipped++;
                continue;
            }
            // The ONE arming gate: a valid Ed25519 signature against TrustAnchors. No
            // bypass — in-code registration confers no trust. An unsigned/unverified
            // patch is skipped; with empty anchors the signer trusts nothing.
            if (!signer.verify(m)) {
                System.err.println("[MCP Compat] SKIP unverified patch " + m.code()
                        + " (targets " + m.targetClass() + ") — signature not trusted.");
                skipped++;
                continue;
            }
            // TUF L0 — content binding (UNSIGNED equality check, not a signature over
            // behavior). If the patch provides a behavior anchor (canaryClassBytes), the
            // hash recomputed from transform(canary) MUST equal the patch's constant
            // expectedCanaryHash(). NOTE: the Ed25519 signature does NOT cover this hash —
            // it covers a stable manifest label (PatchCanonicalizer). Both transform() and
            // expectedCanaryHash live in the same patch class, so L0 is drift-detection
            // (catches accidental/version changes), NOT adversarial binding — an attacker
            // with code-exec can edit both together. See known-issues KI-10 for the full
            // boundary. A patch with NO canary (legacy / the harmless IdentityProbe) is
            // exempt — signature-only, as before — so this is additive and never breaks an
            // anchor-less patch. Guard
            // the recompute so a bad canary can never break the boot transformer.
            boolean hasCanary;
            try {
                byte[] c = patch.canaryClassBytes();
                hasCanary = c != null && c.length > 0;
            } catch (Throwable t) {
                hasCanary = false;
            }
            if (hasCanary && !ContentHash.matchesExpected(patch)) {
                System.err.println("[MCP Compat] REJECT patch " + m.code()
                        + ": L0 content binding failed — the behavior hash recomputed from its "
                        + "canary does not match the author-pinned expectedCanaryHash (transform "
                        + "swapped/mutated since the fingerprint was pinned).");
                skipped++;
                continue;
            }
            if (!patch.appliesToRuntime()) {
                skipped++;
                continue;
            }
            if (authorizedIds != null && !authorizedIds.contains(m.patchId())) {
                System.err.println("[MCP Compat] SKIP patch " + m.code()
                        + " — not in online authorized set (ticket channel).");
                skipped++;
                continue;
            }
            index.computeIfAbsent(internal, k -> new ArrayList<>()).add(patch);
            armed.add(m.patchId());
            applied++;
        }
        System.err.println("[MCP Compat] engine built: " + applied + " patch(es) armed, "
                + skipped + " skipped.");
        return new CompatEngine(index, java.util.Set.copyOf(armed));
    }

    /**
     * Register the total transformer on {@code inst}. If no patches are armed this
     * is effectively a no-op transformer (returns null for every class), so it is
     * always safe to install.
     */
    public void install(Instrumentation inst) {
        if (inst == null) {
            System.err.println("[MCP Compat] no Instrumentation — compat patches disabled "
                    + "(start with -javaagent).");
            return;
        }
        inst.addTransformer(new PatchTransformer(), false);
    }

    /**
     * Convenience: build from the database + signer and install in one call, for the
     * OFFLINE path only (no online authorization). The premain boot path does NOT use
     * this — {@code Compat.igniteAtPremain} deliberately does build -&gt; setLease -&gt;
     * install so the live lease is attached before the transformer is registered (F3).
     */
    public static CompatEngine installFrom(Instrumentation inst, CompatDatabase db, PatchSigner signer) {
        return installFrom(inst, db, signer, null);
    }

    /**
     * Offline-only build+install. Rejects a non-null {@code authorizedIds}: an
     * online-filtered engine MUST attach a live {@link PatchLease} BEFORE the
     * transformer is registered, or de-list/TTL never re-check at apply time (the
     * BLUE-1 / F3 regression). Callers with online authorization must use
     * {@link #build(CompatDatabase, PatchSigner, java.util.Set)} then
     * {@link #setLease} then {@link #install} explicitly (as {@code
     * Compat.igniteAtPremain} does). This guard closes red-team finding F2.
     *
     * @throws IllegalArgumentException if {@code authorizedIds} is non-null
     */
    public static CompatEngine installFrom(
            Instrumentation inst,
            CompatDatabase db,
            PatchSigner signer,
            java.util.Set<String> authorizedIds) {
        if (authorizedIds != null) {
            throw new IllegalArgumentException(
                    "installFrom must not be used for the online path (authorizedIds != null): "
                    + "it would register the transformer with no lease (BLUE-1/F3 regression). "
                    + "Use build(...) -> setLease(seededLease) -> install(inst) explicitly.");
        }
        CompatEngine engine = build(db, signer, authorizedIds);
        engine.install(inst);
        return engine;
    }

    /** Internal names (slashes) currently armed — for tests/introspection. */
    public java.util.Set<String> armedInternalNames() {
        return java.util.Set.copyOf(byInternalName.keySet());
    }

    /**
     * Content-addressed ids of the patches actually armed. Per-PATCH, so a skipped
     * patch that happens to share a target class with an armed one is NOT reported
     * as armed (that is the truthful signal {@code list_compat_patches} needs).
     */
    public java.util.Set<String> armedPatchIds() {
        return armedPatchIds;
    }

    /**
     * Apply all armed patches for {@code internalName} to {@code original}, chaining
     * each patch's output into the next. Returns null if nothing changed (JDK
     * transformer convention). Package-visible so tests can exercise the dispatch
     * without a live Instrumentation.
     */
    byte[] apply(String internalName, byte[] original) {
        List<CompatPatch> patches = byInternalName.get(internalName);
        if (patches == null || patches.isEmpty()) {
            return null;
        }
        byte[] current = original;
        boolean changed = false;
        PatchLease live = lease;
        for (CompatPatch patch : patches) {
            // BLUE-1: authorization is evaluated at the MOMENT OF USE, not frozen at
            // build. A live lease that has expired, or that no longer lists this
            // patchId (authority de-listed it), disarms the patch here — even though
            // it passed the build-time gauntlet. Null lease = offline-only static
            // behavior (unchanged).
            if (live != null && !live.isAuthorized(patch.manifest().patchId())) {
                continue;
            }
            try {
                byte[] out = patch.transform(current);
                if (out != null && out != current) {
                    current = out;
                    changed = true;
                }
            } catch (Throwable t) {
                // A buggy patch must never corrupt the class or break loading.
                System.err.println("[MCP Compat] patch " + patch.manifest().code()
                        + " threw on " + internalName + "; keeping unpatched bytes: " + t);
            }
        }
        return changed ? current : null;
    }

    private static String internalName(String dotted) {
        return dotted.replace('.', '/');
    }

    /** The one transformer the engine installs; dispatches by loaded class name. */
    private final class PatchTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) {
                return null;
            }
            return apply(className, classfileBuffer);
        }
    }
}
