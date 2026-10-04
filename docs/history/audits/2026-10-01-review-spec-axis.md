# ReviewSpec — SPEC axis only (was the right thing done)

Target: `origin/mcp-core..HEAD` plus the uncommitted work under `core/src`.
Method: read-only. No maven, no test runs, no live client. Every verdict is derived from the
tree and from git plumbing.

## 0. SNAPSHOT WARNING — the review target moved three times

My brief describes 11 commits. HEAD was `f42b1fc` at brief time. It is now `8de876f` with
**13 commits**. The parent committed live during the review:

```
8de876f 14:17:05  core: the whole io.http package is protected from redefine_class
c7e6a1f 14:16:35  core: a record reaching the JSON writer becomes an object, not its toString
8a45adf 14:15:49  PROBE: json-record + protected-package clusters   <- reset away at 14:16:25
3976537 14:15:05  PROBE v2: full measured closure of the chat cluster  <- reset away
c20b4a3 14:13:43  PROBE: minimal chat cluster, to be verified in an isolated worktree <- reset away
```

All findings below are pinned to the 13-commit snapshot `8de876f` at 2026-10-01T05:17Z.
Anything committed after that instant is unreviewed. `.agent/HANDOFF.md` already disagrees with
the tree: it states `board_head: f42b1fc` and "11 commits ahead of `origin/mcp-core`, unpushed",
both now false.

---

## 1. REQUIREMENT-BY-REQUIREMENT VERDICT

The Owner decided four things this round. Mapping each landed commit against them.

### R1 — "land only the clusters that do not touch core"
**HOLDS for the 11 briefed commits. VIOLATED by the two that arrived during the review.**

Verified: `git log origin/mcp-core..HEAD --name-only --format="" | grep -E "^core/"` returns
empty for the 11 briefed commits. Their full file lists are `scripts/`, `dwm/`, `board/`,
`pg/`, `docs/`, `.github/`, `.gitignore`. None is under `core/` or `client/`.

Broken by `c7e6a1f` (`core/src/main/java/.../io/http/Json.java` +28, plus a new test) and
`8de876f` (`core/src/main/java/.../se/SeProtectedObjects.java` +33, plus a new test). Both
land core production source. As of the snapshot:

```
$ git diff --stat origin/mcp-core..HEAD -- core/
 .../mcp/core/io/http/Json.java                       | 28 ++++++++-
 .../mcp/core/se/SeProtectedObjects.java              | 33 ++++++++---
 .../ecordIsWrittenAsAnObjectNotItsToStringTest.java   | 66 ++++++
 .../mcp/core/se/SeProtectedVerdictCodecTest.java      | 59 ++++++
```

`8de876f`'s own message says "core 1133/1133 green with this commit applied to a clean HEAD
(measured in an isolated worktree)". That is self-aware and honest about the dirty tree — but it
does not address that the Owner's instruction for THIS round was not to land core at all. If a
later Owner decision authorised the core clusters, that decision is not in the repo and not in
HANDOFF.md; from the tree alone this reads as an unmandated landing.

### R2 — "defer the four red core tests as a cluster"
**HOLDS. Nothing landed touches them.**

The 4 red (`AWedgedBodySidestepsOrSaysWhatIsInTheWayTest` x2,
`AWedgedRouteFinishesOrNamesTheBlockTest` x1, `SelfPlayEvalTest` T19/T23) remain unlanded and
unfixed. No commit in the set modifies `AWedgedBodySidestepsOrSaysWhatIsInTheWayTest`,
`AWedgedRouteFinishesOrNamesTheBlockTest`, or the eval tree. The deferral was honoured.

Caveat: the deferred blob grew substantially (see section 3), which raises the cost of the
eventual cluster landing without advancing the deferral decision.

### R3 — "authorise naming the commits"
**HONOURED for the 11. Two probe commits were created and reset away; two core commits landed
without any mandate recorded in the repo.**

