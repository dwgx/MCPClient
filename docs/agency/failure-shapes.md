# The failure shape: a capability documented as doing something whose producer is absent

2026-10-02. Thirteen instances of one shape were found in a single day across seven parallel
work streams, plus three caught by a worker rather than by the integrator. **This document
catalogues sixteen worked instances: the thirteen, plus one found while writing it** — §2.3, a
stale line count sitting in the javadoc of the very file that fixed instance §2.1 — **plus one
found after it was finished, which is §2.15 and is the only one here that no test could have
caught in advance** — **plus one more, §2.16, merged in on 2026-10-03 from four audits that were
gitignored and would otherwise not have travelled.** Those clauses are not padding: they are the
argument for §3.3, they are why the header count was written last, and they are why the count is
stated as a number rather than left to a reader: this file was written by the same process it
describes, and the process produced a new instance while the catalogue was open, twice.

**What the four 2026-10-03 audits contributed, and the one structural decision in this file.**
Four new shapes were found that day, and **none of them is an instance of §1**: each is a
claim about **a test instrument**, not about a documented capability, so numbering them §2.16
onward would have put a second shape inside a directory that has exactly one, and a reader
scanning §2 for "what goes red when a producer is absent" would have found a guard defect
instead. They are catalogued where this document's own admission criterion puts them — **two
instances or more is a catalogue entry, one is a rule** — which gives **one shape with two
instances (§7.1, and one of those two is still live in the tree right now)**, **two shapes with
one instance each, promoted to §3.12 and §3.13 and flagged as invented, because §3's preamble
promises nothing in that section is invented and the promise has to be amended rather than
broken**, and **one that is about the discipline of the reader rather than about the code, which
is §4.4**. The reasoning and the instance counts it rests on are in
`.ai-notes/docs/audits/2026-10-03-shape-merge-arbitration.md`. **The gap at §2.16 is the
argument, not an oversight.**

**This is the catalogue for people who have never been in this repository.** It is not a
narrative and it is not a guarantee list. Read §2.1 if you write a test-only helper. Read §3 if
you write anything at all.

**What this document is not.** It does not tell you what the kernel guarantees — that is
`guarantees.md`, **53** rows each carrying a `file:line` and the test class that goes red (row 53
is the highest number in that document; this file said 47 until 2026-10-03, which is §2.3's shape
in a file whose own §3.3 forbids it). It does
not tell you what is in the vendored Minecraft tree — that is `vendored-tree.md`. Where an
instance below is also a guarantee row, the row is linked, not repeated. **Link, do not
restate:** a second copy of either document is a second copy that goes stale.

**The count was re-verified on 2026-10-03, after `guarantees.md` grew a column, and it still
holds — but what was checked is the number, not the words.** Counting the guarantee tables' own
first column rather than trusting either document's prose: **53** rows numbered `1` through `53`,
**no gap and no duplicate**, which is exactly what makes "53 rows" and "row 53 is the highest" two
claims that agree rather than two numbers that happen to match. The new `tests` column took every
guarantee row from 4 fields to 5 and **renumbered nothing**, which is why a change that touched
all 53 rows left the count standing.

**Two figures come out of the same sweep and neither is part of the 53.** The measurement table is
at `guarantees.md:1115` (it was at `:952` earlier the same day) and is a **separate five-row table
with its own `1`-`5` numbering**. And sweeping every `| N |` row in the file yields a number that
is **not 53 and not stable either** — it was **58** on the first pass and **64** on the last, while
the guarantee count stayed at 53 through both. **Anyone re-checking must count the first column of
the guarantee tables and stop there; the whole-file sweep is the trap, and it is a moving trap.**

**This paragraph is itself the strongest evidence for §3.3 in the document, and it is worth
leaving in rather than tidying.** Between the two passes above, `guarantees.md` grew from **1480
lines to 1643**, its §6 rules were renumbered, and one of its entries deleted the `1317` line count
this document cites at §2.3 — **and the guarantee row count did not move by one.** A number that
tracks a *set of rows* survives edits that renumber *positions*; a number that tracks *positions*
does not. **That is the whole difference between the 53 and the 58, and the 58 is what a
careless re-check produces while looking like diligence.**

**Why it exists at all.** These thirteen were recorded only in audit reports under
`.ai-notes/docs/audits/`, which is gitignored (`.gitignore:56`, the `.ai-notes/` line). Those
reports produced these changes, and **none of them will travel with the repository.** A
contributor who has never seen this project cannot currently learn any of it. This file is the
part that will.

**This document originally carried a count, and I had to take it out.** The first draft said the
directory holds "68 reports, 38 of them dated the day". Re-measuring later the same evening,
with three workers still writing: **70 and 47**. `guarantees.md` carried the same kind of
count at **61 and 38** on the day this was written, and it is stale in the same way. Neither
number is wrong about anything; each is a claim about a directory being written to while it is
read. **§3.3 is not a rule about someone else's document.** It is the reason this one quotes no
directory count — read the directory. **The anchor this paragraph used to carry,
`guarantees.md:39-41`, no longer resolves to a count at all**: those three lines are now the
*"Why this file exists at all"* paragraph, so the count is gone from there. The sentence was
true when written and the sentence outlived it — **§2.3 and §2.15 at once**, which is why the
citation is now the symbol (`guarantees.md`) and not the line range.

**A citation in this document is a promise.** Every `file:line` below was opened on the day it
was written. That sentence is doing more work than it looks: §1g of `guarantees.md` records a
single sweep over what was then a **905-line** `guarantees.md` (it is **1643 lines** as of
2026-10-03 — measured during the count re-verification above) that corrected **16 wrong anchors**
across nine rows, and
an earlier sweep corrected **30-plus**. Two of those groups were wrong **as a block**, every
member off by exactly one line, which is the signature of a number copied out of a diff hunk
header rather than counted off an open file. They were spread across the document rather than
concentrated in one stale corner, which says the anchors were being written **by inference**. If
you find an anchor here that does not resolve, it is a defect in this file, not a curiosity:
that is what the rules section is for, and it is the same rule the wrong anchors were caught by.

**And this file's own promise was tested on 2026-10-03, when §7 was merged in.** Every
`guarantees.md` anchor in §3 was re-opened rather than trusted, and **seven had moved** — §6.4,
§6.9, §6.10, §6.11, §6.12, §6.16 and the §5.5 range — because that file's §6 was renumbered by
another worker while this one was being written; §2.3's `1317` citation had gone stale for a
different reason, the number having been deleted outright. **Every one is now annotated in place
with the line it used to carry**, so the rot is legible rather than papered over. **A `file:line`
into a file somebody else is editing is a half-life, not a citation** — which is now
`guarantees.md` §6.14's own rule, written *after* most of these anchors were first used, so the
rule existed and this file did not apply it to itself until something forced the check.

---

## 1. The shape

**A capability documented as doing something whose producer is absent, renamed, never
executed, or unreachable.**

The interesting part is not that each instance was wrong. It is that **almost none of them
could be caught by the machinery the project already had** — not the compiler, not the test
suite, not grep. **Five** separate escape routes are documented below, and a reader who has only
learned "my tests are green, therefore I am fine" has learned the wrong lesson — worse in the
fifth case, where the green suite is not a passive bystander but the mechanism:

| escape route | how the claim survived | where |
|---|---|---|
| **It sat in a comment.** | A javadoc or a `//` line is not compiled and not collected. The name in it can be wrong for years. | §2.13, §2.14 |
| **It was a number read from a diff.** | A hunk header shows a line number; it does not show that the line is the thing. | §2.2, and all 46-plus wrong anchors in `guarantees.md` |
| **It was true when written, and the file moved.** | The claim was correct. Then somebody fixed the code, or edited the file it described, and the sentence did not. | §2.3, §2.13, and three of the integrator's own claims |
| **It was a decision, not an implementation, and nobody asked.** | The design says the model decides; the enforcement half was never measured against it. | §2.1 |
| **A correct fix made the sentence false, and every gate stayed green.** | The wiring landed, was tested, and worked. The document describing its absence was in the same repo the whole time, and the diff that broke it reads like a feature. | §2.15 |

**§2.2 is the sharpest, and it was the newest.** It is not a defect in the code at all — the
claim underneath it was **true**. Only the name was wrong, and it was wrong in a report about a
defect in a document, which means the shape was live in the act of being reported on.

---

## 2. The instances, in severity order

### 2.1 A policy only tests could reach

**What was claimed.** `GoalPolicy` — the eval's reference decision — is 1317 lines in
`core/src/test/`, and `grep -rn GoalPolicy core/src/main` returns **zero**. It is the thing the
whole survival evaluation runs on, and nothing in the shipped artifact can reach it.

**What was on disk.** Both halves true. The file is at
`core/src/test/java/net/marcloud/mcp/core/eval/GoalPolicy.java`, **1339** lines as of this
writing. `grep -rn "GoalPolicy" core/src/main/` returns **5 hits, every one of them inside a
javadoc comment** in `Policy.java:9,39` and `PolicyRun.java:11,22,41` — that is, zero *code*
references, which is what the claim meant. Construction sites: **10** `new GoalPolicy(...)` call
sites in code, plus one method reference at
`core/src/test/java/net/marcloud/mcp/core/eval/EvalSuite.java:1708` (`runWith(GoalPolicy::new)`).
Before the seam landed, at commit `7973f76`, `git grep` counted **11** `new GoalPolicy(` sites,
which is the "eleven sites" that both `Policy.java:10` and `GoalPolicy.java:69` still say —
correctly, because both are describing the *before*.

**Why the claim survived.** It was true, and it was true by design. The design is "the runtime
reports, the model decides" — which is also `guarantees.md` §6.2. A Java chooser that picked
*walk or dig* would make the project's own acceptance criterion permanently untestable. So the
policy being in the test tree was **correct**. What was missing was not the policy. It was the
**seam**: nothing could hand a task a different decision, so no task had ever been shown capable
of failing on anything but a planted world, and a suite in which nothing can fail cannot
demonstrate that it detects failure.

