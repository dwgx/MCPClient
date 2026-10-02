package net.marcloud.mcp.core.compat;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Per-patch record of what the transform ACTUALLY did, keyed by content-addressed patchId.
     *
     * <p><b>These are measurements, not authorisations.</b> {@link #armedPatchIds()} answers
     * "was this patch permitted to run"; this record answers "when it ran, did it do
     * anything". The two are independent, and conflating them is how a patch armed, verified
     * and silently changed nothing — {@code GlClampToEdgePatch} matched {@code LDC} where javac
     * emits {@code SIPUSH}, so its transform returned null on every load and the only
     * observable was {@code armed: true}.
     *
     * <p><b>Per-process.</b> Counters start at zero when the engine is built and reset on
     * restart. They are not fleet history and must not be presented as an attestation that a
     * patch is correct: {@code targetsChanged > 0} says the engine handed the JVM a different
     * byte array, not that the rewrite was semantically right.
     *
     * <p><b>{@code targetsChanged} counts invocations, not distinct classes.</b> The engine
     * installs with {@code addTransformer(..., false)} (no retransformation), so today the two
     * coincide; if redefine-time application is ever added, this number becomes "times" and a
     * caller must not read it as a class count.
     *
     * @param patchId           the content-addressed patch id, never null
     * @param transformRuns     times {@link CompatPatch#transform(byte[])} was invoked
     * @param targetsChanged    of those, how many returned bytes different from what they were
     *                          given (reference inequality — what the JVM acts on)
     * @param unauthorizedAtUse times the live lease refused the patch at the moment of use
     * @param applyFailures     of those runs, how many threw
     * @param lastApplyError    {@code ""} when none, else {@code "<internalName>: <throwable>"}
     */
    public record ApplyRecord(String patchId,
                              int transformRuns,
                              int targetsChanged,
                              int unauthorizedAtUse,
                              int applyFailures,
                              String lastApplyError) {

        /** The all-zero record: never executed, never refused, never failed. */
        public static ApplyRecord none(String patchId) {
            return new ApplyRecord(patchId, 0, 0, 0, 0, "");
        }
    }

    /**
     * Mutable, thread-safe accumulator. Identity is SHARED between the dispatch index and
     * the reporter, so the two can never disagree. {@code apply} runs on class-loading
     * threads, so the counters are atomics and the error string is volatile; no reader needs
     * a cross-patch consistent snapshot, so there is deliberately no lock on the class-load
     * path.
     */
    private static final class ApplyCounter {
        private final String patchId;
        private final AtomicInteger transformRuns = new AtomicInteger();
        private final AtomicInteger targetsChanged = new AtomicInteger();
        private final AtomicInteger unauthorizedAtUse = new AtomicInteger();
        private final AtomicInteger applyFailures = new AtomicInteger();
        private volatile String lastApplyError = "";

        ApplyCounter(String patchId) {
            this.patchId = patchId;
        }

        ApplyRecord snapshot() {
            return new ApplyRecord(patchId, transformRuns.get(), targetsChanged.get(),
                    unauthorizedAtUse.get(), applyFailures.get(), lastApplyError);
        }
    }

    /** One entry in the dispatch index: the patch plus the counter that measures it. Carrying
     *  the counter INSIDE the index entry is what keeps the hot path map-lookup-free. */
    private record Armed(CompatPatch patch, ApplyCounter counter) { }

    /** Applicable, verified patches indexed by JVM internal class name (slashes). */
    private final Map<String, List<Armed>> byInternalName;

    /** Content-addressed ids of the patches that passed the BUILD-time gauntlet
     *  (offline verify + protected-class + runtime + optional online snapshot).
     *  This is the set eligible to arm; whether one actually applies at load/redefine
     *  time is additionally gated by the live {@link #lease} when present. */
    private final java.util.Set<String> armedPatchIds;

    /** One pre-created accumulator per armed patch, in build-time registration order.
     *  Populated in {@link #build} alongside {@link #armedPatchIds} so that "armed but never
     *  recorded" is not representable — absence is never a state here. */
    private final Map<String, ApplyCounter> counters;

    /** OPTIONAL live authorization lease (BLUE-1 fix). When non-null, {@link #apply}
     *  additionally requires each patch to be authorized RIGHT NOW — so a de-listed
     *  or lease-expired patch is disarmed at the moment of use, not frozen at build.
     *  Null = offline-only / static snapshot behavior (unchanged). */
    private volatile PatchLease lease;

    private CompatEngine(Map<String, List<Armed>> byInternalName,
                         java.util.Set<String> armedPatchIds,
                         Map<String, ApplyCounter> counters) {
        this.byInternalName = byInternalName;
        this.armedPatchIds = armedPatchIds;
        this.counters = java.util.Collections.unmodifiableMap(counters);
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
        Map<String, List<Armed>> index = new LinkedHashMap<>();
        Map<String, ApplyCounter> counters = new LinkedHashMap<>();
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
            // behavior). If the patch DECLARES a behavior anchor (a pinned
            // expectedCanaryHash), the hash recomputed from transform(canary) MUST equal
            // it. NOTE: the Ed25519 signature does NOT cover this hash — it covers a
            // stable manifest label (PatchCanonicalizer). Both transform() and
            // expectedCanaryHash live in the same patch class, so L0 is drift-detection
            // (catches accidental/version changes), NOT adversarial binding — an attacker
            // with code-exec can edit both together. See known-issues KI-10 for the full
            // boundary. A patch that declares NO hash (legacy / the harmless
            // IdentityProbe) is exempt — signature-only, as before — so this is additive
            // and never breaks an anchor-less patch.
            //
            // The exemption is decided from the DECLARATION, never from whether the
            // canary happens to materialise. Deriving it from canaryClassBytes() (and
            // from a catch that also cleared the flag) meant a patch that pins a hash but
            // ships a null/empty/throwing canary armed with ZERO behavior verification —
            // the gate failed OPEN on exactly the patches it exists to stop. That is the
            // opposite of ContentHash.matchesExpected's stated rule: a patch that
            // DECLARES a fingerprint and cannot match it does not arm.
            if (ContentHash.declaresExpectedHash(patch) && !ContentHash.matchesExpected(patch)) {
                System.err.println("[MCP Compat] REJECT patch " + m.code()
                        + ": L0 content binding failed — the patch pins an expectedCanaryHash "
                        + "but the behavior hash recomputed from its canary does not match it "
                        + "(transform swapped/mutated since the fingerprint was pinned, or the "
                        + "canary is missing/empty/throwing, so the binding is unprovable).");
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
            // The accumulator is created HERE, beside the arming, not lazily at apply time: an
            // armed patch always has a record, so "no record" can never be mistaken for "no
            // evidence that it failed". The SAME instance travels in the index entry and in
            // the counters map, so dispatch and report cannot drift apart.
            ApplyCounter counter = new ApplyCounter(m.patchId());
            index.computeIfAbsent(internal, k -> new ArrayList<>()).add(new Armed(patch, counter));
            counters.put(m.patchId(), counter);
            armed.add(m.patchId());
            applied++;
        }
        System.err.println("[MCP Compat] engine built: " + applied + " patch(es) armed, "
                + skipped + " skipped.");
        return new CompatEngine(index, java.util.Set.copyOf(armed), counters);
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
     * Immutable snapshot of every ARMED patch's runtime record, in build-time registration
     * order. {@code keySet()} is exactly {@link #armedPatchIds()}.
     *
     * <p>Allocates; call it from a reporter, never from the class-load path. Counts are
     * per-process and reset on restart. A snapshot taken while classes are loading may mix
     * a pre-increment and a post-increment reading of the same patch — that is intentional,
     * and no reader needs a cross-patch consistent view.
     */
    public java.util.Map<String, ApplyRecord> applyRecords() {
        java.util.LinkedHashMap<String, ApplyRecord> out = new java.util.LinkedHashMap<>();
        for (ApplyCounter c : counters.values()) {
            out.put(c.patchId, c.snapshot());
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * The runtime record for one patchId. <b>Never null</b>: an unknown, unarmed or never-run
     * patchId yields an all-zero {@link ApplyRecord}, so a missing record is never readable
     * as "no evidence of failure". An unarmed patch and an armed-but-never-executed one do
     * report the same zeros here — {@link #armedPatchIds()} is what separates them.
     */
    public ApplyRecord applyRecord(String patchId) {
        ApplyCounter c = counters.get(patchId);
        return c == null ? ApplyRecord.none(patchId) : c.snapshot();
    }

    /**
     * Apply all armed patches for {@code internalName} to {@code original}, chaining
     * each patch's output into the next. Returns null if nothing changed (JDK
     * transformer convention). Package-visible so tests can exercise the dispatch
     * without a live Instrumentation.
     */
    byte[] apply(String internalName, byte[] original) {
        List<Armed> patches = byInternalName.get(internalName);
        if (patches == null || patches.isEmpty()) {
            return null;
        }
        byte[] current = original;
        boolean changed = false;
        PatchLease live = lease;
        for (Armed armed : patches) {
            CompatPatch patch = armed.patch();
            ApplyCounter c = armed.counter();
            // BLUE-1: authorization is evaluated at the MOMENT OF USE, not frozen at
            // build. A live lease that has expired, or that no longer lists this
            // patchId (authority de-listed it), disarms the patch here — even though
            // it passed the build-time gauntlet. Null lease = offline-only static
            // behavior (unchanged). The refusal is counted, so "authorised at build,
            // refused at use" is a state a caller can see.
            if (live != null && !live.isAuthorized(patch.manifest().patchId())) {
                c.unauthorizedAtUse.incrementAndGet();
                continue;
            }
            try {
                c.transformRuns.incrementAndGet();
                byte[] out = patch.transform(current);
                if (out != null && out != current) {
                    current = out;
                    changed = true;
                    c.targetsChanged.incrementAndGet();
                }
            } catch (Throwable t) {
                // A buggy patch must never corrupt the class or break loading — fail-open
                // stays fail-open, because throwing out of a ClassFileTransformer would drop
                // the class. What changes is that it is no longer SILENT and no longer
                // unattributable: the throw is counted against this patchId and the last
                // one is recorded, so "armed and then threw" is a third state, distinct
                // from both "ran and changed nothing" and "never ran".
                c.applyFailures.incrementAndGet();
                c.lastApplyError = internalName + ": " + t;
                // patchId is in the line because patchId — not code() — is the key the
                // report is keyed on; without it this line cannot be joined back to a row.
                System.err.println("[MCP Compat] patch " + patch.manifest().code()
                        + " (patchId " + patch.manifest().patchId() + ") threw on " + internalName
                        + "; keeping unpatched bytes: " + t);
            }
        }
        // UNCHANGED, and must stay so: the ClassFileTransformer contract. Null means "no
        // change"; a non-null return makes the JVM install these bytes, which for an
        // unchanged class would be pointless work.
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