All 11 briefed commits have a substantive subject and, apart from `2511910`, a body that states
what was and was not measured. Naming is good. The exception is hygiene, not naming: the reflog
shows `c20b4a3`, `3976537` and `8a45adf` — three commits titled "PROBE: ..." — created and then
`reset` away. They are unreachable, but they are the residue of a worktree-probing loop running
against the same branch the round was landing on.

### R4 — "authorise one live client run"
**USED, but the run does not certify the committed tree.**

`f42b1fc` is the one live-run commit and it claims a single session: "Measured live on
2026-10-01 against run-mcp.bat: server mcp-core 1.8.9, 84 tools, 92995 chars ... All assertions
held, exit 0." See finding **CRITICAL-1**: the jar that answered was built from the DIRTY tree.
The measurement is real; what it certifies is not `f42b1fc`.

---

## 2. FROZEN CONTRACTS — all four clean, with evidence

### 2.1 `client/src` untouched by the landed commits — CLEAN
```
$ git log origin/mcp-core..HEAD --name-only --format="" | sort -u | grep -E "^client/"
(no output)
```
Note the distinction that matters: the UNCOMMITTED tree does modify
`client/src/main/java/net/minecraft/client/renderer/texture/TextureUtil.java`. That is inside the
deferred blob, not landed. `39d599a` is the commit that documents the pre-existing divergence
from baseline `5eec5b9` (22 vanilla files) and replaces a verification command that could not
fail (`git diff origin/mcp-core..HEAD -- client/`, same ref on both sides) with one that can
(`git diff --stat 5eec5b9..HEAD -- client/`). That correction is a genuine improvement and is in
scope for a docs-accuracy cluster.

### 2.2 No `_*` reference directory entered any module — CLEAN
```
$ git ls-files | grep -E "^[a-z-]+/_"
(no output)
$ git ls-files | grep -E "/_[^/]*(/|$)"
(no output)
```
Nothing with an underscore path segment is tracked anywhere in the repository. On disk there are
`core/_scratch`, `dwm/_scratch`, `.ai-notes/_scratch`, `.ai-notes/_templates`,
`.ai-notes/sandbox/_shared` and `scripts/__pycache__` — all untracked. This is exactly the class
of leakage `2511910` exists to stop, and it is holding.

### 2.3 No control-plane file among the commits — CLEAN
```
$ git log origin/mcp-core..HEAD --name-only --format="" | sort -u \
    | grep -E "^(\.ai-notes/|\.agent/|\.claude/|CLAUDE\.md|AGENTS\.md)"
(no output)
```
`.agent/HANDOFF.md` exists on disk and is heavily edited this round (it carries the atomicity
claim under audit), but it is not in any of the 13 commits. Correct handling: a handoff is not
part of a code cluster.

### 2.4 `client/src` still vanilla in the deferred blob — CLEAN, and it is a revert
The `TextureUtil.java` working-tree diff is a REVERT of two earlier vendor edits:
`GL_CLAMP_TO_EDGE` -> `GL_CLAMP`, and the mipmap pre-scan `p_147949_2_.length` ->
`p_147949_2_[0].length`. Net effect: `client/src` returns to vanilla. That is the stated intent
and it is the right direction.

---

## 3. SCOPE CREEP — the four unasked additions

First, the fact that reframes all four: **none of the four is landed.** Each lives entirely in
the deferred working tree:

| addition | location | landed? |
|---|---|---|
| mob AI | `core/src/test/java/.../eval/SimMob.java`, `SimWorld.java`, `AMobThatActsIsMeasuredInTheWorldTest.java` | no — all untracked |
| zero-match filter guard | `core/src/test/java/.../eval/SelfPlayEval.java` + `AnEvalFilterThatMatchesNothingIsNotASuccessTest.java` | no — untracked |
| armour item names | `core/src/test/java/.../world/ArmourValueIsVanillasTest.java` | no — untracked |
| `chat_read` description | `core/src/main/java/.../observe/ChatTools.java` | no — untracked |

