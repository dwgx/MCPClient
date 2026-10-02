# The failure shape: a capability documented as doing something whose producer is absent

2026-10-02. Thirteen instances of one shape were found in a single day across seven parallel
work streams, plus three caught by a worker rather than by the integrator. **This document
catalogues fourteen worked instances: the thirteen, plus one found while writing it** — §2.3, a
stale line count sitting in the javadoc of the very file that fixed instance §2.1. That is not
padding; it is the argument for §3.3, and it is why the header count was written last.

**This is the catalogue for people who have never been in this repository.** It is not a
narrative and it is not a guarantee list. Read §2.1 if you write a test-only helper. Read §3 if
you write anything at all.

**What this document is not.** It does not tell you what the kernel guarantees — that is
`guarantees.md`, 47 rows each carrying a `file:line` and the test class that goes red. It does
not tell you what is in the vendored Minecraft tree — that is `vendored-tree.md`. Where an
instance below is also a guarantee row, the row is linked, not repeated. **Link, do not
restate:** a second copy of either document is a second copy that goes stale.

**Why it exists at all.** These thirteen were recorded only in audit reports under
`.ai-notes/docs/audits/`, which is gitignored (`.gitignore:56`, the `.ai-notes/` line). Those
reports produced these changes, and **none of them will travel with the repository.** A
contributor who has never seen this project cannot currently learn any of it. This file is the
part that will.

**This document originally carried a count, and I had to take it out.** The first draft said the
directory holds "68 reports, 38 of them dated the day". Re-measuring later the same evening,
with three workers still writing: **70 and 47**. `guarantees.md:39-41` carries the same kind of
count at **61 and 38**, and it is stale in the same way. Neither number is wrong about anything;
each is a claim about a directory being written to while it is read. **§3.3 is not a rule about
someone else's document.** It is the reason this one quotes no count — read the directory.

**A citation in this document is a promise.** Every `file:line` below was opened on the day it
was written. That sentence is doing more work than it looks: §1g of `guarantees.md` records a
single sweep over a 905-line document that corrected **16 wrong anchors** across nine rows, and
an earlier sweep corrected **30-plus**. Two of those groups were wrong **as a block**, every
member off by exactly one line, which is the signature of a number copied out of a diff hunk
header rather than counted off an open file. They were spread across the document rather than
concentrated in one stale corner, which says the anchors were being written **by inference**. If
you find an anchor here that does not resolve, it is a defect in this file, not a curiosity:
that is what the rules section is for, and it is the same rule the wrong anchors were caught by.

---

## 1. The shape

**A capability documented as doing something whose producer is absent, renamed, never
executed, or unreachable.**

The interesting part is not that each instance was wrong. It is that **almost none of them
could be caught by the machinery the project already had** — not the compiler, not the test
suite, not grep. Four separate escape routes are documented below, and a reader who has only
learned "my tests are green, therefore I am fine" has learned the wrong lesson:

| escape route | how the claim survived | where |
|---|---|---|
| **It sat in a comment.** | A javadoc or a `//` line is not compiled and not collected. The name in it can be wrong for years. | §2.13, §2.14 |
| **It was a number read from a diff.** | A hunk header shows a line number; it does not show that the line is the thing. | §2.2, and all 46-plus wrong anchors in `guarantees.md` |
| **It was true when written, and the file moved.** | The claim was correct. Then somebody fixed the code, or edited the file it described, and the sentence did not. | §2.3, §2.13, and three of the integrator's own claims |
| **It was a decision, not an implementation, and nobody asked.** | The design says the model decides; the enforcement half was never measured against it. | §2.1 |

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

**What was on disk.**
`core/src/test/java/net/marcloud/mcp/core/eval/TheSuiteGoesRedThroughTheSeamTest.java:216`:

```java
public void theSeamIsInTheShippedTreeNotTheTestTree() {
```

**Lower case on the leading `t`.** Not a different test, not a missing test — the right method in
the right file, with the first letter wrong. The claim underneath was **entirely true**: the
method asserts the seam's package is `net.marcloud.mcp.core.eval` for all three shipped classes,
which is exactly what instance 2.1 needed.

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
test, and no grep for `1317` outside this paragraph and `guarantees.md:940` — which carries the
same number, and is the same defect once more.

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

## 3. The rules

These are **not new**. Each one consolidates a rule that already exists in this repository; the
line cited is one opened on the day this was written. Invented rules are marked as such, and
there are none — the one rule below with no existing anchor is flagged.