**What caught it.** A deliberate injection point, placed in the shipped tree:
`core/src/main/java/net/marcloud/mcp/core/eval/Policy.java:58` (`public interface Policy`),
`PolicyRun.java`, and `BrokenPolicies.java` — **all three in `core/src/main`**, which is the load
-bearing choice, and `Policy.java:39-43` says why in the file itself: *"A seam defined in the test
tree can only be reached from the test tree, which would reproduce in this slice the exact
failure the slice exists to close."*

**What does not catch it.** Nothing about a green suite. The old suite was green for as long as
the defect existed.

**The rule.** *A capability's reachability is a property you have to measure, and "only tests can
reach it" is a defect even when the capability is correct.* The question to ask of any new
component is not "is it tested" but **"who reads this?"** If the answer is "the tests", the
component is not part of the product.

---

### 2.2 The seam test, and the name that did not resolve

This is instance 13 and the one to read first if you only read one.

**What was claimed.** A worker reported a test named
`TheSeamIsInTheShippedTreeNotTheTestTree`.

**What was on disk, on 2026-10-02.**
`core/src/test/java/net/marcloud/mcp/core/eval/TheSuiteGoesRedThroughTheSeamTest.java:216`:

```java
public void theSeamIsInTheShippedTreeNotTheTestTree() {
```

**Lower case on the leading `t`.** Not a different test, not a missing test — the right method in
the right file, with the first letter wrong. The claim underneath was **entirely true**: the
method asserted the seam's package was `net.marcloud.mcp.core.eval` for all three shipped classes,
which is exactly what instance 2.1 needed.

**This entry's own anchor has since aged, which is worth printing rather than quietly repairing.**
As of 2026-10-03 **that method no longer exists in that class**: it was **renamed**, not deleted,
and `:216` now holds a javadoc line. The live method is
`theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree` at **`:247`**, and its criterion is
no longer the package name at all — see **§7.1**, where the same repair is the worked example of a
second shape. **So §2.2's `file:line` and its quoted name are both now wrong, in this file, about
the cheapest rule in the document** — a stale line number (§2.3) *and* a name that no longer
resolves (§2.13), stacked on the one entry whose rule is *"open the file and copy the name."*
Nothing forced it: the rename was correct, §7.1's fix was correct, and the sentence describing
them stayed put. **§3.3 and §3.2, both, on one line.**

**One precision that matters, because getting it wrong is how this entry nearly got a false
all-clear.** A tree-wide `grep` for `theSeamIsInTheShippedTreeNotTheTestTree` **does return a
hit** — `TheShippedShelterCounterIsReachableAndNotATestTreeClassTest.java:60` — so the *string*
still exists. **What does not exist is the method**: a declaration search
(`grep -rnE '(public|private|protected)[^;{]*\btheSeamIsInTheShippedTreeNotTheTestTree\s*\('`)
returns **zero across the tree**, and the one surviving occurrence is a `{@code}` **mention**
inside another class's javadoc, not a signature. **A `{@code}` javadoc tag is not a link, so the
javadoc tool never resolves it and never warns** — which is precisely why §2.13's criterion, taken
as *"does this name appear in the tree"*, reports this entry as healthy. §2.13 carries the full
argument and the two checks that do work.

**Why the claim survived.** Nothing checks the name of a test the way a compiler checks a type.
A `file:line` is checkable by opening the file; a *test method name quoted in prose* is only
checkable by someone who already suspects it. And the method name here is long, specific, and
almost right — the hardest kind of name to doubt. Note where the wrong name appeared: **inside a
report about the failure shape**, produced while handling instance 2.1. The shape was live in the
act of being reported on.

**What catches it now.** Opening the file. That is the whole mechanism, and it is the reason
`guarantees.md` §6.12 exists: **a test class's name is not evidence of what it reads.** Unlike a
stale line number — which a reader can catch by following it — a wrong *name* cannot be caught
by following the citation, because a reader has no reason to doubt a name that specific.

**What does not catch it.** Surefire will happily collect and report a method whose name you
misremember. The suite total is unaffected.

**The rule.** *Open the file and copy the name. A name you have not copied is a name you have
invented.* This is the cheapest rule in the document and the one most often skipped, because the
name is right most of the time.

---

### 2.3 The number in the seam's own javadoc, already stale

Found while verifying instance 2.1 for this document, and it is here because it is the newest
member of the shape and because it is in the file the shape's own fix produced.

**What was claimed.** `TheSuiteGoesRedThroughTheSeamTest.java:42`:

```
 * of every pair here is {@code GoalPolicy}, a 1317-line reference implementation.
```

**What was on disk.** `GoalPolicy.java` is **1339** lines — and it was **1339** at the very
commit that introduced the sentence. `git show c3e3c5d:…/GoalPolicy.java | wc -l` → `1339`;
`git show c3e3c5d:…/TheSuiteGoesRedThroughTheSeamTest.java | sed -n 42p` → the `1317` sentence,
**in the same commit**. The 22-line difference is that commit's own edit: `implements Policy`
plus a `toString()` override and their javadoc, visible in
`git diff 7973f76 c3e3c5d -- …/GoalPolicy.java`.

**Why the claim survived.** It was **copied from the state before the edit**. The number was
counted, then the file changed underneath the sentence in the same commit, and nothing
re-counted. It is not a diff-hunk number; it is a number that was true when read and published
one edit later. That is the fourth escape route in its purest form, and it is why `guarantees.md`
§6.9 ("do not pin a number in this document") and `vendored-tree.md` R3 ("a document that
accumulates numbers will accumulate stale numbers") both exist.

**What catches it now.** Re-opening. Nothing else. The sentence is a comment: no compiler, no
test, and no grep for `1317` outside this paragraph — **`guarantees.md` no longer prints it at
all**, having replaced it with *"its line count is deliberately not given: this entry once said
1317, and it was already wrong when written"* (`:1339-1340`; this entry used to cite `:940`, which
carried the number before that edit). **So the number now appears in exactly one place in the
tree, and the other document has answered §6.15 by deleting its copy** — which makes this the
second instance in this document of a stale anchor that got fixed by somebody else, without
this sentence being told.

**What does not catch it.** The suite. The file compiles. `GoalPolicy`'s behaviour is unaffected
by its own length.

**The rule.** *Do not publish a count you took before your own edit landed.* If the change you
are describing also changed the thing you counted, the count is already wrong — recount after,
or cite the symbol (`GoalPolicy`) instead of the length. A symbol survives a refactor; a line
count does not even survive an edit.

---

### 2.4 A patch that armed and changed zero bytes

**What was claimed.** `GlClampToEdgePatch` is armed. The manifest says so, the signature
verifies, and the patch is listed among the armed ones.

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/compat/patches/GlClampToEdgePatch.java:153-159`,
in the file's own comment, after the fix:

> The patch originally matched only LDC. GL_CLAMP is 0x2900 = 10496, which fits a signed
> 16-bit immediate, so javac emits SIPUSH … The patch therefore never matched, returned null,
> was reported armed because its signature verifies, and did nothing.

The matcher at `:168-177` now accepts **both** encodings and rewrites to `LDC`, because the two
constants do not have the same width — `GL_CLAMP` (10496) fits a 16-bit immediate, `GL_CLAMP_TO_EDGE`
(33071) does not, so the replacement must be a different *instruction*, not a different operand.
Writing 33071 into the SIPUSH operand produces a class that does not verify.

**Why the claim survived.** **`armed` and `worked` are different facts, and only the first was
measured.** Verification checks the patch's signature against the class it names. It does not
check that the matcher ever fires. A patch that matches nothing is indistinguishable from a
patch that matched everything and rewrote correctly, at every layer that existed. The symptom
would have been one line — `N patch(es) armed` — and the vendor edit the patch was written to
replace had *already been reverted*, so the fix would have vanished on the day the pair landed,
with nothing to notice it.

**What catches it now.** `core/src/main/java/net/marcloud/mcp/core/compat/CompatEngine.java:73-78`
declares `ApplyRecord` with **three** separate counters — `transformRuns` (`:473`),
`targetsChanged` (`:478`), `applyFailures` (`:487`) — per patch, per patch id. The javadoc at
`:48-53` states the distinction in one sentence: *"`armedPatchIds()` answers 'was this patch
permitted to run'; this record answers 'when it ran, did it do anything'."*

**What does not catch it.** Correctness. `CompatEngine.java:55-58` says it in the file:
`targetsChanged > 0` says the engine handed the JVM a different byte array, **not** that the
rewrite was semantically right. And `targetsChanged` counts **invocations, not distinct
classes** (`:60-63`) — it coincides with a class count only because the engine installs with
`addTransformer(…, false)`. If redefine-time application is ever added, that number silently
changes meaning.

**The rule.** *Measure the effect, not the permission.* Whenever a record says a thing was
allowed to happen, ask what observable would distinguish "it happened and worked" from "it
happened and did nothing" — and if the answer is none, the record is a decoration. **Armed is
not changed. Permitted is not performed.**

---

### 2.5 A gate whose `require()` was an empty body

**What was claimed.** `core/src/main/java/net/marcloud/mcp/core/flt/HookTools.java:20-24`, the
class's own javadoc:

> Gated by BOTH the ring (install at R-1, uninstall at R0 — enforced by the supervised gate) AND
> an `AccessGate` defense-in-depth check (CAP_CLASS_RETRANSFORM), following the L1-L7
> AND-composition rule.

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/se/AllowAllGate.java:16-19`:

```java
@Override
public void require(CapabilitySid cap, Privilege... privs) {
    // allow unconditionally (dev default)
}
```

`MonitorAccessGateIsLiveTest.java:31-38` names the defect in its own javadoc: *"while
`HookTools`' own javadoc claimed those tools were gated by … The second term of that AND did
nothing. A documented defense that is not there is the same shape as a patch that arms and does
nothing."* The production wiring at `core/src/main/java/net/marcloud/mcp/core/McpCore.java:337-339`
carries the correction as a comment on the line: *"L4/L5 defense-in-depth against the LIVE monitor
(was `new AllowAllGate()`, an empty method body, so the documented second term of the AND did not
exist)."*