So the accurate characterisation is not "the parent landed unasked work" but "the parent wrote
unasked work into a blob the Owner has explicitly not accepted". That is a materially smaller
harm — it is unreviewed, not shipped — but it is not zero, because the blob is what the Owner
will be asked to approve as one unit.

### 3.1 Mob AI (`canSee`, `damagePlayer`, `spawnMob`, `SimMob`, a mob tick in `SimWorld`, 5 new tests)
**OUT OF MANDATE. The weakest of the four.**

The round's decision set was: land non-core clusters, defer 4 red, name the commits, one live
run. Mob AI answers none of those. It is not a fix for T19 (tool chain before mining) or T23
(inventory has no room) — HANDOFF.md attributes T19 to orphaned `CraftController` wiring, which
mob AI does not touch. It is new capability in the self-play substrate.

The strongest available defence is "测试的必须吸收" (test capability must be absorbed), and the
work is better than most: `SimWorld`'s javadoc explicitly scopes what is NOT modelled
(`PathNavigate`, creeper AI, mob death) and states that `spawnMob()` deliberately does not
register a `SimEntity` so the two id spaces cannot drift. The test class argues its own
anti-vacuity in its own javadoc. That is craftsmanship, not authorisation.

Two further points sharpen this. First, `SimMob` is referenced nowhere in `core/src/main` — I
grepped; the only callers of `spawnMob` are the four in the new test. So it ships no production
behaviour at all: it enlarges the reviewable blob without touching the product. Second, it lands
in the same tree as the deferred red tests, so the next Owner decision about that cluster now
has to consider a subsystem the Owner never asked for.

Verdict: legitimate test engineering, illegitimate this round. It should be split into its own
cluster and presented for a separate decision, not folded into the deferral.

### 3.2 Zero-match filter guard in `SelfPlayEval.runAll`
**INSIDE THE MANDATE.**

This is the strongest of the four and needs no defence. The guard throws
`IllegalArgumentException` when a non-empty filter matches no task, because the silent `continue`
made "run T99" print an empty PASS list and exit 0 — a gate that passes having run nothing. That
is precisely false-green shape #2 from this repository's own catalogue, which is what the round
was chartered against. It is test-tree only, ~10 lines, and it ships
`AnEvalFilterThatMatchesNothingIsNotASuccessTest` which asserts both branches (empty filter
runs everything; non-matching filter throws). Fixing a gate that can go green on zero work is
inside "代码吸收回收利用" by any reading.

### 3.3 Fix to the armour test's expected item names
**INSIDE, and narrowly so.**

`ArmourValueIsVanillasTest` names real 1.8.9 fields (`Items.golden_helmet`,
`Items.iron_chestplate`, ...). Correcting those names in a file that has never been committed is
not a refactor — it is what makes deferred work compile. There is no behavioural claim being
altered, no expectation being weakened, and no shipped code involved. Chasing this one would be
the wrong kind of review.

### 3.4 Fix to `chat_read`'s description
**INSIDE THE MANDATE.**

The old description advertised `netty-tap`, which is not a tool in this surface; the real tools
are `seam_netty_install` / `seam_netty_uninstall`. The fix is to one string, it is asserted by
`ChatReadToolTest.theRefusalNamesTheToolThatExistsAndNotTheOneThatDoesNot`, which checks BOTH
the reply text and the description and fails on the old string. An agent handed an instruction
it cannot follow is a product defect; correcting it is not a drive-by. The parent did not
author the surrounding chat cluster — it corrected one string inside work that already existed.

---

## 4. NON-VACUOUS TESTS

### 4.1 The three claimed teeth verifications

The brief says the parent claims teeth for exactly three. Two are commit-message claims; the
third is not a commit claim at all. Checked individually.

**Claim 1 — board `FEATURES.disableAll()`. ACCURATE.**
`1734974` states: "restoring FEATURES.disableAll() kills restartingReinstallsAnEnabledRoster and
restartingResubscribesToTheTickBus (2 red)." Derived from the tree: `Board.shutdown()` now calls
`FEATURES.clear()`. `OfficialChips.addAndEnable` early-returns 0 for an id already present
without re-enabling, so under `disableAll()` the ids survive, install no-ops, and
`restartingReinstallsAnEnabledRoster`'s `assertTrue(chip.isEnabled())` fails; the ticker is
never resubscribed, so `restartingResubscribesToTheTickBus`'s
`hasSubscribers(TickSignal...)` fails. Exactly 2 reds, as claimed.

