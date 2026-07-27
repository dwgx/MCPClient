#!/usr/bin/env bash
# ============================================================================
#  sign-patch.sh — the offline signing ceremony for a compat patch.
#
#  WHY THIS EXISTS
#    A patch arms if and only if its Ed25519 signature verifies against the
#    kernel key baked into the client. The canonical signing input covers NINE
#    fields (targetClass, contentHash, keyId, status, kiRef, publisher, version,
#    supersedes, platformCondition) and every one must match the shipped manifest
#    EXACTLY — one character off and the signature simply does not verify, with
#    no diagnostic beyond "signature not trusted". Assembling that by hand is a
#    trap, so this script reads the values off the patch class itself and then
#    VERIFIES the result before printing it.
#
#  WHAT IT DOES NOT DO
#    It does not hold, generate, or store a private key. You pass the path to
#    one; it is never copied, never logged, and never written anywhere. The
#    matching PUBLIC key is what ships (core/src/main/resources/.../
#    kernel-ed25519.pub), and embedding only that grants no forging power.
#
#  USAGE
#    scripts/sign-patch.sh --privkey <path-to-pkcs8-ed25519.key.b64> \
#                          [--patch Ki11DwmHotkeyPatch]
#
#    On success it prints the signature string and the exact source edit to make.
#    Then rebuild and confirm the kernel's own report says the patch armed.
#
#  EXIT CODES
#    0  signed AND verified
#    1  signing produced a signature that does NOT verify (do not ship it)
#    3  setup problem (missing key file, unbuilt core, no JDK)
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

PRIVKEY=""
PATCH_CLASS="Ki11DwmHotkeyPatch"

while [ $# -gt 0 ]; do
  case "$1" in
    --privkey) PRIVKEY="${2:-}"; shift 2 ;;
    --patch)   PATCH_CLASS="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 3 ;;
  esac
done

fail_setup() { echo "SETUP: $*" >&2; exit 3; }

[ -n "$PRIVKEY" ] || fail_setup "--privkey is required (path to the kernel PKCS#8 Ed25519 key, base64)"
[ -f "$PRIVKEY" ] || fail_setup "no such key file: $PRIVKEY"

# --- JDK 25, the same picker the launch scripts use. ---
pick_java() {
  for cand in \
      "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
      "$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java" \
      "$HOME"/.jdks/jdk-25*/Contents/Home/bin/java \
      "$HOME"/.jdks/jdk-25*/bin/java; do
    [ -x "$cand" ] || continue
    case "$("$cand" -version 2>&1 | head -1)" in *\"25*) echo "$cand"; return 0 ;; esac
  done
  command -v java
}
JAVA="$(pick_java)"
[ -x "$JAVA" ] || fail_setup "no JDK 25 found (set JAVA_HOME)"

CLASSES="$ROOT/core/target/classes"
[ -d "$CLASSES" ] || fail_setup "core is not built — run: ./mvnw -q -pl core -am package -DskipTests"

CP_CACHE="$ROOT/core/target/signing-classpath.txt"
if [ ! -f "$CP_CACHE" ]; then
  echo "resolving core's classpath..."
  ( cd "$ROOT" && ./mvnw -q -ntp -pl core dependency:build-classpath \
      -Dmdep.outputFile="$CP_CACHE" ) || fail_setup "could not resolve core's dependencies"
fi
CP="$CLASSES:$(cat "$CP_CACHE")"

# The work happens in Java, not in shell: the canonical signing input is defined by
# PatchCanonicalizer, and the only way to be sure the ceremony matches it is to build the
# manifest from the patch class and let the signer sign that. Reimplementing the field order
# in bash would be a second source of truth for exactly the thing that must not drift.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/SignCeremony.java" <<'JAVA'
import java.nio.file.*;
import java.security.PrivateKey;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.compat.*;

/**
 * Signs one in-code patch and then PROVES the signature verifies, by reading the fields off
 * the patch itself rather than off a command line. Every covered field therefore matches the
 * shipped manifest by construction.
 */