### 3.1 Cite a line you opened

`guarantees.md` §6.10 (`docs/agency/guarantees.md:1143`), and `vendored-tree.md` R1
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

`vendored-tree.md` R3 (`docs/agency/vendored-tree.md:384`) and `guarantees.md` §6.9
(`docs/agency/guarantees.md:1136`). **Re-open anchors rather than adding new ones.** Two sweeps
of a 905-line document found 46-plus wrong anchors, essentially all off by a few lines, and a
later pass caught **three of the integrator's own claims** that had aged into false. §2.3 above is
a fourth, found while writing this file, in the file the shape's own fix produced.

### 3.4 Absent is not zero

`guarantees.md` §1e row 38 (`docs/agency/guarantees.md:327`), and in the source at
`ActTools.java:1012-1015`: *"Absent, not zero, when there is no count: null says this line is not
a statement about a searched area, and 0 would claim a search ran and found nothing unread — a
stronger claim about the world than any site made."* The same shape at a different layer is
**2.10**: a field that was true and is now stale.

### 3.5 A control whose negative half cannot be made to pass is not a control

`guarantees.md` §6.11 (`docs/agency/guarantees.md:1157`). `held == sheltered && floorHeld` is
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

`guarantees.md` §0 (`docs/agency/guarantees.md:59-83`), the trap: run the suite **serially**, and
read the result from `core/target/surefire-reports/*.xml`, not the console line. The console
total and the on-disk reports disagreed until the directory was cleared, because probe runs
leave stale entries in it. Two reports there belong to classes that **do not exist in the tree**,
and summing the directory anyway gives a number wrong by exactly four. The peripheral audit names
the general form: a command whose output is empty because the thing it names is absent is
indistinguishable from a command that ran and found nothing.

### 3.7 Do not cite a test that does not read the thing the row is about

`guarantees.md` §6.12 (`docs/agency/guarantees.md:1172`). **§2.14 above.**

### 3.8 Ask "and who reads this?"

`guarantees.md` §5.5 (`docs/agency/guarantees.md:1037-1058`) states the generalisable form: *a
test that asserts a property of a component is silent about the property of the seam between
components.* Each of those defects was caught by asking that question, or by driving the real
thing — which is why that section is a list rather than a green suite. **§2.1, §2.6, §2.7 and
§2.9 are the same finding at four different layers.**

### 3.9 Do not relay a conclusion without the reasoning that produced it

`guarantees.md` §6.4 (`docs/agency/guarantees.md:1107`). This mistake corrupted three separate
briefs in one session. **A wrong conclusion handed over as an anchor is more dangerous than no
anchor at all**, because a worker given a "verified anchor" stops verifying. One brief shipped a
wrong method descriptor (`File.<init>:(Ljava/lang/String;)V` where the real signature is
`(Ljava/io/File;Ljava/lang/String;)V`).

### 3.10 Report it as unenforced, or do not report it

`guarantees.md`'s own convention: a row without a test class is a claim, a row with one is a
receipt, and **"unenforced" is the only honest entry when no such test exists**. §2.8, §2.11 and
§2.13 are what the alternative looks like — a capability with no enforcement described as though
it had some.

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

---

## 5. What this document does not claim

- **It is not a postmortem.** The instances are catalogued so they can be recognised, not
  relitigated.
- **It does not assert that these thirteen are all of them.** They are the ones found in one day.
  §1 of `guarantees.md` counts **ten** occurrences in one session and its own author counts more
  as the session continued; the two counts disagree because the second was written later. Both
  are honest at the time they were written, and that is **§3.3** in miniature.
- **It makes no claim about test counts or suite numbers.** Those move under concurrent workers,
  are read from `surefire-reports/*.xml`, and belong to `guarantees.md` §0 — which says so
  explicitly and marks which legs are measured and which are carried history.
- **Every `file:line` here was opened on 2026-10-02**, in a tree with three live workers in it.
  If one does not resolve, that is a defect in this file and §3.2 is what to do about it.

## 6. Related

- `guarantees.md` — the 47 guarantees, each with a `file:line` and the test class that goes red.
  §5 is the short form of this document; §6 is the rules it consolidates.
- `vendored-tree.md` — the vendored Minecraft tree's reference manual. §4.1 R1-R3 are the same
  citation rules, written for a tree where "the file exists" and "the game has it" are different
  facts.
- `.ai-notes/docs/audits/` — where all of this was found. **Gitignored** (`.gitignore:56`), so
  it does not travel with the repository, and this file is the part that does.