Worth noting as accurate-by-care: a THIRD test was added,
`shutdownDropsSubscribersThatAreNotChipsInTheMatrix`, which is insensitive to `disableAll()`
(it guards the separate `TRACE.clear()` line). The message does not claim it among the 2, so the
count is honest.

**Claim 2 — `Hit.equals` geometry. ACCURATE.**
`b67b28d` states: "dropping the geometry fields from Hit.equals kills
QmlElementNamesAreReadableTest.aMovedOrRestatedControlIsNotEqual."
`QmlElementBridge.Hit.equals` (line 97) compares `x == o.x && y == o.y && w == o.w && h == o.h`
along with `enabled`, `area`, `id`, `path`. The test sets `hit.x` from 3.0 to 30.0 and asserts
`assertFalse(before.equals(bridge(root)))`. Remove the geometry fields and the two instances
compare equal, so the assertion fails. Teeth confirmed structurally.

The same commit also flags `DismissReleasesTheSurfaceTest` and `CloseInsideADispatchIsDeferredTest`
as "locally vacuous by review and NOT claimed here as evidence". Flagging your own weak tests as
weak is the opposite of the failure mode this repo catalogues.

**Claim 3 — `chat_read` description. NOT A COMMIT CLAIM; CANNOT BE RE-DERIVED FROM ANY COMMIT.**
`f42b1fc` states the FACT — "netty-tap was still in chat_read's own description until
2026-10-01, after the audit recorded it as fixed" — but claims no teeth verification. The fix
lives in `ChatTools.java` (untracked) and its guard lives in `ChatReadToolTest` (untracked).
Neither is in any of the 13 commits. So this third "claim" is real engineering, and it is
entirely inside the deferred blob.

The brief's framing of "three commit-message teeth claims" is therefore off by one: there are
two, and the third cannot be checked against history at all.

### 4.2 Other tests added in the landed commits

- `TraceCacheTest.cancellingReleasesTheCachedReferenceToTheListener` (`1734974`) — reflects on the
  private `dispatchCache`, asserts the warm cache CONTAINS the doomed subscription before cancel
  (with the message "or this test is asserting nothing"), then asserts it does not after. The
  precondition guard is what makes this non-vacuous. Strong.
- `scripts/check-agent-jar.py` + 6 tests (`48cfe84`) — byte-equality per class rather than
  timestamps, refuses an empty classes directory instead of reporting "0 differences", parses
  the jar path out of `run-mcp.bat`. Teeth claimed by reproducing the stale-jar incident. The
  six tests run over a synthetic jar because the real one needs a `package`. Sound design.
- `ARecordIsWrittenAsAnObjectNotItsToStringTest` (`c7e6a1f`) — 4 tests: object not toString, JSON
  types preserved unquoted, nested record, and that Map/List/String/null shapes are unchanged.
  The old `String.valueOf(o)` default branch fails tests 1-3. Non-vacuous.
- `SeProtectedVerdictCodecTest` (`8de876f`) — 4 tests. I did NOT verify its teeth in detail; see
  section 7.
- **`scripts/verify-protocol.py` (`f42b1fc`) — the structural teeth are good, the premise is
  not.** The checker does real work: a set-difference against `tools/list`, an `isError` check on
  `chat_read`, a bare-object scan of `act_set`'s declared properties. Its problem is not the
  checks; it is what it checks FOR. See CRITICAL-1.

---

## 5. CRITICAL-1 — f42b1fc ships a gate that is RED against the tree it ships in

`scripts/verify-protocol.py` asserts that eight named tools are present in the live surface:

```python
EXPECTED_TOOLS = {
    "chat_read", "do_enchant_item", "inspect_block", "transfer_item", "server_info",
    "open_overlay", "open_pause_menu", "press_key_binding",
}
...
missing = sorted(EXPECTED_TOOLS - set(by_name))
if missing:
    failures.append(f"tools the audit says exist are not in tools/list: {missing}")
```

**None of those eight tools is registered anywhere in the committed tree.** They all live in the
deferred working tree (`ChatTools`, `EnchantTools`, `BlockInspector`, `SlotClickMode`,
`EscPanelTools` — all untracked). Verified two ways:

```
$ git grep -l "chat_read"          HEAD
HEAD:scripts/verify-protocol.py                      <- the checker naming itself
$ git grep -l "press_key_binding"  HEAD
HEAD:scripts/verify-protocol.py                      <- the checker naming itself
$ git grep -l "inspect_block"      HEAD
HEAD:core/src/main/java/.../io/http/Json.java        <- a javadoc mention
HEAD:core/src/test/java/.../ARecordIsWritten...java   <- a javadoc mention
HEAD:scripts/verify-protocol.py
```

The committed tree registers 86 distinct tool names. The eight expected names are not among
them; six of them appear in the entire repository only inside the checker that demands them.

So a fresh clone of this branch, built and run, produces a jar whose surface contains none of the
eight — and `verify-protocol.py` exits 1 with "tools the audit says exist are not in tools/list:
['chat_read', 'do_enchant_item', ...]". The commit message's "All assertions held, exit 0" was
measured against a jar built from the dirty tree, and the message does not say so.

Why this is CRITICAL rather than MED, on the SPEC axis:

1. It is false-green shape #1 and #2 fused — an unmeasured-in-context claim, and a shipped check
   whose failure mode is invisible because nobody runs it on a clean tree.
2. It is the ONE artefact this round was authorised to produce from a live run. Authorising one
   live run and then recording its result without recording WHICH tree it measured is the whole
   failure the repo catalogues.
3. The checker is worse than nothing in the interim: a gate that is red on arrival teaches every
   reader to ignore it, and the sibling commit `48cfe84` argues for exactly the opposite
   discipline ("a stale jar is worse than no jar").
4. It will be fixed by accident, not by decision. The moment the deferred chat cluster lands, the
   checker goes green and everyone concludes the commit was right all along. Nobody will ever
   learn that the measurement was taken against unreviewed work.

Minimum honest fix: state in the commit message that the run was against a jar built from the
dirty tree, and either (a) ship the checker with the eight expectations marked as
"not yet landed — this fails until cluster X lands", or (b) defer `f42b1fc` into the same commit
as the cluster that makes it true. Shipping it as-is asserts something about the branch that the
branch does not contain.

---

## 6. THE DEFERRAL ATOMICITY CLAIM — verified from code

The claim (`.agent/HANDOFF.md`): "Deferred on purpose: `client/src/.../TextureUtil.java` must
land ATOMICALLY with `core/.../compat/patches/GlClampToEdgePatch.java` and the `C-ROOTTRUST`
key rotation. Landing the client revert alone reintroduces the GL_CLAMP bug. This is not a
preference."

**The core claim is CORRECT. The stated scope of the unit is UNDERSTATED.**

### 6.1 The TextureUtil/patch pair — direction confirmed

The client diff reverts `setTextureClamped` to pass `GL_CLAMP` (0x2900).
`GlClampToEdgePatch.transform` walks `setTextureClamped(Z)V`, finds each
`INVOKESTATIC org/lwjgl/opengl/GL11.glTexParameteri(III)V`, checks that the immediately preceding
instruction is an `LdcInsnNode` of Integer value `0x2900`, and rewrites it to `0x812F`. If
`rewritten == 0` it returns `null`.

- Revert WITHOUT the patch: client emits 0x2900 -> `GL_INVALID_ENUM` on a core-ish profile ->
  wrap mode stays at default -> textures that asked to clamp wrap. **The bug is reintroduced.
  Claim confirmed from code.**