**Why the claim survived.** The interface existed, the field was threaded, the constructor took
the gate, and every call site went through `require(…)`. **Every structural check passed.** An
empty method body is a perfectly legal implementation of a security interface, and it is
indistinguishable from a real one by reading the call graph. What was false was the *conjunction*
— one term of an AND, described in prose, next to a term that worked.

**What catches it now.** `MonitorAccessGateIsLiveTest`, which **drives the production registry**
`McpCore#registerBuiltins` builds and then reaches the `MmAccess` instance that call constructed —
deliberately **not** through `IoManager.invoke`, because going through the registry lets the
by-name tool table answer in the gate's place and the assertions would pass no matter which gate
was wired (`:40-49`). Its javadoc also records a real red run: a probe declared inside
`net.marcloud.mcp.core.se` was refused by the protected-object guard before the gate was ever
consulted, five errors, testing the wrong refusal.

**What does not catch it.** A blanket refusal. The test pins both terms with a counterweight each
(`:51-54`) precisely so "refuse everything" cannot pass. That is the discipline — see
**§3.5**.

**The rule.** *A defense described in an AND is not a defense until its negative half can be made
to pass.* If you cannot write the test that fails when the term is removed, you have written a
sentence, not a control.

---

### 2.6 A schema refusing a verb that exists

**What was claimed.** `drop` exists end to end: the intent kind, the controller, the live
half, the parser case. The worker's own report said so, in a table, with a "before / after".

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/drivers/action/ActTools.java:301-302`
published the `interact.kind` enum **without** `drop` in it, and there was no `slot` property.
The refusal a caller actually got was
*"argument 'kind' must be one of [dig, use, place, attack, hotbar, hold, block, release] but was
drop"*. Today `"drop"` is at `ActTools.java:302` and the `"slot"` integer property is at `:318`.
The parser was fine. The controller was fine. **The boundary refused the call before the handler
ever ran.**

**Why the claim survived.** The worker's own report is explicit that this was known and left
standing, as §6.1 of the dropverb audit — but a *reader* of the "before/after" table in §1 would
have read a shipped capability. **The producer existed; the gate in front of it did not know.**
That is the shape in its purest form: a capability documented as doing something, with an
absent producer on the only path anyone can reach it by.

**What catches it now.** `ADropIsReachableThroughTheRealToolBoundaryTest` (6 tests) chains
`IoProbe.validate(realSchema, {kind:drop, slot:17})` → `ActIntentParser.parseInteract` →
`DropController`, **against the schema `act_set` actually publishes**. The audit is explicit about
why that matters: a test that called the parser alone *"would have passed the entire time the
boundary refused the call."* Removing `"drop"` from the enum turns **3 of 6 red**.

**What does not catch it.** `IoProbe` checks `type` and `enum` only — see **2.13**, which is a
live instance of the same family. And `ToolDescriptionsMatchTheirBoundsTest` is per-tool and
hand-written: it covers what somebody thought to cover.

**The rule.** *Test the path a caller takes, not the function you wrote.* The unit under test was
never the parser. It was `act_set`, and the parser was downstream of the defect.

---

### 2.7 Tool descriptions naming arguments that do not exist

**What was claimed.** The `act_set` description is the model-facing contract: it names what the
tool accepts.

**What was on disk.** `hitX` / `hitY` / `hitZ` — arguments the parser honours, that the
description never named, so a model could not use them without reading the source. They are read
at `core/src/main/java/net/marcloud/mcp/core/drivers/act/ActIntentParser.java:306` and refused as
placement-only at `:277-284`. The mirror-image error existed too: `mode` advertised in the
`interact` clause but consumed only by `look`, where an `interact` `mode` was **accepted and
silently discarded**.

**Why the claim survived.** A description is prose about a parser. Nothing in the type system
relates them, and prose is not compiled. A model reading the description has no way to detect it
either — which is what makes this the highest-cost instance in the list for the project's actual
goal. **The consumer of the description is a language model, and it cannot ask.**

**What catches it now.** Two gates, both in
`core/src/test/java/net/marcloud/mcp/core/drivers/action/ActToolsTest.java`: `:544`
(`everyInteractArgumentIsBothReadAndDocumented`) and `:578`
(`everyLookArgumentIsBothReadAndDocumented`). "Read" is established by **submitting the argument
and observing the resulting intent differs** (`:571-575`), not by reading the parser — a parser
that stopped consuming the argument fails the test instead of silently discarding it. The
`mode` half asserts against the `interact` **clause** rather than the whole description string
(`:556-564`), because `mode` legitimately appears in the `look` clause and the test would
otherwise be unable to fail.

**What does not catch it.** A description that is *wrong* rather than *incomplete* — one that
names an argument correctly and then misdescribes what it does. Every mechanism here checks
**agreement**, and agreement is not correctness.

**The rule.** *Derive the vocabulary from the thing, not from a list you typed.* The strongest
single mechanism in this repository is `ActToolsTest.java:426-440`: the key list is read from
the map the handler actually emitted, so adding a field without documenting it fails. A
hand-written list is the empty-assertion shape this repository has now caught in itself more than
once (`:421-423`).

---

### 2.8 A constant with zero callers

**What was claimed.** `SlotClickMode.DROP_SLOT` exists, and its javadoc is a careful
transcription of vanilla's `Container.slotClick:445-457` — what the click does, what
`clickedButton` selects, why `slotId >= 0` is required, and what the confirmable fact is.

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/drivers/world/SlotClickMode.java:96`
declared it, and nothing in `core/src/main` read it. The dropverb audit's own table records this
as a before-column: *"`SlotClickMode` constants | declared, **zero callers** in `core/src/main`"*.
Today the single production reader is
`core/src/main/java/net/marcloud/mcp/core/drivers/act/LivePlayerActuator.java:518`.

**Why the claim survived.** A constant with a correct, thorough javadoc is indistinguishable
from a used one. Nothing fails, nothing warns, and the transcription can be *more* careful than
the code that would consume it. **The producer of the capability was the javadoc.**

**What catches it now.** Nothing automatic. The fix was a decision — noticing the constant had no
caller and asking what capability it implied was missing. This is the one instance in the list
where the detector was a person reading a table.

**What does not catch it.** Coverage of any kind: an unreferenced constant is not uncovered code,
it is unexercised *intent*.

**The rule.** *A constant with no caller is a decision nobody made yet.* When you find one,
either wire it or delete it — and say which, in the commit. Silence is the third option and it is
the only one that rots.

---

### 2.9 A tactic record only tests could reach

**What was claimed.** `RouteIntent` carried a `MoveTactic` field, and `NavController` published
one every tick. The chosen tactic was a **value**, readable by anyone.

**What was on disk.** Nothing put the two together. From
`core/src/test/java/net/marcloud/mcp/core/drivers/act/TheChosenTacticReachesTheRecordTheWalkIsCarryingTest.java:19-22`:

> The only caller of `RouteIntent#withTactic` was a test, so the field existed, the accessor
> existed, and the production path carried the goal and nothing else. A test that builds the pair
> itself proves only that a record can hold a value it was handed.

`RouteIntent.java:106` is the accessor; `MoveApplier.java:487-492` (`stamp`) is the production
caller that now exists. The seam audit's opening line is the shape in one sentence: *"The
representation exists; nothing publishes it."*

**Why the claim survived.** A value type, a field, and an accessor are all real, all tested, and
all **unconnected**. `TheChosenTacticIsAValueAndNotPrivateStateTest` proved a record can hold a
tactic — which is true, and is not the claim anyone cared about. Green tests for the component;
nothing for the seam.

**What catches it now.** The second test drives `MoveApplier` → `RouteExecutor` →
`NavController` over a plan the real `Planner` produced, and **never calls `withTactic` itself**
(`:24-29`). If production stopped stamping, it fails on a null tactic.

**What does not catch it.** A value test. The instance's own lesson, in its own words: *"A test
that builds the pair itself proves only that a record can hold a value it was handed."*

**The rule.** *A property of a component is silent about the property of the seam between
components.* Ask, of every accessor: **"and who reads this?"** The answer "the tests" is the
whole finding.

---

### 2.10 An observability field reporting a lease that did not exist

**What was claimed.** `act_status` exposes `heldBy` — the lease on each channel. It is documented
in the tool's own description at `ActTools.java:931-942` and emitted at `:1009`
(`row.put("heldBy", runtime.leaseHolder(s.slot()))`).

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/drivers/act/ActRuntime.java:550-557`,
in the file's own comment, describing the version that was there before:

> Holding the lease across the flag … **that theory is false and the comment it produced was a
> lie about this code** … All it did was leave `heldBy` reporting a holder for a channel nobody was
> using — a model reading `act_status` would conclude the channel was taken when it was free.

**Why the claim survived.** The field was **populated from a real lease structure that was held
past its useful life**. The value was never invented and never wrong-shaped; it was *timed*
wrong. And the consequence was aimed at the one consumer that cannot detect it: a model reading
`act_status` would see `heldBy=act_set/DIRECT` and conclude it was being blocked when the
channel was free. **A wrong observability field is worse than a missing one**, because the
consumer has no reason to distrust a field the system chose to publish.

**What catches it now.** A refusal is a **value**, not an absence — `ActRuntime.java:300-312`
(`Refused(slot, heldBy, heldAt, heldForTicks, reason)`), whose javadoc names the defect it
replaces: *"a submit that reported success and was then either silently overwritten by somebody
else or silently overwrote somebody else."* Plus five named tests covering explicit release,
cancel, auto-expiry, and the two real owners.

**What does not catch it.** A reader who trusts the field. That is the definition.

**The rule.** *Observability is a contract with a consumer who cannot check it.* Before
publishing a field, name the decision the consumer will make from it, then verify the field is
true **at the moment the consumer reads it**. A field that was true and is now stale is the same
defect as a field that was never true.

---

### 2.11 A declared schema bound nothing enforced

**What was claimed.** The schema declares `minimum` / `maximum`. A caller reading the tool
description sees the range and reasonably concludes it is enforced.

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/io/IoProbe.java:141-189`
(`validateValue`) enforces **`type`** (the switch at `:148-182`), **`enum`** (`:184-187`), and —
one level up — **`required`**. It does **not** read `minimum` or `maximum` at all. The file says
so itself at `:122-123`: *"**What this does NOT enforce, and a schema author must not assume it
does: `minimum` and `maximum`.**"*