public final class SignCeremony {
    public static void main(String[] args) throws Exception {
        String simpleName = args[0];
        Path keyFile = Path.of(args[1]);

        CompatPatch patch = (CompatPatch) Class
            .forName("net.marcloud.mcp.core.compat.patches." + simpleName)
            .getDeclaredConstructor().newInstance();
        PatchManifest shipped = patch.manifest();

        // The transform hash the shipped manifest is already bound to. Reusing it (rather than
        // recomputing from a seed passed in) is what keeps the signature bound to THIS build's
        // declared content hash.
        String transformHash = shipped.contentHash();
        if (transformHash == null || transformHash.isBlank()) {
            System.err.println("FAIL: " + simpleName + " has no contentHash to sign over.");
            System.exit(1);
        }

        String b64 = Files.readString(keyFile).trim();
        if (b64.isEmpty()) {
            System.err.println("FAIL: the key file is empty.");
            System.exit(3);
        }
        PrivateKey priv = CompatCrypto.decodePkcs8("Ed25519", CompatCrypto.unb64(b64));

        // Rebuild the manifest UNBOUND from the shipped one, so every signed field is copied
        // rather than retyped, then let the signer bind and sign it exactly as the client will
        // recompute it.
        PatchManifest unbound = new PatchManifest.Builder()
            .code(shipped.code()).name(shipped.name()).version(shipped.version())
            .kiRef(shipped.kiRef()).targetClass(shipped.targetClass())
            .platformCondition(shipped.platformCondition())
            .publisher(shipped.publisher()).builtAt(shipped.builtAt())
            .status(shipped.status()).supersedes(shipped.supersedes())
            .evidence(shipped.evidence())
            .build();

        String keyId = KernelTrustAnchor.KEY_ID;
        PatchManifest signed = new Ed25519PatchSigner(TrustAnchors.empty(), priv, keyId)
            .sign(unbound, transformHash);

        // The ceremony's own check: verify under the PUBLIC key that actually ships. Printing a
        // signature that does not verify would just move the failure to a confusing place at boot.
        boolean verifies = new Ed25519PatchSigner(Compat.defaultTrustAnchors()).verify(signed);
        if (!verifies) {
            System.err.println("FAIL: the produced signature does NOT verify against the baked-in "
                + "kernel public key. Either this key is not the kernel key, or the shipped "
                + "public key does not match it. Do not paste this signature.");
            System.exit(1);
        }

        // L0 is a separate gate and does not involve the key at all, but a stale pin would block
        // arming just as effectively, so say so here rather than let it look like a signing bug.
        String l0 = ContentHash.forPatch(patch);
        boolean l0ok = l0 == null || l0.equals(patch.expectedCanaryHash());

        System.out.println();
        System.out.println("signature verifies against the shipped kernel public key.");
        System.out.println("  patchId : " + signed.patchId());
        System.out.println("  keyId   : " + keyId);
        System.out.println("  target  : " + signed.targetClass());
        System.out.println("  L0 pin  : " + (l0ok ? "matches" : "STALE — regenerate expectedCanaryHash to " + l0));
        System.out.println();
        System.out.println("Paste into " + simpleName + ".KERNEL_SIGNATURE:");
        System.out.println();
        System.out.println("            \"" + signed.signature().replaceFirst("^(ed25519:v1:[^:]+:)", "$1\"\n            + \"") + "\";");
        System.out.println();
        if (!l0ok) {
            System.err.println("WARNING: the L0 pin is stale, so the patch will not arm even with "
                + "this signature. Fix expectedCanaryHash first.");
            System.exit(1);
        }
    }
}
JAVA

echo "signing $PATCH_CLASS with the key at $PRIVKEY"
"$JAVA" -cp "$CP:$WORK" "$WORK/SignCeremony.java" "$PATCH_CLASS" "$PRIVKEY"

cat <<'NEXT'
Next steps:
  1. paste the signature above over the placeholder in the patch class
  2. ./mvnw -q -pl core -am package -DskipTests
  3. ./scripts/run-mcp.sh   and confirm the kernel reports it ARMED rather than
     "SKIP unverified patch" — the engine's own report is the authority, not this
     script's, because only the client evaluates the full arming gauntlet.
NEXT