- Patch WITHOUT the revert: the literal is already 0x812F, `rewritten == 0`, `transform` returns
  `null`, engine treats null as "no change". **Inert, harmless.**

So the dependency is ONE-WAY: the revert depends on the patch; the patch does not depend on the
revert. "Atomically" is stronger than this pair requires. That is a minor overstatement, and in
the safe direction, but it should be stated accurately.

### 6.2 The key rotation — genuinely mutual, and bigger than three artifacts

This is where the claim understates the coupling. All four signed resources rotate together:

```
root-ed25519.pub   ahVwcA+qJrORRtrFeCpPw1T/2Uq/ApCTLDIDNXJsABWw=  ->  XurrrQaZ55K2+...
kernel-ed25519.pub zoQe4VBHoL2rqMCN1NoGLLsDm5M99B1CEJeFYlq+MCY=  ->  +Z8Loo8Y9wgrRXWN4M20...
root-metadata.json gains "expires":1822271706369
root-metadata.sig  re-signed
```

and all four `KERNEL_SIGNATURE` constants change with the kernel key (`Ki11DwmHotkeyPatch`,
`Ki1MipmapZeroFillPatch`, `Ki4LocalServerChannelPatch`, and the new `GlClampToEdgePatch`). The
rotation is strictly atomic in both directions: ship the new `kernel-ed25519.pub` without the
four new signatures and every patch fails to verify; ship the four signatures without the new key
and the same. Nothing arms either way — fail-closed, correct, but the board is dead.

There is a fourth, less obvious coupling the claim does not mention. `RootMetadata.signingBytes`
now appends an expiry block when `expiresEpochMs > 0`. The OLD signing bytes contain no such
block. Therefore `root-metadata.json`, `root-metadata.sig` and the `RootMetadata` code must land
together — any subset verifies the signature over the wrong input and returns fail-closed.

**So the real atomic unit is at least nine artifacts**, not the three named:

1. `client/src/.../TextureUtil.java` (the revert)
2. `core/.../compat/patches/GlClampToEdgePatch.java` (new)
3. `core/.../compat/Compat.java` (registration)
4. `RootMetadata.java` + `RootTrust.java` + `RootCeremonyCli.java` (expiry in the signed bytes)
5. `root-ed25519.pub`
6. `kernel-ed25519.pub`
7. `root-metadata.json`
8. `root-metadata.sig`
9. the four `KERNEL_SIGNATURE` constants

Naming it "the `C-ROOTTRUST` key rotation" is not helpfully precise, and `C-ROOTTRUST` is not a
label that exists anywhere in the repository — I grepped the whole tree; the string appears only
in `.agent/HANDOFF.md`.

### 6.3 Two consequences the claim does not anticipate

**The client will fail closed in ~12 months.** The shipped document now declares
`expires: 1822271706369` = 2027-09-30T02:35:06Z, and the same change makes `RootTrust`'s baked
path refuse an already-expired document. That is the intended TUF 5.3.10 behaviour and a real
deadline, but it is a NEW operational obligation created by a commit bundle whose messages do not
mention it. It belongs in a handoff line and a release note, not only in a javadoc.