**Why the claim survived.** Declaring a bound and enforcing it are separate acts, in separate
layers, and nothing in the schema object says which one happened. `IoProbe` is L7; the parser is
one layer in. A bound declared at L7 and enforced at the parser is **correct for every existing
bound** — `face` 0-5, `hotbarSlot` 0-8, `slot` 0-35 — and each names its range in the refusal
message. The schema is not lying. It is **declaring a fact about a different component**.

**Why it was documented rather than fixed.** `IoProbe.java:130-139` gives the reason, and it is
the right one: enforcing the bounds at L7 would alter the behaviour of **every tool at once**,
including tools whose bounds are currently wider than their parsers accept — so a caller could
start being refused at the boundary for a range it was previously allowed to send and have the
parser reject. *"Deciding which of those two behaviours is correct is a policy question about the
tool surface, not a defect, and it belongs to whoever owns that surface rather than to a change
smuggled in as a cleanup."*

**What does not catch it.** Anything today. This is live and documented, and the documentation is
the deliverable.

**The rule.** *A declared bound is a claim about a different layer.* Say which layer enforces it,
where, and what happens when they disagree. The alternative — a schema that implies enforcement
and does not perform it — is **instance 2.7 wearing a numeric hat.**

---

### 2.12 An interface method documented "never null" returning a one-frame-stale value

**What was claimed.** `ActActuator.mouseOver()` — *"What the player is currently looking at
(crosshair ray), never null."*

**What was on disk.** `core/src/main/java/net/marcloud/mcp/core/drivers/act/ActActuator.java:44-50`,
in the file, describing what it used to say:

> It read *"What the player is currently looking at (crosshair ray), never null"*, and the only
> production implementation satisfied it by returning `mc.objectMouseOver`. That field has
> exactly two writers — `Minecraft.runTick` and `EntityRenderer.renderWorld` — and this act loop
> fires at `runTick` **ENTRY**, so every act-layer reader has been handed a ray traced against the
> **PREVIOUS frame's** rotation, with nothing at the seam able to say so.

The belief-design audit called it *"the cleanest structural lie found in this project."*

**Why the claim survived.** The method returned **non-null**, always, by substituting
`Target.miss()`. So the "never null" half was true and the *"currently"* half was false — a
non-null value computed from stale input. **No null check can catch it**, because there is no
null. The declaration was a promise about presence and the defect was about **time**.

**What catches it now.** The return type carries it: `Graded<Target> mouseOver()` (`:64`), with
`mayActOn()` and a named constant `STALE_ROTATION` (`:80`) whose value is *"ray traced against
the PREVIOUS frame's rotation: objectMouseOver is…"*. The design is stated at `:52-57`: a second
`mouseOverGraded()` beside the old one would leave the ungraded door standing open, and the next
caller would walk through it and be lied to exactly as before — **while the diff that added the
honest one reads like a feature.**

**What does not catch it.** A caller that only checks null.

**The rule.** *A contract's adjectives are claims too.* "never null" is a promise about presence;
it is not a promise about currency, freshness, or provenance. When a method's output can be stale
or derived, **the type has to say so** — otherwise the honest caller and the careless caller are
indistinguishable from the outside.

---

### 2.13 Two javadocs citing test classes that do not exist

**What was claimed.** `Daylight.java:32` named `TheDaylightHelperCannotReadTheWorldTest`.
`ActOutcome.java:44` named `AEveryOutcomeSiteStatesItsDerivationTest`.

**What was on disk.** Neither class exists anywhere in the repository. Confirmed by `find` under
`core/src/test/java`, and by history: both names were **introduced** in commit `1ba0a3d` and
**repaired** in `7973f76` (`git log -S` on each name returns exactly those two commits).

**Both are now correct**, and the corrections are verifiable:
`Daylight.java:32` names `TheClockCostsTheWalkNoWorldReadTest.theDaylightHelperContainsNoWorldCallAtAll`,
which exists at
`core/src/test/java/net/marcloud/mcp/core/drivers/act/TheClockCostsTheWalkNoWorldReadTest.java:170`;
`ActOutcome.java:44` names
`ABeliefCensusOverTheActScenariosTest.everyOutcomeSiteInTheActLayerStatesItsDerivation`, which
exists at `…/ABeliefCensusOverTheActScenariosTest.java:192`.

**Why the claim survived.** **It sat in a comment.** `Daylight.java:32` is inside a `/** */`
block; `ActOutcome.java:44` is a `//` line. Neither is compiled. Neither is collected. No test
reads either file. A javadoc that cites a test class is a claim about **which test proves the
sentence above it**, and a reader who goes looking finds nothing — which is worse than a stale
line number, because the reader has been told there is a proof and there is none.

**Why this is the entry that proves the whole thesis.** The two sentences were **both fixed**, and
the paragraph *describing them* became false without either file changing. `guarantees.md` §1g
records it as a was/is row: *"two class names are cited in javadoc that do not exist" → **"both
were repaired; the paragraph had become false without the file changing."*** A document can
describe a defect that has since been fixed and keep doing it confidently.

**What catches it now.** Opening the file the name points at. `guarantees.md` §6.12 is the
consolidated form.

**What does not catch it.** Everything automated. The compiler cannot see a comment.

**The blind spot in §2.13's own detection method, found by hitting it on 2026-10-03.**
This entry's stated criterion is **"the name does not exist in the tree"**, and that is the test a
reader applies — `grep` the name, find nothing, conclude the citation is broken. **The criterion
fails when the name exists somewhere other than where the citation says it does**, because then
the search returns a hit and the check passes. **This is not theoretical: it happened in this
repository on the day this section was extended, and it nearly produced a wrong entry here.**

The concrete case. §2.2 quotes the method name `theSeamIsInTheShippedTreeNotTheTestTree`. That
method was **renamed** — it is now `theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree`
at `TheSuiteGoesRedThroughTheSeamTest.java:247`, and §7.1 is that repair. **So §2.2's `file:line`
is stale and its quoted name no longer names a method of that class.** The first sweep of this
entry got that right. **The second sweep got it wrong, in the opposite direction**, and the
reason is instructive: a tree-wide `grep` for the old name **does return a hit** — at
`TheShippedShelterCounterIsReachableAndNotATestTreeClassTest.java:60` — **so the name "exists",
and a checker reading §2.13's criterion concludes the citation is fine.** It is not fine: that
line is a `{@code}` **prose reference** inside a class javadoc, not a declaration, and the method
it names lives in a different class. **A `{@code}` citation is not resolvable by javadoc, so
nothing in the toolchain distinguishes "this names a method" from "this spells a method's name."**

**The two checks that do work, and both were needed here.** `grep -rnE '(public|private|protected)
[^;{]*\btheSeamIsInTheShippedTreeNotTheTestTree\s*\('` over the tree returns **zero** — a
declaration search, not a string search. And reading the hit's context shows it is a comment, not
a signature. **A name lookup that does not distinguish a declaration from a mention will report
§2.13's defect as absent, and the report will look like a clean bill of health.**

**So the amended rule is two-part.** *§2.13's defect is a name that does not resolve **where the
citation says it does** — not a name that does not appear anywhere in the tree.* And the reason
this matters beyond §2.13: a cross-file name collision is **more** likely than a unique name, not
less, because the methods being cited are all named after the same concept. **Three guards in
this repository cite a "seam" and two of them cite a method called
`theSeamIsInTheShippedTreeNotTheTestTree`** — the shape is not hypothetical here, it is a
property of how this codebase names things.

**And the instance this produced is §2.15 again.** The rename was correct, the repair was correct,
and the sentence describing them (§2.2) did not move. Nobody created that rot: **a correct fix
invalidated a neighbouring sentence and every gate stayed green through it** — which is the whole
of §2.15, arriving one entry away from where §2.15 is written.

**The rule.** *A javadoc citing a test is a receipt, and an unreceipted receipt is worse than no
citation.* If you name a test in prose, the name must be one you copied out of a file you
opened, and the sentence must still be true — because the class it names can be renamed, and the
sentence cannot notice.

---

### 2.14 A document citing a test that does not read what the row is about

**What was claimed.** `BuildScriptContractTest` was named as the class enforcing the signing
script's contract.

**What was on disk.**
`core/src/test/java/net/marcloud/mcp/core/kd/BuildScriptContractTest.java:30-31` reads
`src/main/native/core-jvmti/build-clang.sh` — a different script in a different directory — with
one `@Test` at `:41`, and that method (`jbrincIsEnvOverridableWithLocalDefaultRetained`) is about
`JBRINC` at `:47-53`. It reads nothing about `sign-patch.sh`.

**Why the claim survived.** **The name is evidence of nothing.** It is the class a search for
"what tests the scripts?" points at, it lives in a `kd` package, and it is about scripts. Every
property a reader would check from the outside says yes. Unlike a wrong line number — which
breaks when followed — a wrong class name **cannot** be caught by following the citation, because
a reader has no reason to doubt a name that specific. This is the error a contributor is least
able to detect in someone else's document and most able to commit in their own.

**What catches it now.** Opening the enforcing class and naming what it reads. `guarantees.md`
§6.12 exists for exactly this.

**What does not catch it.** The suite. `BuildScriptContractTest` is green, collected, and
irrelevant to the claim it was cited for.

**The rule.** *A row is a receipt or it is a claim, and it is a claim the moment the named test
opens a different file than the row describes.* Search for **what a test reads**, not for what
its name suggests.

