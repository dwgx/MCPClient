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
 * <p><b>Safety posture:</b>
 * <ul>
 *   <li>Only <b>signature-verified</b> patches are applied — an unverified patch is
 *       skipped with a log line. With the shipped {@link UnsignedPatchSigner} that
 *       means zero patches apply (fail-safe until the crypto core lands).</li>
 *   <li>A patch may never target a {@linkplain SeProtectedObjects protected} Core
 *       class — defense-in-depth so a compat patch cannot rewrite the guard itself,
 *       matching the same rule the redefine/hook installers enforce.</li>
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

    /** Content-addressed ids of the patches actually armed — per-patch, not per-target. */
    private final java.util.Set<String> armedPatchIds;

    private CompatEngine(Map<String, List<CompatPatch>> byInternalName,
                         java.util.Set<String> armedPatchIds) {
        this.byInternalName = byInternalName;
        this.armedPatchIds = armedPatchIds;
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
     * Build the engine from a database, keeping only patches that (1) verify under
     * {@code signer}, (2) apply to the current runtime, (3) do not target a protected
     * Core class, and (4) when {@code authorizedIds} is non-null, have a patchId in
     * that online-authorized set (ALPC ticket channel). Pass {@code null} to skip the
     * online filter (offline-only path). An empty set arms nothing (fail-closed when
     * the authority is down). Never throws.
     */
    public static CompatEngine build(
            CompatDatabase db, PatchSigner signer, java.util.Set<String> authorizedIds) {
        Map<String, List<CompatPatch>> index = new LinkedHashMap<>();
        java.util.Set<String> armed = new java.util.LinkedHashSet<>();
        int applied = 0;
        int skipped = 0;
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
            if (SeProtectedObjects.isProtected(dotted)) {
                System.err.println("[MCP Compat] REJECT patch " + m.code() + ": targets protected class "
                        + m.targetClass());
                skipped++;
                continue;
            }
            if (!signer.verify(m)) {
                System.err.println("[MCP Compat] SKIP unverified patch " + m.code()
                        + " (targets " + m.targetClass() + ") — signature not trusted.");
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
     * Convenience: build from the database + signer and install in one call. Used by
     * the premain boot path.
     */
    public static CompatEngine installFrom(Instrumentation inst, CompatDatabase db, PatchSigner signer) {
        return installFrom(inst, db, signer, null);
    }

    public static CompatEngine installFrom(
            Instrumentation inst,
            CompatDatabase db,
            PatchSigner signer,
            java.util.Set<String> authorizedIds) {
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
        for (CompatPatch patch : patches) {
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