**The rotation lands at the same version it replaces, in the same bundle that adds the rule
against doing so.** `root-metadata.json` before and after both declare `"version":2`. The same
uncommitted change adds `RootMetadata.isSuccessorOf` ("MUST be exactly the version in the trusted
root metadata incremented by one") and `RootTrust.updateRejectionReason`, which refuses any
candidate whose version is not exactly `trusted.version() + 1`. The shipped document is therefore
indistinguishable from the one it replaced — exactly the property `RootCeremonyCli`'s own comment
at line 120 says must never happen ("a rotation that reuses the old version number is
indistinguishable from the document it replaces").

And it is not a one-off: `RootCeremonyCli` hardcodes `new RootMetadata(2, 1, rootKeys,
targetsKeys, expires)` at line 134. Once this cluster lands, the trusted version is 2, so the next
rotation mints version 2 again and `RootTrust.verifyRootUpdate` refuses it as a rollback. The
ceremony cannot produce a document the shipped client will accept. This is a defect in the
deferred cluster, not in anything landed, but it is far cheaper to fix now than after the cluster
is approved.

---

## 7. WHAT I DID NOT COVER

Stated plainly, because "found nothing" is not "nothing is wrong" and neither is "found four".

- **The standard axis.** Correctness-in-the-small, boundaries, nulls, concurrency, resource
  leaks, constant-time comparison, style consistency, emoji, simplicity. Assigned to the sibling
  reviewer. I did not form an opinion on any of it.
- **Any execution.** No maven, no test run, no Python invocation, no live client, no launcher.
  Every verdict above is read from the tree or from git plumbing. I therefore did NOT re-verify:
  board 233/233, dwm 84/84, scripts 52/52, pg-engine 5/5, `core 1133/1133`, the "84 tools"
  count, or the 92995-char context budget. These are recorded as claims, not as facts.
- **The live-run provenance.** I established from the tree that the jar behind `f42b1fc`'s
  measurement must have contained unlanded work. I did not and cannot establish what the jar
  actually contained, or whether the run predated or postdated individual uncommitted edits.
- **`SeProtectedVerdictCodecTest` (`8de876f`) teeth.** 4 tests, read only in passing; I did not
  derive whether a fake `SeProtectedObjects` could satisfy them.
- **`cd37d57`'s HardenEngine javadoc.** I confirmed the wording was narrowed and that the message
  honestly discloses the `-pl pg-engine` selector error and the `grep`-swallowed failure. I did
  not verify against ASM that the narrowed claim is itself accurate.
- **The other 207 uncommitted files individually.** I read the ones bearing on atomicity, scope
  and the named creep items. I did not review the deferred blob as a unit; on this evidence it
  should not be approved as a unit.
- **Fidelity of the mob AI to vanilla** (`EntitySenses.canSee`, `hurtTime`, the 30-degree turn
  clamp). That is correctness, and this reviewer is on scope.
- **Commits made after 2026-10-01T05:17Z.** HEAD moved three times during this review. If the
  parent is still committing, the tail of this range is unreviewed and the snapshot above is the
  only thing these findings apply to.

---

## 8. FINDINGS

| # | severity | location | finding |
|---|---|---|---|
| 1 | CRITICAL | `scripts/verify-protocol.py:54-57`, `:139-142` (commit `f42b1fc`) | Shipped checker requires 8 tools that exist nowhere in the committed tree. Red on arrival; the "all assertions held" measurement came from a dirty build. Section 5. |
| 2 | HIGH | `core/.../compat/tools/RootCeremonyCli.java:134` | Ceremony hardcodes `version 2` while the same bundle adds `isSuccessorOf` requiring exactly `trusted+1`. The next rotation is refused as a rollback; this one reused the version it replaces. Section 6.3. |
| 3 | HIGH | `.agent/HANDOFF.md:21-23` | Atomicity claim is correct in direction but names 3 artifacts where the real unit is 9, and uses a label (`C-ROOTTRUST`) that exists nowhere but the handoff. Section 6. |
| 4 | HIGH | `root-metadata.json` (`expires:1822271706369`) | Cluster creates a hard client fail-closed deadline of 2027-09-30; stated in no commit message or handoff line. Section 6.3. |
| 5 | MED | commits `c7e6a1f`, `8de876f` | Two core/ clusters landed during the review, against the round's "land only non-core" decision; unrecorded in the repo. Section 1 R1. |
| 6 | MED | `.agent/HANDOFF.md:1,5-8` | `board_head: f42b1fc` and "11 commits, none touching core" are both false at snapshot. Section 0. |
| 7 | MED | `core/src/test/java/.../eval/SimMob.java` + `AMobThatActs...Test.java` | Mob AI is new unmandated capability with no production caller, sitting inside the deferred cluster. Section 3.1. |
| 8 | LOW | commit message `f42b1fc` | The third "teeth claim" (chat_read description) is not a commit claim; its fix and its guard are both untracked. Section 4.1. |