---

### 2.15 The sentence a SUCCESSFUL fix falsified, read by whoever writes the prompt

*(Placed last, and it is **not** the least severe — it is the most expensive one in the list by the
measure §2.7 uses, since a wrong description costs more here than anywhere else. It goes last
because it was found last: on 2026-10-03, after this document was finished, which is itself part of
the finding. §5 says the list cannot be closed; this ordering is the evidence.)*

**What was claimed.** `core/src/main/java/net/marcloud/mcp/core/drivers/craft/Craft.java`, the
craft package's public face, said of itself at `:26-28` before this was repaired:

> What this deliberately does NOT do is craft anything. Executing a craft needs a live `CraftWindow`
> over the open container, and **no implementation of that interface exists outside the tests yet**.

**What was on disk when it was written.** True, and it was true about the *whole tree*. The craft
controller existed and was tested; **every** `CraftWindow` implementation was under `core/src/test`
— `FakeCraftWindow` in the craft package and `SimCraftWindow` in `eval` — so "outside the tests"
was not an overstatement, it was the situation. Then four things shipped, in this order, and none
of them was in `Craft.java`:
`act_set` grew `interact kind='craft'` into its published enum
(`ActTools.java:308`, and named in the same tool's own description at `:300-302`);
`LiveCraftWindow implements CraftWindow` (`LiveCraftWindow.java:61`);
`McpCore.java:365` builds one and hands it to `InteractApplier`, whose CRAFT branch ticks it
(`InteractApplier.java:150`); and `CraftWire.bind` resolves an item name to a controller over it.
**Every one of those is real and none of them is a comment.** The javadoc was not updated, so a
sentence that had been accurate became a lie **without one byte of it changing**.

**Why this is not a fifteenth copy of §2.6.** The other fourteen are *"the capability is finished
and nobody wired it"* — the producer is absent, and the document is an innocent bystander that
merely failed to notice. Here the wiring was **successful**, and its success is the entire cause of
the defect. Nothing goes red when a correct fix lands. **The green suite is the mechanism, not an
obstacle to it** — which is why no test written before the fix could have caught this, and why the
usual "drive the real seam" recipe (§3.8) does not reach it either: the seam is fine. It was the
sentence *about* the seam that was wrong.

**Why the consumer makes it the most expensive entry in this list.** Every other instance is read by
a maintainer, who can open the next file and find out. This one was read by **whoever writes the
prompt**, and a prompt author who concludes the surface cannot craft **will not write the three lines
that make crafting happen**. This is not hypothetical: the same absence was shipped verbatim inside
`craft_plan`'s own model-facing description, and a model round
(`.ai-notes/docs/audits/2026-10-03-model-round-model-round.md:316`, and again at `:361`) quoted it
back as *"NOTE (verbatim from the shipped tool's own description)"*. The consequence was recorded in
the same report at `:780`: crafting treated as unreachable in a substrate where a chest needs it.
**§2.7 says a description's consumer is a language model and it cannot ask. This is that, one level
up: the lie reached the model through a chain of four correct fixes.**

**What catches it now.** `core/src/test/java/net/marcloud/mcp/core/drivers/craft/TheCraftJavadocNamesTheVerbThatPerformsACraftTest.java`,
three tests, in the shape `TheHandshakeSentenceNamesNoKernelVerbTest` established: **the ruling is
asked of the driven registry, not of a list of forbidden phrases.** A phrase list copied into the
test would be a second copy of the sentence, and the second copy is the thing that rots — so the test
extracts every tool-shaped name from `Craft.java`'s class javadoc and asks the real, driven model
surface whether *that tool's published schema* accepts `kind='craft'`.

**Both mutation directions were run, and they are disjoint.**

| mutation | red | green |
|---|---|---|
| restore the original sentence at `:26-28` | **2 of 3** — `theJavadocNamesAVerbTheRealBoundaryCanPerformACraftWith` (no named tool accepts a craft) and `theJavadocNamesTheShippedWindowImplementationWhenOneExists` | `theRealBoundaryStillPerformsACraft` |
| keep the tool names, drop only the `LiveCraftWindow` mention | **1 of 3** — `theJavadocNamesTheShippedWindowImplementationWhenOneExists` only | the other two |

The premise test is asserted **first** and is what makes the other two mean something: it asserts the
boundary still accepts `kind='craft'` and that a shipped `CraftWindow` exists. Without it, the file
would be ruling about a world that might no longer exist, and a stale document would pass as honest.

**What does not catch it.** Every mechanism in §3, and the reason is structural: **a fix that lands
correctly is indistinguishable from a fix that was never made**, at every layer that exists. The
compiler sees no comment. The suite sees no behaviour change. Grep for the *wiring* finds it; grep for
the *sentence* does not, because nothing ever asks whether the two still agree.

**This instance is what `guarantees.md` §6.16 was already written about**
(`docs/agency/guarantees.md:1628-1634`, re-opened 2026-10-03; this entry used to cite
`:1465-1471`, correct on 2026-10-02 and no longer resolving because that file is under active
edit — **§3.2 applied to this file one entry below the entry that states §3.2**). In its words:
*"A claim of absence is the hardest kind of rot to notice, because the fix is
invisible from the document and the sentence still reads fluently — there is no diff to review and
nothing to contradict it. When a wave closes something, grep this file for the absence as hard as
you grep the tree for the presence."* That rule existed. §2.15 is the instance showing it **was
applied to rows in a markdown table and not to a javadoc in the same repository.** §3.2's principle
generalises further than §3.2 says: **a citation rule that only governs citations does not govern
the claims.** That sentence had a working `file:line` and was still the defect.

**The rule.** *A successful fix invalidates every sentence that described the absence it just filled —
and the fix is the only event in the system guaranteed to make all the gates stay green.* When a
capability goes from unreachable to reachable, the sentence saying it is unreachable is not a stale
comment, it is a **lie with a working citation**, and the reader who cannot audit it is the prompt
author. So: **when a fix lands, grep for the ABSENCE claims about what it connected, not only for the
code it added.** The absence claims are the only part of the change nobody reviews, because the diff
looks like a feature.

**Corollary, and it is the uncomfortable half.** Every other rule in §3 asks you to *write a test*.
This one cannot be discharged that way alone — the test written today guards the sentence found
today. The durable half is the practice: **a fix that makes a capability reachable must re-read the
files that documented its absence, in the same commit.** That is a review step, not a test, and no
amount of green proves it was done.

---

## 3. The rules

Each one consolidates a rule that already exists in this repository; the line cited is one opened
on the day this was written. Invented rules are marked as such. **As of 2026-10-03 there are
three: §3.11, which no existing anchor covered, and §3.12-§3.13, which arrived with the four
shapes merged on that date.** §3.12 and §3.13 each have **one** instance, and this section is
where a one-instance shape belongs — §7 is the catalogue and its admission criterion is two or
more, so promoting either of these to §7 would mean inventing a second instance to justify the
promotion. Their reasoning, including why they are rules rather than shapes, is in
`.ai-notes/docs/audits/2026-10-03-shape-merge-arbitration.md`.

### 3.1 Cite a line you opened

`guarantees.md` §6.10 (`docs/agency/guarantees.md:1565`, re-opened 2026-10-03; this entry cited
`:1143`, correct when written and moved when that file's §6 was renumbered — **§3.3, in the entry
that states §3.3**), and `vendored-tree.md` R1
(`docs/agency/vendored-tree.md:374`). Sixteen anchors corrected in one pass, two groups wrong as
a block, off by exactly one line each. **Open the file and count.** Do not carry a number
forward from a previous version of a document, a brief, or a stack trace.

### 3.2 An anchor that does not resolve is a defect, not a citation

`vendored-tree.md` R2 (`docs/agency/vendored-tree.md:379`), restated in `guarantees.md` §6.10. If
you cannot open the line, **cite the symbol** — `EvalSuite.survived`,
`FireDamage.LAVA_CONTACT_DAMAGE`, `Policy.class` — and say plainly that the line was not verified.
A symbol survives a refactor. A stale line number is a confident wrong answer, indistinguishable
from a right one to every reader except the person who opens the file.

### 3.3 A document that accumulates numbers will accumulate stale numbers

`vendored-tree.md` R3 (`docs/agency/vendored-tree.md:384`, re-verified 2026-10-03) and
`guarantees.md` §6.9 (`docs/agency/guarantees.md:1555`; this entry cited `:1136` and moved with
§6's renumbering). **Re-open anchors rather than adding new ones.** Two sweeps
of a then-905-line document found 46-plus wrong anchors, essentially all off by a few lines, and a
later pass caught **three of the integrator's own claims** that had aged into false. §2.3 above is
a fourth, found while writing this file, in the file the shape's own fix produced. **And this
entry is now the fifth: on 2026-10-03 a single re-read of this file found four of its own anchors
into `guarantees.md` stale at once**, because that file was being edited concurrently.

### 3.4 Absent is not zero

`guarantees.md` §1e row 38 (`docs/agency/guarantees.md:427`; this entry cited `:327` and moved),
and in the source at `ActTools.java:1158-1160` (this entry cited `:1012-1015`, which is now
`slotNameList()`): *"Absent, not zero, when there is no count: null says this line is not
a statement about a searched area, and 0 would claim a search ran and found nothing unread — a
stronger claim about the world than any site made."* The same shape at a different layer is
**2.10**: a field that was true and is now stale.

### 3.5 A control whose negative half cannot be made to pass is not a control

`guarantees.md` §6.11 (`docs/agency/guarantees.md:1583`; this entry cited `:1157` and moved with
§6's renumbering). `held == sheltered && floorHeld` is
false in three of four combinations, so a test asserting only `held` cannot tell *"sheltered and
hurt"* from *"exposed and untouched"*. The criterion is **invisible the moment the test exists** —
a green join test looks exactly like a real one. The stated reason it is a rule: *"A test that has
never been seen red is not evidence, and a join test that has never had a half deleted is not a
join test."*

The same discipline, stated from the other direction in the vendored audit: **a test that only
proves the new guard fires also passes against a guard that refuses everything**, which would be
a worse defect than the one it replaced. Direction 1 (guard removed) and direction 2 (guard
widened to a blanket refusal) must produce **disjoint** red sets — and both were run, giving
different failing assertions. `MonitorAccessGateIsLiveTest.java:51-54` is the in-tree instance:
each of the gate's two terms is pinned with a counterweight *"so a blanket refusal cannot pass."*

### 3.6 A printed command that silently returns nothing is worse than no command

`guarantees.md` §0 (`docs/agency/guarantees.md:69` — the section now *starts* at `:69`; this entry
cited `:59-83`, correct when written and wrong now, since `:59` is above the section heading),
the trap: run the suite **serially**, and
read the result from `core/target/surefire-reports/*.xml`, not the console line. The console
total and the on-disk reports disagreed until the directory was cleared, because probe runs
leave stale entries in it. Two reports there belong to classes that **do not exist in the tree**,
and summing the directory anyway gives a number wrong by exactly four. The peripheral audit names
the general form: a command whose output is empty because the thing it names is absent is
indistinguishable from a command that ran and found nothing.

### 3.7 Do not cite a test that does not read the thing the row is about

`guarantees.md` §6.12 (`docs/agency/guarantees.md:1598`; this entry cited `:1172` and moved with
§6's renumbering). **§2.14 above.**

### 3.8 Ask "and who reads this?"

`guarantees.md` §5.5 (`docs/agency/guarantees.md:1433`; this entry cited `:1037-1058` and moved)
states the generalisable form: *a
test that asserts a property of a component is silent about the property of the seam between
components.* Each of those defects was caught by asking that question, or by driving the real
thing — which is why that section is a list rather than a green suite. **§2.1, §2.6, §2.7 and
§2.9 are the same finding at four different layers.**

### 3.9 Do not relay a conclusion without the reasoning that produced it

`guarantees.md` §6.4 (`docs/agency/guarantees.md:1526`; this entry cited `:1107` and moved with
§6's renumbering). This mistake corrupted three separate
briefs in one session. **A wrong conclusion handed over as an anchor is more dangerous than no
anchor at all**, because a worker given a "verified anchor" stops verifying. One brief shipped a
wrong method descriptor (`File.<init>:(Ljava/lang/String;)V` where the real signature is
`(Ljava/io/File;Ljava/lang/String;)V`).

### 3.10 Report it as unenforced, or do not report it

`guarantees.md`'s own convention: a row without a test class is a claim, a row with one is a
receipt, and **"unenforced" is the only honest entry when no such test exists**. §2.8, §2.11 and
§2.13 are what the alternative looks like — a capability with no enforcement described as though
it had some.

### 3.11 A fix that fills an absence must re-read the sentences that described it

**The one rule here with no existing anchor in `guarantees.md`, and it is flagged as invented —
because the fourteen instances before §2.15 are all defects of omission, and nothing in this
repository yet guards against a fix creating one.** §2.15 is the whole argument: a successful,
correct, fully-tested fix left a sentence asserting the absence it had just filled, and **every gate
in the system stayed green through it.**

Two halves, and only the first is mechanical. The **test** half is
`TheCraftJavadocNamesTheVerbThatPerformsACraftTest`, and its shape matters more than its content:
the ruling is asked of the **driven registry** — extract the names the document spells, ask the real
surface whether that tool's published schema accepts the call — rather than of a forbidden-phrase
list, because a phrase list is a second copy of the sentence and the second copy is what rots. The
**review** half is the part no test discharges: when a fix makes a capability reachable, the diff
looks like a feature, so the sentences about its absence are the only lines in it nobody re-reads.
Grep for the absence claims in the same commit as the wiring.

### 3.12 Two claims about one number, and an interval that forgives both

**Invented, 2026-10-03, and it has one instance — which is why it is a rule and not a §7 entry.**

**The instance.** `core/src/main/java/net/marcloud/mcp/core/drivers/act/MoveApplier.java:125`
states that the chosen tail *"puts the mean at **5.54** ticks (277 ms) and leaves the SD at
1.18"*. `core/src/test/java/net/marcloud/mcp/core/drivers/act/TheWalkDelayIsSkewedLikeALatencyTest.java:32`
states that *"the mean moves 6.00 -> **5.55** ticks (300 -> 277 ms)"*. **Both describe the same
quantity, both are inside the assertion at `:119` — `mean > 5.2 && mean < 5.9` — and therefore
neither one can make any test red.** `5.54` and `5.55` are both correct to the precision each was
written at; they disagree, and the disagreement is invisible to every gate in the system.

**Why the existing rules do not reach it.** `guarantees.md` §6.15 says do not print line counts,
because a line count has no symbol to degrade into. **This instance shows that rule is not
sufficient**, and the gap is specific: a count is either right or obviously wrong, while a
**measured physical quantity is legitimately quoted to two decimals**, so two measurements of the
same quantity will differ slightly forever, and **an interval assertion wide enough to tolerate
the sampling noise is also wide enough to hide the disagreement.** §2.3 is a stale number; this
is two fresh numbers that never agreed, and no amount of re-counting finds it — both are true.

**Why it is dangerous rather than merely untidy.** It will not surface as a failure. It surfaces as
two citations that each read correctly, quoted by two different people, and **the report that
depends on which one you happened to open is the defect** — writing `5.54` from the production
comment and `5.55` from the test comment produces two documents that disagree about the same
measurement, with nothing in the tree to arbitrate.

**The rule.** **When one measured quantity is quoted in two places, the two must be pinned to each
other, or one of them must go.** Pin the *equality of the two claims*, not the value: asserting
`assertEquals(5.54, mean, 0.01)` would be worse, because sampling is random and a flaky assertion
gets deleted, which is a higher price than a wrong comment. And the guard **must carry a premise
assertion that both numbers exist** — otherwise deleting both comments satisfies the consistency
check vacuously, and a guard that passes on absence is §2.11 wearing a comparison operator.

### 3.13 A window is part of a test's claim, and a window nobody stated is a claim nobody made

**Invented, 2026-10-03, and it has one instance — §7.2 carries the worked case, and this is the
generalisation that outlives it.**

**The instance in one line:** a cost test swept `worldTime` over `0 .. 23999` — exactly one
24,000-tick day — against a **cross-night** ledger, so cross-night behaviour was outside the
experiment by construction. Full evidence, and the second candidate instance that was checked and
deliberately not counted, are in **§7.2**.

**The rule.** **A test's time window is part of what it claims, so state it, and check it against
the component's period before assuming the file is silent.** Three cases, and the third is the one
that bites: a window **wider** than the component's period tests accumulation without saying so; a
window **equal** to the period excludes the boundary and looks complete while doing it; and a
window **narrower** than the period is fine **only if the component has no behaviour at the
boundary** — which is a claim about the component and has to be made somewhere.

**Why this is a rule about disclosure rather than about doubling the window.** The direct fix —
every cross-period component drives two periods — is correct and expensive, and this project has
already been hurt by slow suites often enough that a blanket doubling is not affordable as a
default. The zero-cost half is the one to make mandatory: **write in the test what window it drives
and what that window cannot see.** `guarantees.md` §2 already does this for every instrument in
that document (*"it is structurally blind to…"*), so the precedent exists and the cost is a
sentence. **A blind spot that is written down is a decision; the same blind spot left implicit is
a defect that reports itself as coverage.**

**And the general form, because it is not about time.** *A test's window, its fixture, its driver,
and its mock are all part of its claim, and whatever they exclude is excluded silently.* That is
§2.1's shape with the test rather than the class confined to the test tree, and it is why the
criterion for catching it is always the same: **name what the instrument cannot see, in the
instrument.**

---

## 4. The instances a worker caught, not the integrator

This is the section a reader most needs, because every instance above was found by the person
responsible for fixing the code. These three were found by **someone else**, and each one is a
case where a plausible story was attached to a real observation without being checked.

**4.1 A commit with no test, found by the worker writing the document meant to prevent it.**
Commit `d5a82b6` (*"scripts: derive the signing classpath separator from the JVM"*) changed
`scripts/sign-patch.sh` and **one file, 14 insertions / 2 deletions** — `git show d5a82b6 --stat`
confirms. The fix was real and it mattered: `sign-patch.sh:77-79` asks the JVM for
`File.pathSeparator` and strips the CR, and `:91` joins with the derived separator. Before it,
joining `mvn dependency:build-classpath` output with a hardcoded `:` produced a classpath the
JDK rejected with **sixteen** "package does not exist" errors, on Windows only, and only at
signing time — *"the ceremony that is supposed to be the authority on signatures was the thing
that could not execute."*

**No test in the repository read `sign-patch.sh`.** Reverting the fix left every suite green.
The gap was found by the worker writing `guarantees.md` — **while producing a new instance of the
document's own failure shape inside the document meant to stop it.** The cost has since been
paid: `scripts/test_sign_patch.py` exists, class `TheSigningClasspathIsBuiltFromTheJvmTest`, six
`@Test` methods, mutation-verified in both directions.

**The lesson.** The document whose purpose is to stop the shape is not exempt from it. Nobody is.
Write the guard, then check the guard.

**4.2 A test count attributed to a deletion that never happened.** A worker had recorded that the
grading wave **deleted** a `@Test` method from `ADigCompletionSaysWhatItActuallySawTest`, and
used that to explain a count. Opening the diff: the class has **six** `@Test` methods today and
had the **same six** at the commit before. **No method was deleted.** The document's "7" was
simply a wrong number, and the deletion was invented to explain it.

The integrator's correction is the quotable part: *"The correction is that the **diagnosis** was
wrong; the **observation** was right. Getting the number right by attaching it to a false cause
is still a false claim."*

**The lesson.** When a number surprises you, the cheap move is to invent a mechanism for it. Open
the file instead. A plausible cause attached to a real observation is still a false claim.

**4.3 A broken build blamed on a worker who had touched nothing.** Two full-suite runs failed
intermittently. The report attributed them to a sibling's half-landed files. That sibling had
been dispatched as a **read-only scout and told to touch nothing**; it never wrote a file. The
half-landed files belonged to a different worker.

**The real cause is worse and it generalises.** The two workers held `compat/**` and `eval/**` —
directory-disjoint write scopes — but `mvn -pl core test` compiles the whole module, so a module
containing a half-written file fails to compile for everyone, intermittently, and the fork can
start against a truncated classpath and throw `NoClassDefFoundError` across unrelated packages.
**Directory-disjoint write scopes are not isolation when siblings share a Maven module.** The
integrator's operational change: no two live workers get the same module, and a worker whose
sibling is mid-write in the same module is told to expect its full-suite number to be void.

**The lesson.** The observation survives; the diagnosis does not. And the real cause was a
*design* property, not a person's mistake — which is what a real cause usually is.

**4.4 A diagnosis that was right when written and was never revised.** This one is here, and not
in §2, because it is not a defect in the code at all — it is a defect in a reader, and the three
above are the same kind of thing for the same reason. **§2 catalogues what a repository gets
wrong; this section catalogues what a diagnosis gets wrong, and both are read by the next
person.**

**What happened.** `.ai-notes/docs/audits/2026-10-03-env-blocks-subprocess.md` was written to
explain a 30-minute silent hang: clearing `JAVA_HOME` in the parent process deprived an `omp`
subprocess of its environment, so the first model call never returned. That diagnosis was
**reasonable on the evidence then in hand**, and it was **wrong**. Four counter-evidences
accumulated over the following hours, and the file was corrected — but the correction is a §2
heading at the top of a document whose §0 and §1 still state the original claim, so **the file
now opens by asserting the thing it spent its length disproving.**

**Why it is a shape rather than a slip.** Nothing about it is unusual. The observation was real
(`grep -c TURN` returned 0; the process really did hang for 30 minutes), the mechanism was
plausible, and the first three counter-evidences were each individually inconclusive. The failure
is the ordinary one: **a document written at time T is read at time T+n as a statement about
now**, and nothing in a document's format says which time it was true at. §2.3 is this shape with
a line count; this is §2.3 with a root cause, and it is more expensive because a wrong root cause
gets acted on — a reader who trusts it patches the wrong component, which is exactly the "another
patch in the wrong place" outcome §4.3 warns about.

**The rule, and it is the only one in this document that is about your own writing.** **Write
the confidence and the falsifier down at the moment you write the conclusion** — not "this is
the cause" but "this is the cause *unless* X, and X is what I would check next" — and **go back
and edit the original file when the evidence moves, rather than appending a correction to it.**
An appended correction is a second copy, and §3's "link, do not restate" applies to your own
diagnosis as much as to `guarantees.md`.

**The structural half, which is the part that generalises.** `guarantees.md` §6.16 already says
this about documents — *"a claim of absence is the hardest kind of rot to notice"* — and this
instance is the same rule pointed at a **diagnosis** instead of a claim. The gap is real and it is
in this repository's own coverage: §6.16's remedy is "grep for the absence in the same commit as
the fix", which has no mechanical analogue for *"you are wrong about a hang you already
explained"*. A conclusion has no diff to review, so the review that catches it has to be scheduled
by the author, while the diagnosis is still fresh enough to be cheap to revise.

---

## 5. What this document does not claim

- **It is not a postmortem.** The instances are catalogued so they can be recognised, not
  relitigated.
- **It does not assert that these fifteen are all of them.** Thirteen were found in one day;
  §2.3 was found while writing this file, and **§2.15 was found on 2026-10-03, after the catalogue
  was finished** — which is the strongest evidence in the document that the list cannot be closed.
  §1 of `guarantees.md` counts **ten** occurrences in one session and its own author counts more
  as the session continued; the two counts disagree because the second was written later. Both
  are honest at the time they were written, and that is **§3.3** in miniature.
- **§7 is a different shape from §2, and the fifteen above are all of §1's.** A reader who takes
  the sixteen-instance total in the header to mean sixteen instances of §1's shape is wrong: it is
  **fifteen instances of the documented-capability shape (§2.1-§2.15), two of a second shape about
  the detector rather than the detected (§7.1-§7.2), four diagnoses (§4.1-§4.4) and thirteen
  rules (§3.1-§3.13)**. **One shape per section is the property that makes this file navigable**,
  and merging §7 into §2 to make the numbering run would have been the first instance of the
  confusion it prevents.
- **§7.1's second instance is unrepaired.** `TheShippedShelterCounterIsReachableAndNotATestTreeClassTest`
  still asserts `getPackageName()` at `:80`, `:83` and `:86`, and its javadoc at `:73-74` still
  claims a `git mv` turns it red. **This document records the defect; it does not fix it**, and the
  fix belongs to whoever owns that test — the criterion is `getCodeSource()`, exactly as §7.1
  names.
- **It makes no claim about test counts or suite numbers.** Those move under concurrent workers,
  are read from `surefire-reports/*.xml`, and belong to `guarantees.md` §0 — which says so
  explicitly and marks which legs are measured and which are carried history.
- **Every `file:line` here was opened on the day it was written** — 2026-10-02 for §2.1 through
  §2.14, in a tree with three live workers in it, 2026-10-03 for §2.15, and 2026-10-03 for §7, §4.4
  and §3.12-§3.13, re-opened from disk rather than carried forward from the audits they came from.
  If one does not resolve, that is a defect in this file and §3.2 is what to do about it.
- **§7.1 is measured, not argued — and the measurement lives in another document.** The claim that
  the two trees are distinguishable by *load location* was executed, not inferred: a one-shot probe
  printed `main-tree Policy -> file:/D:/Project/MCPClient/core/target/classes/` and
  `test-tree GoalPolicy -> file:/D:/Project/MCPClient/core/target/test-classes/`, recorded in
  `.ai-notes/docs/audits/2026-10-03-seam-guard-wired-vs-shelved.md:127-130` (the probe class was
  deleted afterwards; the audit records the removal and that `target` holds no residue). **So
  §7.1's positive half — the new criterion *can* tell the two trees apart — is a runtime
  measurement.**
  **The negative half — that the old package-name guard stays green under the move — is stated as
  a deduction, not as a recorded red/green run for that specific mutation**: the audit says the old
  guard *"must stay green (the package name does not change)"* at `:132-133`, and its executed
  red/green table at `:213-217` covers the **caller-count** guard's three states, not a moved file.
  **Both halves are therefore recorded, and they are recorded as different kinds of evidence.**
  This document does not upgrade the second half to a measurement, and a reader who needs it should
  move `Policy.java` and run it rather than trust the arithmetic.
- **What §7.1 does rest on, read from disk:** the criterion at
  `TheSuiteGoesRedThroughTheSeamTest.java:339` and the three live `getPackageName()` assertions at
  `TheShippedShelterCounterIsReachableAndNotATestTreeClassTest.java:80`, `:83` and `:86`, plus the
  false javadoc claim at `:73-74`. **No file was moved by the author of this section**, and the
  package-declaration argument is stated independently in the seam test's own javadoc at
  `:220-227`.

## 6. Related

- `guarantees.md` — the **53** guarantees, each with a `file:line` and the test class that goes
  red. (This line said 47 until 2026-10-03; the same wrong number stood at `:14`, in a file whose
  own §3.3 is the rule against it. A stale count in a document about stale counts is still a stale
  count — **§2.3, found twice in one file by the person fixing §2.15.**)
  §5 is the short form of this document; §6 is the rules it consolidates.
- `vendored-tree.md` — the vendored Minecraft tree's reference manual. §4.1 R1-R3 are the same
  citation rules, written for a tree where "the file exists" and "the game has it" are different
  facts.
- `.ai-notes/docs/audits/` — where all of this was found. **Gitignored** (`.gitignore:56`), so
  it does not travel with the repository, and this file is the part that does.
- **§7 and §2.1-§2.15 are different shapes** and are kept in separate sections on purpose: §2 is
  about a documented capability whose producer is absent, §7 is about a detector that cannot
  distinguish the state it exists to detect. A §2 reader scanning for "what goes red when a
  producer is missing" finds nothing in §7, and that is the correct outcome.
- `.ai-notes/docs/audits/2026-10-03-shape-merge-arbitration.md` — **why §7 exists and why §2 stops
  at §2.15**: the instance count behind each of the four 2026-10-03 shapes, the admission
  criterion, and the candidate instance of §7.2 that was checked and deliberately **not** counted.
  Gitignored like the rest of that directory, which is why its conclusions are restated here.

---

## 7. A second shape: a guard whose criterion cannot tell the states apart

**Read §7 if you write a test that is supposed to catch something.** Everything above §2 is about
a **capability** being absent from the shipped artifact. This is about the **detector**: an
assertion that is green, is collected, runs on every build, and is structurally unable to
distinguish the state it exists to detect from the state where the defect is present. The defect
is not in the code under test and not in the document — **it is in the instrument**, which is why
none of §2's escape routes reach it: there is no missing producer to grep for, no stale anchor to
re-open, and no prose to contradict.

### 7.1 A name that does not change when the thing moves

**The shape.** A guard asserts an identifier — a package name, a class name, a path — in order to
prove a class lives in one tree rather than another, **and the identifier it asserts is invariant
under the move it is supposed to detect.** The assertion is true, the test is green, and the guard
is immune to precisely the event it was written for.

**Instance 1, repaired: the seam guard.**
`core/src/test/java/net/marcloud/mcp/core/eval/TheSuiteGoesRedThroughTheSeamTest.java` carried a
method named `theSeamIsInTheShippedTreeNotTheTestTree` whose three assertions were all
`assertEquals(getPackageName())`. Moving `Policy.java` from
`core/src/main/java/net/marcloud/mcp/core/eval/` to
`core/src/test/java/net/marcloud/mcp/core/eval/` **does not change its package declaration by one
character** — both sides read `package net.marcloud.mcp.core.eval;` — so the exact move the guard
was named for left it green. The repair is at `:247`
(`theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree`), which reads where the bytes were
actually loaded from: `type.getProtectionDomain().getCodeSource()` at `:339`. It carries an
anti-vacuity clause at `:256-259` — the location must contain `/classes/`, so a classloader that
publishes no code source fails the assertion instead of satisfying it. **A move changes where the
class is loaded from, and that is the property the guard now reads.**

**Instance 2, LIVE, and this is why the shape is catalogued rather than filed as history.**
`core/src/test/java/net/marcloud/mcp/core/eval/TheShippedShelterCounterIsReachableAndNotATestTreeClassTest.java:77`
(`theAccumulatorAndItsToolAreShippedNotTestTreeClasses`) asserts `getPackageName()` three times,
at `:80`, `:83` and `:86`, for `NightShelter`, `ShelterTools` and `ClientBody`. **The same three
`assertEquals(getPackageName())` calls, in the same tree, on the same day.** Its own javadoc at
`:73-74` states the property it believes it has: *"Moving any of the three down into `src/test`
turns this red even if every other test in the repo stayed green."* **That sentence is false, and
falsified by the mechanism described above.** Moving `NightShelter.java` to
`core/src/test/java/net/marcloud/mcp/core/eval/` leaves its package name untouched and the
assertion green.

**The second instance is the stronger half, because it is the documented-limit case.** The class
javadoc at `:57-62` already knows: the package assertion *"could not have caught a tool declared in
three tables and built in none"* — it cites instance 1 by name, as *"the shape
`theSeamIsInTheShippedTreeNotTheTestTree` settled on"*, and calls the package assertion *"the
honest form"*. **The limit was written down, named, cited, and inherited by a second guard that
kept the criterion anyway.** A known weakness in a criterion is not a defect until somebody relies
on the criterion for the property the weakness removes, and `:73-74` is exactly that reliance.
Note also what the file gets right: `:105` (`theModelSurfaceCarriesTheShelterCounter`) drives
`McpCore.registerBuiltins` and is a genuinely different kind of check — *two guards over one
property, and either can go red with the other still green*, which is the right structure. **It is
the package guard that is the weak one, and the strength of its sibling is what makes it easy not
to notice.**

#### 7.1.1 Why this is one entry and not two, stated so the decision can be argued with

Instance 2 is arguably a **different shape** from instance 1, and the argument for splitting is
real: instance 1 is *an unaware guard with a criterion that cannot see*, while instance 2 is
**a guard whose criterion cannot see, described in prose as though it could** — a claim about a
judge's power, sitting next to the judge. **That is §6.16's neighbourhood** (a claim of absence,
or of capability, rotting quietly), and §2.15's mechanism.

**It stays one entry, for two reasons, and the first is a count.** Splitting would make
7.1.1 a shape with **one** instance, and this document's admission criterion — stated in §3's
preamble and applied to B and C on the same day, for the same reason — is two or more. The
alternative is to invent a second instance of the split shape, and **§7.2 exists precisely to
explain why that is not a neutral act**: a known-and-mitigated deviation and an undetected blind
spot look identical in a search, and counting the first as the second is how a catalogue starts
lying.

**The second reason is that the two halves are not separable in the defect, only in the
description.** Ask what fixing instance 2 requires: not more prose, and not a second criterion —
**it requires changing the criterion**, because the false sentence at `:73-74` is *entailed* by
the blind one at `:80`. Repair the criterion and the sentence becomes false and gets fixed with
it; leave the criterion and no amount of javadoc care prevents a reader from trusting it. **A
second entry would imply the false sentence is an independent defect that a careful writer could
have avoided on its own, and it is not: careful writing is exactly what this file already does.**

**What is recorded instead of a split is the aggravating factor, and it is named.** The severity
of instance 2 is not "a guard was blind" — it is **"the blindness was documented, cited to the
file that discovered it, and inherited anyway."** That is a different *cost*, not a different
*shape*, and §3.13 states the rule it implies. **If a second instance of the documented-limit case
appears, it becomes §7.3, and this paragraph is the argument that it should.**

**How both escaped.** Nothing, at any layer. A `git mv` between source roots is not a refactor the
compiler flags, not a behaviour a test observes, and not a fact any document records. The class's
own name is the strongest possible signal that the guard works — a reader auditing it would check
that the method exists, that it is `@Test`, and that it asserts something about packages. **Every
one of those checks passes while the guard is blind**, which is §2.14's shape pointed at the
guard rather than at the row citing it.

**The rule.** **A guard that claims to detect a *change of state* must assert something the change
alters.** Ask what a `git mv` does to your criterion: if the answer is "nothing", the criterion is
incapable of detecting the move. In this repository the criteria that survive that question are
**where the bytes were loaded from** (`getProtectionDomain().getCodeSource()`) and **how many
callers exist after comments are stripped** — the two the seam test uses at `:299`
(`theShippedTreeMustNotClaimACallerThatItDoesNotHave`) and `:247`. A path string is not enough:
`new File(path).exists()` is invariant under `git mv` for the same reason the package name is.

**The corollary, and it is the part that generalises past this repository.** *A criterion's
documented limitation is not a mitigation.* Instance 2 is not sloppy work — it is careful work with
a known weakness, cited to the file that discovered it — and it is still wrong, because the
sentence at `:73-74` claims a property the criterion does not have. **If you know your criterion is
blind to a case, the obligation is to add the second criterion, not to describe the first one
honestly.** Honesty about a limit is worth having; it is worth exactly nothing at the moment
somebody reads the method name instead of the javadoc.

### 7.2 A window exactly one period long, on a component that spans periods

**The shape.** A component's time semantics are **periodic or cumulative**; its test's time window
is **exactly one period**; and every behaviour that exists only *across* a period boundary is
therefore outside the experiment by construction. The test is not wrong about what it asks, the
shape is not an oversight, and the suite is green — **it is green in a way that reads as coverage
of a property it never touched.**

**Instance 1, and it is not hypothetical: this one shipped a defect.**
`core/src/test/java/net/marcloud/mcp/core/eval/TheShippedShelterCounterCostsEightReadsASampleAndNothingADayTickTest.java`
drives `worldTime` over `0 .. 23999` — at `:191` and again at `:226`, both loops the full day.
`worldTime` cycles every **24,000** ticks, so **the experiment contains exactly one night and can
never contain two.** The component under test, `NightEnclosure`, is a **cross-night ledger**: it
holds samples, and the thing worth testing about a ledger is what happens between entries. The
defect that shipped is in
`.ai-notes/docs/audits/2026-10-03-night-ledger-reset.md` — the ledger reset only when the `World`
**object identity** changed, so in one world it folded every night since the client connected into
one window; `samples` passed 8,386 on the second night and the budget the tool description names,
*`(nightTicksTotal - samples)`*, **went negative**, which is the arithmetic the shipped
`night_shelter` description teaches the model to perform. The repair and its guard are in
`NightEnclosure.java` (the `nightCycle` reset trigger at `:87`, documented at `:33-42`) and
`core/src/test/java/net/marcloud/mcp/core/eval/TheShelterLedgerIsTheCurrentNightAndNotTheSumOfEveryNightTest.java`,
which drives **two** nights on purpose.

**The second candidate instance was checked and deliberately not counted, and the reason is the
useful part.** `WorldViewCapture.timeBucket` (`core/src/main/java/net/marcloud/mcp/core/drivers/world/WorldViewCapture.java:544`)
labels `night` from `13000` and `sunrise` from `23000`, while the measured curve is `13807 .. 22193`
(`core/src/main/java/net/marcloud/mcp/core/drivers/world/Daylight.java:95-96`) — a skew of 807 ticks
at one end and 806 at the other, in **opposite directions**, so it is not a constant offset that
one correction would remove. That looks like instance 2 and is not: **the deviation was found,
measured, and routed around rather than left blind**, at
`core/src/test/java/net/marcloud/mcp/core/drivers/world/TheDiffCarriesTheClockTest.java` — `DUSK`
and `DAWN` are the *measured* constants at `:31-32`, the test asserts the two ticks either side of
dusk at `:60-63`, and the fix was to carry the raw clock and a `daytime` flag beside the bucket
(`:23-26`) so a consumer no longer has to infer night from a label that is 807 ticks early.
**A known-and-mitigated deviation is a decision with a receipt; a window that cannot see the
boundary is a blind spot with a green suite.** They look alike in a grep and are opposites, and
counting the first as the second would have manufactured the two instances this entry needs in
order to exist.

**How it escaped.** The cost test answers a real and correctly-posed question — *how many block
reads does one sample cost, and is it zero in daylight* — and its chosen shape is **optimal for
that question**: a full day sweep is what lets one test see both the 8-reads-per-sample night half
and the zero-read daylight half. The blind spot is a **by-product of a good choice for a different
question**, which is the reason it survived review: nobody is going to argue that a one-day loop
is the wrong instrument for measuring the cost of a day. The class javadoc at `:13` even says
*"What one night of the shipped shelter counter ACTUALLY costs"* — **accurate, and the accuracy is
the problem**: the file documents a one-night scope and is therefore honest, while the component
whose cost it measures is a multi-night instrument and nothing in the file says the ledger's
cross-night behaviour is untested here.

**The rule.** **When a component's time semantics are periodic or cumulative, a test window of
exactly one period has declared the boundary out of scope — say so in the test, or cross it.**
The cheap half costs nothing and this repository already has the precedent for it: `guarantees.md`
§2 writes, for every instrument, what it is structurally blind to. The expensive half is *"drive
at least two periods"*, and it is expensive precisely because this project has already been hurt by
slow suites, so **the default should be the disclosure and the doubling should be a deliberate
per-component decision** — not a blanket rule nobody can afford.

**The general form, one level up.** *A test's window is part of its claim, and a window chosen to
answer question Q silently answers "no" about every question it was not asked.* That is §2.1's
shape — a capability reachable only from the test tree — with the test rather than the class as the
thing confined to its own tree.