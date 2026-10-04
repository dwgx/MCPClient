# Guarantees and instruments: what this kernel now holds, and what pins it

2026-10-02. Written after a day of auditing-and-fixing, not before one; refreshed the same
evening after the clock and grading waves landed, then again after the damage, enclosure and
corpus waves, then for a **full anchor sweep**, and then **a second full anchor sweep** after the
seam, the box row and the tool-layering wave. **53 guarantees**, each carrying the `file:line`
that implements it and the **exact test class** that goes red if it regresses — or the word
**unenforced**, which is the only honest entry when no such test exists.

**2026-10-03: every row now also carries 距北极星 — its distance from the north star.** The
premise of this file is that a guarantee a reader cannot check is the failure mode worth
stopping, and until this pass §1 could not answer the Owner's only question: *what do these
guarantees have to do with finishing a game?* **Six of the 53 decide a match; the rest are
instruments for those six, guards on one branch, or receipts for a fixture** — and the table
printed the last group in the same shape as the first. §1h counts it and **§1h.1 names the
rows that were overstated by their own placement**, which is where the finding is: **`FireDamage`
and `SurvivalDamage` have no production caller at all** (verified twice by grep), so three rows
about damage pin an eval fixture rather than a client.

**Every `file:line` in this file has been opened, twice, and the second sweep is the one with the
lesson in it.** The first corrected thirty-plus wrong anchors left by the version before it. The
second found **sixteen** more, in nine rows of §1 and four places in §4/§5 — and two of those
groups were wrong *as a block*, every member off by exactly one line, which is the signature of a
number copied out of a diff rather than counted off a file. **The third found fifteen, and all
fifteen were in one section.** That is not a coincidence and it is not "count your lines"
(**§6.10**). It is **§6.13**: a sweep that re-reads everything finds what a sweep that re-reads
the *moving* part finds, at a fraction of the cost — and **§6.14**, which is the rule the fifteen
actually demonstrate: **an anchor into a file under active change is a half-life, not a citation.**
Every one of the fifteen was an `EvalSuite.java` line that moved because a task was added below it.
The number was not wrong when written; it was correct with an expiry date, and nothing recorded the
expiry.

**A guarantee's row must cite a test that reads the thing the row is about.** That is the specific
error this file exists to stop and it has already happened here twice — once in this file while it
was being written, and once for `BuildScriptContractTest`, which reads `build-clang.sh` in a
different directory (§1d). **A test class's name is not evidence of what it reads**, and it is the
one error in this document a reader cannot detect for themselves.

**Why this file exists at all.** This repository has produced the same defect shape
repeatedly: a capability documented as doing something whose producer is absent, renamed,
or never asked a question. A patch that armed and changed nothing. A security gate whose
`require()` was an empty method body. A schema that refused a verb the actuator had. Tool
descriptions naming arguments that did not exist. An interface method documented "never
null" that returned a one-frame-stale value to zero callers. **A movement-trace regression
test that surefire never collected.** **Ten `w.health() > 0.0D` comparisons that evaluated
`20.0 > 0.0`, in the very suite whose charter is "every verdict is a world fact"** (§1f).

So the job of this document is not to describe the design. It is to make each guarantee
**checkable rather than believable**. A row without a test class is a claim; a row with one
is a receipt. §1 separates them deliberately and does not blur the line.

**Language.** English, unlike the rest of `docs/`. The session's own reports are English and
this file is a distillation of them; where the two disagree, the reports under
`.ai-notes/` are the longer form and this file is the shorter one.

**What is not here.** `.ai-notes/docs/audits/` holds the session's audit reports, most of them
dated 2026-10-02. Those produced these changes, and the directory is gitignored
(`.gitignore:56`, the `.ai-notes/` line), so none of them will travel with the repository.
This file is the part that will. **The count of those reports is deliberately not given: this
sentence previously said 61, and the directory gained files while the sentence was being written,
which is the same shape as a line count that rots on the next edit.**

The correction logs are `.ai-notes/docs/audits/2026-10-02-wave10-guarantees-refresh.md` and
`.ai-notes/docs/audits/2026-10-02-wave12-doc-sweep.md`, both gitignored and both worthless
without this file.

---

## 0. How to check anything in this document

One command, and nothing in §1 that claims enforcement survives without it:

```bash
export JAVA_HOME='D:\Software\Developer\jdk\25'
./mvnw.cmd -B -ntp -pl core -am test
```

**`-am` is not optional, and its absence has a price with a shape worth naming.** `core/pom.xml:55-60`
declares `client` as **`provided`**, so `-pl core` alone does not put `client` in the reactor and
Maven resolves the **stale installed jar** rather than the source in the tree. **A `NoSuchMethodError`
on a method that plainly exists in the source is then a stale artifact, not a defect** — and
**eleven** phantom errors were attributed to source that way the day before this pass. The failure
is worst precisely when a worker is most likely to be reading `core`: **if `client` changed,
`-pl core test` is the wrong command**, because it is the command that will hide that.

Run it **serially**. Two concurrent runs against one `core/target` have already collided
once this session: `EphemeralSynthesizerTest` killed the surefire fork and the run aborted
at 97 of 124 tests with `BUILD FAILURE`, and those numbers had to be discarded.

Read the result from `core/target/surefire-reports/*.xml`, not from the console line. The
console total and the on-disk reports disagree, and **summing the directory is not the answer
either**: the directory has to be **joined against the source tree first**, because probe runs
leave stale entries in it and a stale entry sums just as loudly as a real one.

**And that join is now a command rather than a habit.** `python scripts/test-census.py --run`
does it for every module, both report populations, and fails loudly on a ghost or an uncollected
class. Its convention — which module list, which two populations, what "green" excludes — is
`docs/agency/test-census.md`. **The number below this paragraph is the last one in this file that
was produced by hand**, and it is stale for the reason every other number here went stale.

**The number as of this writing: 323 classes / 2025 tests / 0 failures / 0 errors / 1 skipped.**
The directory holds **326** XML files and a naive sum over all of them says `2028`. That sum is
**wrong by three**, and all three are reports whose source does not exist anywhere under
`core/src/test/java`:

| stale report | tests | class in `core/src/test/java`? |
|---|---|---|
| `net.marcloud.mcp.core.compat.ScratchNullTraceProbeTest` | 1 | **absent** |
| `net.marcloud.mcp.core.compat.ScratchVarintProbeTest` | 1 | **absent** |
| `net.marcloud.mcp.core.compat.ScratchWorldNullProbeTest` | 1 | **absent** |

**2025 is the sum over the 323 live reports, and it excludes those three stale ones.** No `Scratch*.java`
and no `Diag*.java` exists under `core/src/test/java` at all. The two probe reports an earlier
version of this section named as stale (`DiagTest`, `ScratchLavaProbeTest`) are **gone from the
directory too**, so this section's own table was stale in exactly the way the anchors were: it
cited reports that had been cleared, and missed reports that were still sitting there. **The
rule, and the reason this is a section and not a sentence: join the report set against the source
tree before summing, and treat a report with no source as a deletion, not as a test.** "Clear the
directory" is not a rule anybody can follow after somebody else runs a probe. The join is the rule.

**The same bijection checked from the other end, and it is exact.** `core/src/test/java` holds
**346** `.java` files, **323** of them match surefire's default includes (§3), and there are
**323** live reports for them, one for one, with **zero** files lacking a report and **zero**
reports without a file. The earlier warning in this section — that
`ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` existed and had never been
executed — is **resolved**: it is collected, it has a report, and its five tests are green. Row
12 is no longer a design claim.

**The one skip is pre-existing and named.** `LevelSchemePathGuardPatchTest` reads `tests="10"
skipped="1"`, and the skipped method is
`aSymlinkOutOfSavesIsRefusedByContainmentButAcceptedByVanilla` — an assumption about what vanilla
1.8.9 does on Windows that the vendored source does not settle.

### 0.1 Where the count came from, and which of its legs are evidence

The total is measured. The path that got there is not, in more of its legs than it was, and the
difference is worth printing rather than rounding:

| step | delta | what backs the number |
|---|---|---|
| baseline before the collection gap was closed (1803) | — | historical; **not** re-derivable from the reports now on disk |
| the 7 renamed contract tests (§3.1) | **+19** | **measured**: `3+4+1+4+1+4+2`, each read off its own report, re-read this pass |
| `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` | **+3** | **measured**: its report says `tests="3"`, re-read this pass |
| belief-consumers' three classes | +14 | historical |
| wave 8 to the grading wave's baseline | +11 | historical |
| **the clock wave** (§1e rows 33-36) | **+21** | historical **as of this pass** — the four classes still exist and still read `6 + 4 + 5 + 6`, but the `1877` run they were measured against is no longer on disk |
| **the grading wave** (§1e rows 37-38) | **+6** | historical for the same reason. `ABeliefCensusOverTheActScenariosTest` still reads `7` and `ADigCompletionSaysWhatItActuallySawTest` still reads `6`, so both legs of this delta still hold — what is gone is the report that made the sum `1877` checkable |
| **wave-9 subtotal** | **+27** | 1850 + 21 + 6 = **1877** |
| **the damage wave** (§1f rows 39-41) | **+14** | **measured**: three new classes read `7 + 4 + 3` on their own reports |
| **the enclosure wave** (§1f rows 42-44) | **+18** | **measured**: four new classes read `5 + 5 + 4 + 4` on their own reports |
| **the corpus wave** (§1f rows 45-46) | **+31** | **measured**: four new classes read `8 + 8 + 7 + 8` on their own reports — an arming class and a real-vanilla-class class per patch |
| **the seam and the box wave** (§1d row 30, §1f rows 48-52) | **+43** | **partly measured**: the six new classes read `6 + 12 + 4 + 2 + 8 + 3` = **35** on their own reports, re-read this pass. The remaining **8** are **[UNVERIFIED]** — see below |
| **total, this pass** | **+85** | 1940 + 85 = **2025**, which is what the 323 live reports say |

**The `+85` is measured. Its decomposition is not, and the difference is the point.** What *is*
backed by a report that exists right now is the six new classes' own arithmetic:
`TheDawnChestRowHasAProducerTest` **6**, `ToolLayeringTest` **12**,
`ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest` **4**,
`TheHandshakeSentenceNamesNoKernelVerbTest` **2**,
`TheDawnChestPredicateHasBothVerdictsFromOneWorldTest` **8**,
`TheDawnChestCostsTheWalkNoWorldReadTest` **3** — **35** in total, each re-read this pass.
**The remaining 8 tests of the delta are not attributed to any class.** They are not reconstructed
here from memory, and the reason is this section's own standard: **a row labelled *measured* must
still name a report that exists**, and the reports that would carry the last 8 were cleared by a
later run, exactly as the clock and grading legs were. Reconstructing a leg because the arithmetic
demands one is how a decomposition becomes a claim about evidence that no longer exists. **The
delta is published; the decomposition is [UNVERIFIED].**

**The clock and grading waves are not `+16` and `+11`.** They are `+21` and `+6`, and the total
is the same either way, which is exactly why the split is worth getting right: a decomposition
whose parts can be redistributed without changing its sum is a decomposition nobody has checked.

**And this table lost two of its legs between one refresh and the next, which is the finding.**
It previously marked the clock and grading waves **measured** and pointed at their class reports
as the backing. Those reports are gone — cleared by a later run — so the marking had become a
claim about evidence that no longer exists while still reading as evidence. **Five** legs are
measured today and each can be pointed at a report; **five** are historical and are not laundered
into evidence by being in the same table. The correction is not that the old numbers were wrong.
It is that a row labelled *measured* must still name a report that exists.

**And the three stale reports are the cleanest possible argument for §6.9 and §6.17.** All three
belong to classes that were deleted and never had their reports cleared. One of them,
`ScratchLavaProbeTest`, was **6** tests in the version of this document that cited it and read
**3** in the next, so a reader checking that citation would have found a report disagreeing with
the number printed beside it. **Re-measure anyway.** This repository has pinned a test count in a
document and had it be wrong within one round, three times (§0.1 of `branch-topology.md` records
it for commit counts; this is the same failure with a different subject).

---

## 1. What the system now guarantees that it did not before

Each row: the claim, where it is implemented, the test class that fails on regression, and
how many test methods that class actually has — **read from that class's own surefire XML,
not counted by eye and not carried from a previous version of this file**. **UNENFORCED**
means no test in the repository fails if the guarantee stops holding.

**There is exactly one such row, and it is new: row 47** (§1f). It is a claim about what vendored
source does *not* permit — that `ResourcePackRepository`'s destination name is constrained before
any `File` is built, so a containment check there would fix nothing. Nothing fails if that regex
ever changes, and the row says so rather than borrowing a test that reads something else. The
previous unenforced row, 32, was closed by `scripts/test_sign_patch.py` and the story of how is
kept under the row.

**A row that cites a test must cite one that reads the thing the row is about.** That is the
specific error this file exists to stop, it has happened here twice, and it is the one error in
this document a reader cannot detect for themselves — a plausible class name beside a plausible
anchor looks exactly like evidence.

### The last column: distance from the north star

Every row below carries **距北极星** — one letter, `A` / `B` / `C` / `D`, and never an ordinal
(§6.15). The letters are assigned by **one mechanical test, not by judgement**:

> **Does this row's enforcing test drive production classes on a live client, or does it drive
> only `SimWorld` + `GoalPolicy`?**

The second half of that question is **not** about the north star, and §6.2 says why in one
sentence: *"The acceptance object is the **weak model's decision**."* A test that drives only
`SimWorld` + `GoalPolicy` proves **the controllers compose**. It does not prove a model will ever
produce that plan, and the plan is the thing the north star is about. **This is the same fact §4
states from the other side** — the eval *"drives a policy written in Java, and the acceptance
object is the weak model's decision"*.

| letter | meaning | the mechanical test |
|---|---|---|
| **A** | **directly decides the match** | the test drives a production class a running client constructs, and the guarantee is about a value the **client** computes or the **body** must survive |
| **B** | **raises the win rate** | the test drives a production class, but the guarantee is about *how well the model is told* rather than *whether the body lives* |
| **C** | **only on one path** | the test drives a production class, but the guarantee only bites on a specific branch — one verb, one refusal shape, one seam |
| **D** | **unrelated to a match** | the test drives only `SimWorld` + `GoalPolicy`, or only a fixture / a script / a registry table |

**A corollary worth stating, because it is the whole reason the column exists.** §1 was written so
that every row carries a test class and a `file:line`. That discipline makes a row a **receipt**
instead of a claim — and it is silent about the one question the north star asks. **A green
receipt for a `SimWorld` fixture and a green receipt for the one line the client's clock hangs on
are the same shape in this table**, and only one of them can keep a body alive. §6.12's rule
("do not cite a test that does not read the thing the row is about") asks whether the test reads
the row's subject. **This column asks a harder question: whether the subject is on the path from
a running client to a night survived.**

**The counts are in §1h, and they are counted off the column rather than asserted.**


### 1a. Trust and patching

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 1 | The compat layer reports **bytes actually changed**, not only "armed" | `compat/CompatEngine.java:472-479` counts `transformRuns` and `targetsChanged` on reference inequality; record `ApplyRecord` at `:73-78` | `CompatEngineAppliedObservableTest` | 7 | C |
| 2 | `list_compat_patches` publishes that count and classifies a patch as **inert** (ran, changed nothing) | `compat/CompatTools.java:99-140`; `targetsChanged` row at `:123`; inert at `:134` | same as row 1 | 7 | C |
| 3 | SEC-1 (`level://` path traversal) is armed **and** changes the real compiled vanilla class | `compat/patches/LevelSchemePathGuardPatch.java`; canonical-path containment test, documented at `:475` and called by the patched bytecode as `INVOKESTATIC` | `LevelSchemePathGuardPatchArmingTest` | 7 | C |
| 4 | The root private key is not silently overwritten — the refusal lives in the **write function**, not in `main()` | `compat/tools/RootCeremonyCli.java:257-262` | `RootCeremonyRefusesToClobberTheRootKeyTest` | 4 | D |
| 5 | The production `AccessGate` enforces L4 and L5 rather than no-oping | wired at `McpCore.java:339` (`new MonitorAccessGate(engine)`); body at `se/MonitorAccessGate.java:92-131` | `MonitorAccessGateIsLiveTest` | 14 | C |

Three notes on this table, because the shape matters more than the rows.

**Row 1 is the load-bearing one.** Before it, the only signal was `armed`, and
`GlClampToEdgePatch` matched an `LDC` where javac emits `SIPUSH` — so it armed, its
signature verified, and it returned `null` on every real load. The log said "N patch(es)
armed" and the log was telling the truth about the wrong thing. `targetsChanged` is what
separates "registered", "armed", "ran", and "changed something", and they are four
different states. Row 2's inert classification is the specific answer to the specific
failure: a patch with zero runs is not inert, it simply has not been reached yet.

**Row 5's fix moved a `MonitorAccessGate` into `McpCore`, and `AllowAllGate` still exists
with an empty `require()` body** (`se/AllowAllGate.java:17-19`). That is correct: it is the
dev-default, and **10** test files construct it deliberately so the thing under test is not
the gate. What is no longer true is the claim that the *production* wiring used it.
`MonitorAccessGateIsLiveTest` reaches the `MmAccess` instance `registerBuiltins` built and
asserts both terms of the AND separately (L5 capability, L4 privilege), plus that the
subject is re-read per call — which is what separates a live gate from one snapshotted at
wiring time.

**Row 4's placement is the whole fix.** The guard was in `main()`, so any new call site
bypassed it. It is now in `writeOwnerOnly`, the only function that writes the key.

### 1b. The act layer: decisions and grades

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 6 | The MOVE path propagates the belief grade to the record on **both** the terminal and the running path | `drivers/act/MoveApplier.java:388` (terminal), `:441` and `:459` (running) — five `withBelief(` call sites in `core/src/main`, three in `MoveApplier` and two in `InteractApplier` (`:58`, `:75`) | `ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest` | 6 | B |
| 7 | "Nobody looked" and "somebody looked and could not see" are **different values** at the record boundary | `drivers/act/SlotRecord.java:89` and `drivers/act/ActOutcome.java:147` — both `UNGRADED` are now `null`, with `mayActOn()` as the door | same as row 6 | 6 | B |
| 8 | A route refusal that counted unread cells is not the same failure as one over impassable terrain, **on the record** | `drivers/plan/RoutePlanning.java:202-216` (`noRouteMessage`, graded on `unreadCells` at `:210-215`); the grade and the count both leave on the `ActOutcome` at `:234-237` | row 6's test is the seam test; the type-level half is `ARouteRefusalOnUnreadTerrainIsNotTheSameFailureAsOnImpassableTerrainTest` | 6 + 8 | B |
| 9 | The dig's completion says whether this client **saw it, inferred it, or could not look** | `drivers/act/DigController.java` (`withoutUpgrade` grades a stale claim rather than restating it) | `ADigCompletionSaysWhatItActuallySawTest` | 6 | B |
| 10 | Grading the crosshair does not cost a world read | `ActActuator.mouseOver()` now returns `Graded<Target>` (`drivers/act/ActActuator.java:64`; the only production implementation is `LivePlayerActuator.java:75`) | `ABeliefOnADigCostsNoWorldReadTest`, `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 3 + 4 | D |
| 11 | The crosshair's javadoc no longer claims "never null" for a value traced against the previous frame | the same `ActActuator.java:41-63` javadoc, which now states the staleness and names the two writers of `mc.objectMouseOver` | `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 4 | D |
| 12 | A model's `act_status` row carries what the walk **decided** (`tactic.givenUp`) and how well-earned the message is (`belief`) | `drivers/act/ActStatus.java:47-57` (`SlotStatus`, now ten components, gains `MoveTactic tactic`); projected at `drivers/act/ActRuntime.java:648-667` (`:659` tactic, `:667` count); emitted at `drivers/action/ActTools.java:1003-1016`; named in the description at `:909` and `:959-977` | `ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` — **collected and run**: report present, `tests="5"`, 0 failures | 5 | B |
| 13 | A route's terminal record names what the walk actually spent, not what a controller computed | `MoveApplier.stamp` at `MoveApplier.java:487-493` writes onto the `RouteIntent` via `route.withTactic(chosen)` at `:492`; `ActRuntime.java:659` reads it back | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest` | 4 | B |
| 14 | A route that falls on its **first** tick still publishes a tactic | same stamp; the fall-guard branch of `MoveApplier.apply` | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest.aRouteThatFallsBeforeItsMachineHasRunStillPublishesATactic` | (within the 4) | C |
| 15 | A walk that spent nothing says so on the record it carries | `AWalkThatSpentNothingSaysSoOnTheRecordItCarriesTest` reads `((RouteIntent) rec.intent()).tactic()` | same class | 3 | B |
| 16 | The terminal tactic distinguishes "the clock ran out" from "every recovery failed" | `MoveTactic.GivenUp` (`drivers/act/MoveTactic.java:230`; `LIMITS_REACHED` and the `OUT_OF_TICKS_AFTER_A_LANE` split are separate constants) | `TheTerminalTacticMustSayWhatTheWalkSpentToGetThereTest`, `AWalkThatRanOutIsNotAWalkThatSpentEveryOptionTest` | 4 + 4 | B |
| 17 | `ARRIVED_AFTER_A_LANE` is reachable on a production record | `NavController.arrivalGivenUp()` at `drivers/act/NavController.java:810`, reached from the arrival branch at `:588` (`stop(arrivalGivenUp())`) | `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` | 3 | C |
| 18 | Re-stamping a walk every tick does not free the channel it is standing on | `MoveApplier.stamp` allocates a fresh `RouteIntent` per tick via `withTactic`, so `ActRuntime.sameIntent` is goal-keyed, not identity-keyed | `AReStampedIntentMustNotFreeTheChannelItIsStandingOnTest`, `AReStampedWalkIsStillOursToThePlanTest` | 1 + 2 | C |
| 19 | A plan's supersession check reads the **lease**, not object identity, and is ANDed with the goal test at all three sites | `ActPlanInterpreter.takenByAnother` at `drivers/act/ActPlanInterpreter.java:244-247`; call sites `:111`, `:217`, `:269` | `APlanTeardownMustNotCancelTheRacingActSetTest`, `TwoConsumersOfOneSlotCannotSilentlyOverwriteTest` | 5 + 14 | C |
| 20 | A wedged body sidesteps or **names what is in the way** | `NavController.JAM_REACH = 0.2D` at `:88`; the ranking that keeps a measurement above a guess | `AWedgedBodySidestepsOrSaysWhatIsInTheWayTest`, `AFlushJamIsNamedAtEveryCellOffsetTest` | 5 + 5 | B |

**Row 6 and row 7 are one fix, and row 7 is the half that is easy to get wrong.** Before,
`SlotRecord.UNGRADED` and `ActOutcome.UNGRADED` were *the same constant*, both
`Belief.UNKNOWN` — so a walk whose controller had graded nothing published exactly the value
a refusal over unread terrain earned. Moving `MoveApplier`'s two missing `withBelief` calls
alone would have left the conflation in place and merely made it rarer. Both are `null` now,
and `mayActOn()` exists because a `null != UNKNOWN` comparison **fails open**: a caller
written that way would find every ungraded line actionable. That is why the accessor is the
recommended door and a bare comparison is not.

**Row 6's test exists because the existing belief test could not catch this defect.**
`ARouteRefusalOnUnreadTerrain...` reads `out.belief()` off the `ActOutcome`, which is where
the grade is born. It is green, and it is right, and it would have stayed green through the
whole defect — because it never reads the far side of the seam. Both arms of row 6's test
matter: `unreadCells > 0` must reach the record as `UNKNOWN` and `unreadCells == 0` as
`OBSERVED`, and **no constant satisfies both on the unfixed tree**.

**Row 12 used to be the only row in this table that was written but unrun, and it no longer
is.** It is worth keeping the shape of that, because "the test exists" was the exact mistake
§3 is about: the file was on disk, its five `@Test` methods were readable, and its sibling rows
were green, and none of that is the same as a suite having asked it a question. It is collected
now and its report says `tests="5"`, 0 failures (§0). What row 12 does **not** have is a check
that the *value* on the wire is the right one: `ActToolsTest.everyFieldActStatusEmitsIsNamedInItsDescription`
(`ActToolsTest.java:426-440`) derives the key list from the emitted map, so it fails if a key is
undocumented, and it says nothing about whether the value behind a documented key is correct.
That half is what the falsifier in §2.4 is for, and it has still not been run.

**Row 17 is a dispute that was resolved by measurement, not by argument.** Two audits
disagreed: one argued `ARRIVED_AFTER_A_LANE` was unreachable (142 completed side-steps, zero
arrivals across 2840 worlds), the other refuted it with a deterministic three-tick trace.
The **deletion did not proceed, which was the right call** — a deletion must not rest on a
geometric argument that has already been measured wrong once. The burden is on whoever
deletes it to re-run the counterexample.

**`tactic` is null for every non-`go_to` slot, including `walk_straight`, and that is
honest rather than missing.** `MoveApplier.stamp` stamps only a `RouteIntent`
(`MoveApplier.java:491-492`): a `NavIntent` has no field to stamp into and a raw
`MoveIntent` has no tactic at all. Only a route has somewhere to record a decision.

### 1c. The tool surface

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 21 | The DROP verb exists end to end: schema, intent, controller, **re-read confirmation** | schema enum `drivers/action/ActTools.java:302`; `InteractIntent.Kind.DROP` and `dropStack` at `drivers/act/InteractIntent.java:107,242`; `drivers/act/DropController.java:36` | `DropControllerTest`, `DropReachabilityTest`, `InteractApplierDropRoutingTest`, `ADropIsReachableThroughTheRealToolBoundaryTest` | 11 + 7 + 4 + 6 | B |
| 22 | The drop confirmation is "the slot is EMPTY", not "the click returned" | `DropController.isDone()` at `:62` | same as row 21 | (within the above) | B |
| 23 | A misspelled `sections` entry is an **error naming the offender**, not a silent omission | `drivers/world/WorldViewCapture.unknownSections` at `:61-72`, consulted at the tool boundary | `AnUnknownSectionNameIsRefusedTest` | 4 | B |
| 24 | Every key `act_status` emits is named in its description, and the vocabulary is **derived from the emitted map** | description at `ActTools.java:909` and `:959-977`; the gate at `ActToolsTest.java:426-440` | `ActToolsTest.everyFieldActStatusEmitsIsNamedInItsDescription` | (within 34) | B |
| 25 | Every interact/look argument is both **read by the parser** and **named in the description**, in both directions | `ActToolsTest.java:544` (interact), `:578` (look) | same class | (within 34) | A |
| 26 | A tool description naming a tool that does not exist is a build failure | registry walk in `io/transport/DescriptionsNameToolsThatExistTest` | `DescriptionsNameToolsThatExistTest` | 8 | D |
| 27 | A tool description's legend matches the values the handler can actually send | per-tool checks in `io/transport/ToolDescriptionsMatchTheirBoundsTest` | `ToolDescriptionsMatchTheirBoundsTest` | 7 | B |
| 28 | A hazard is readable **before** the walk arrives, and survives on the terminal tick | `MoveApplier.java:386` (terminal), `:440`, `:459` (running) | `AHazardIsReadableBeforeTheWalkArrivesTest`, `TheEarlyHazardWarningReachesActStatusTest` | 9 + 6 | A |
| 29 | A hazard is never guessed from a cell that could not be read, so an unloaded chunk reports **no hazard** rather than a bottomless pit | the read-jam ranking in `NavController.readJam`; described at `ActTools.java:957-958` | `AHazardIsReadableBeforeTheWalkArrivesTest` | 9 | A |
| 54 | A night's **shelter** is readable from the live client, and a payload that has never measured anything says so instead of reading as safe | `eval/NightShelter` — a `measuredOnce` latch plus an internal `ticksDelivered` that separates "the seam never delivered a tick" (dead wiring) from "it is delivering ticks and the client has no world" (title screen); `drivers/observe/ShelterTools` publishes both | `TheShelterPayloadSaysWhetherItHasEverMeasuredTest` | 8 | **A** |
| 55 | The **health floor** for a night is a **min**, and a hit the invulnerability guard refused is visible to this ruler **and not to the bar** — which is why three series ship independently rather than one verdict | `eval/NightHealth` with `eval/ClientVitals`; `minHealth`, `healthDrops`+`hitEdges` and `maxLastDamage` each in the payload, `absorbedHits` their difference; `drivers/observe/NightRulerTools` registers `night_health` | `ANightHealthRulerReadsThreeSeriesAndCanSayNoTest`; reachability via `TheRulersAreReachableFromTheProductionKernelTest` | 12 + 10 | **A** |
| 56 | A **chest that stood all night** is judged per **region**, not per a cell the agent must designate — and a cell that could not be read is counted apart from one that was removed | `eval/DawnChestRegion` with `eval/ClientArea`; `unreadableSamples` and `missingSamples` are separate counters, and `fact()` names which one fired | `ADawnChestRegionRulerFindsItsOwnCellsAndCanSayNoTest` | 15 | **A** |

**Rows 54-56 are the three things a night is judged by, and they were all `D` until 2026-10-03.**
**Two of them did not exist before that date** — `night_health` and `night_box` landed the same
day this column did, which is why the column classified them by where their *tests* lived
(`SurvivalDamage` has its only construction in the test tree's `SimWorld`) rather than by what
they are for. **The classification was correct about the code and wrong about the night.**

**Row 55's reason for three series rather than one verdict is the shape worth stealing.**
`minHealth` sees "really went low"; `maxLastDamage` sees "was hit and the guard refused it".
Either alone is a stand-in for the other: the bar alone cannot see the refused hit, and a hit
counter alone cannot tell a 1-point dip from a 5-point one. **A single `floorHeld` boolean would
have read as a complete answer while silently dropping one of two ways to fail the night.**

**Row 56 trades strength for honesty.** It judges "**a** chest stood all night", not "the chest
**you** built did" — because requiring the agent to designate a cell is a requirement the
current tool surface cannot meet, and a judge that waits for a designation reports nothing. The
`fact()` string and the tool description both say so in those words.

**Row 24's mechanism is the part worth stealing.** The list of keys is read from the map the
handler actually emitted, not hand-written. A hand-written list is the empty-assertion shape
this repository has now caught in itself more than once.

**Row 23's failure mode was worse than a missing section.** In diff mode a silently dropped
section is indistinguishable from one that did not change: the caller is told its kit is
untouched because it asked about the wrong field.

**Row 21's confirmation is deliberately the expensive direction.** A drop is irreversible in
this substrate — see §4 — so a re-read proves more than a packet-sent flag would, and the
refusal is at the boundary rather than a clamp.

### 1d. The measurement substrate

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 30 | A full bag makes a room move before it refuses to dig, and the protection is a **ledger** not a guess | `eval/GoalPolicy.chooseSlotToThrow` / `makeRoom` — still a **test-tree** class and still `public final`, but reachable through the **main**-tree `Policy` seam (row 53) | `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest` | 5 | D |
| 31 | An eval filter matching zero tasks is an **error**, not an empty PASS | `eval/SelfPlayEval.runAll(List)` at `SelfPlayEval.java:38`, the refusal at `:59-60` | `AnEvalFilterThatMatchesNothingIsNotASuccessTest.aFilterThatMatchesNoTaskIsRefusedRatherThanReportedAsSuccess` | 2 | D |
| 32 | The signing script derives the classpath separator **from the JVM that will consume it**, and strips the CR off the answer | `scripts/sign-patch.sh:77-79` (ask `-XshowSettings:properties`, `tr -d '\r'`, `sed` out `path.separator`), `:91` (the join) | `TheSigningClasspathIsBuiltFromTheJvmTest`, in `scripts/test_sign_patch.py` | 6 | D |
| 53 | A deliberately broken policy can be **run against the real suite**, and the seam that makes it possible is **in the shipped tree** | `eval/Policy` at `core/src/main/java/net/marcloud/mcp/core/eval/Policy.java:58`; `PolicyRun`'s `Binder` at `PolicyRun.java:44`; the deliberately-wrong implementations in `BrokenPolicies.java`; `GoalPolicy` (still `public final`, still test-tree) reaches the suite *through* `Policy` | `TheSuiteGoesRedThroughTheSeamTest` — **8 tests**, including `theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree` (its javadoc records why the method was renamed out from under three references in this file: a line number into a file being edited is a half-life, §6.14) | 8 | D |

**Row 30's ledger is the thing standing between the agent and a false pass: with
`keep.addAll(owedItems())` removed, `got` flips from false to true** — the policy reaches the
goal by eating its own planks. That mutation is the evidence the protection is real.

**And the second half of this row inverted, so it is worth printing what it used to say.** It
previously read, at length, that `GoalPolicy` is test-only, has zero references from
`core/src/main`, cannot be substituted, and therefore: *"There is **no seam**, so a deliberately
broken policy cannot be run and the suite has **no negative control of its own** ... so **this
hole is still open.**"* Every clause of that is now **false, in the strong direction**
(row 53). The seam exists and it is **in the main tree**: `core/src/main/java/net/marcloud/mcp/core/eval/`
holds `Policy.java` (interface at `:58`), `PolicyRun.java` (`Binder` at `:44`) and
`BrokenPolicies.java`. `TheSuiteGoesRedThroughTheSeamTest.theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree`
(`:247`) asserts the three classes were **LOADED from** `core/target/classes` — reading
`getProtectionDomain().getCodeSource().getLocation()`, **not** `getPackageName()`.
**That criterion was corrected on 2026-10-03**: a class moved from `main` to `test` keeps every
character of its package name, so the package check was structurally immune to the one event this
row exists to catch. **A refactor can no longer quietly move the seam back into the test tree,
and neither can a plain file move.**

**How the fix was made is the argument, and it belongs in the row rather than in a changelog.**
`Policy.java:39-43` states that a seam defined beside `GoalPolicy` in the test tree *"would
reproduce in this slice the exact failure the slice exists to close -- a capability whose producer
only tests can reach."* **The seam was shipped into `main` specifically so the closure could not be
undone by a test refactor.** That is `failure-shapes.md`'s shape applied to this row's own hole:
a capability reachable only from tests is a capability nothing in production can reach, and a
guarantee that vanishes when somebody tidies the test tree was never guaranteed.

**What this document had wrong about itself, and it is the same shape twice.** §1f row 40 already
credited `TheSurvivalRowDetectsAPlantedHazardTest` as *"the project's first negative control"*
while this paragraph insisted none existed. **Two sections of one file contradicting each other
about a fact is a defect in the file, not a disagreement between the sections**, and the rule that
would have caught it is **§6.16**: *"open" is not a stable verb*, and a claim of absence is the
hardest kind of rot to notice because the fix is invisible from the document and the sentence still
reads fluently. The `alive` conjunct was a second, separate hole; it is closed (§1f row 39) and §4
carries the rest.

**Row 32 was UNENFORCED when this row was written, and the story of how it became enforced is
the reason it is still here.** The fix was real and it was in the script: `sign-patch.sh` asks
the JVM for `File.pathSeparator` instead of guessing, which is what made the signing step work
on Windows — `mvn dependency:build-classpath` already writes the platform separator into its
cache file, so joining its entries with `:` produced a classpath the JDK rejected with sixteen
"package does not exist" errors, on Windows only, and only at signing time. **No test in the
repository read `sign-patch.sh`, so reverting the fix left every suite green.** That gap was
found by the worker writing *this document*, while producing the **tenth instance of this
session's own failure shape** inside the document whose purpose is to stop it.

**`BuildScriptContractTest` must never be cited for this row, and the reason is the general
one.** It is the class a search for "what tests the scripts?" points at, and it reads
`src/main/native/core-jvmti/build-clang.sh` (`BuildScriptContractTest.java:31`) — a different
script in a different directory — with one `@Test` (`:41`) about `JBRINC` (`:47-53`). **A test
class's name is not evidence of what it reads.** The row was wrong that way once, in draft,
and the correction is the only durable form of it.

**The cost has been paid.** `scripts/test_sign_patch.py` now exists: class
`TheSigningClasspathIsBuiltFromTheJvmTest`, **6 `@Test` methods**, mutation-verified in **both**
directions — reverting the first classpath join to a hardcoded `:` gives **2** failures, and
removing the `tr -d '\r'` gives **3**. Run it with `python scripts/test_sign_patch.py`; it needs
no JDK and no built module, so it is the cheapest green in the repository.

### 1e. The clock, and the grades that name their derivation

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 33 | The client's "is it night" answer is **derived from the clock**, not read off a field that stopped moving | `drivers/world/WorldViewCapture.java:502` — `daytime = Daylight.isDaytime(time)`, the one line the defect lived on; the live accessor is `drivers/act/LivePlayerActuator.java:243`, declared at `drivers/act/ActActuator.java:360` | `TheProductionDayNightReadIsNotFrozenTest` (drives `WorldViewCapture.env` itself by reflection, on a world built to be exactly as wrong as a real client) + `TheClockCostsTheWalkNoWorldReadTest` | 4 + 6 | A |
| 34 | The night boundaries are **vanilla's own**, asserted across all 24,000 ticks | `drivers/world/Daylight.java:98` (`skylightSubtracted`), `:113` (`isDaytime`, delegating to `EnvironmentWeather.isDaytime(int)` so the threshold is restated nowhere); transcribed from `World.calculateSkylightSubtracted` at `World.java:1403-1413` | `DaylightMatchesTheVanillaCurveTest` | 6 | A |
| 35 | The clock helper is **structurally incapable** of becoming a second frozen read — proven from bytecode, not from reading | `Daylight`'s five public methods at `:71`, `:98`, `:113`, `:118`, `:123` take a `long` and return arithmetic; there is no `World` parameter, no field, and no seam | `TheClockCostsTheWalkNoWorldReadTest.theDaylightHelperContainsNoWorldCallAtAll` (zero calls into `net/minecraft/world/` or `net/minecraft/entity/`, zero to `World.isDaytime` or `getSkylightSubtracted`) and `.theLiveActuatorDoesNotDelegateToTheFrozenVanillaRead` (exactly one call to `Daylight.isDaytime`, zero to `World.isDaytime`) | (within the 6) | A |
| 36 | A sunset that does not cross the hour bucket is still **visible** in a diff | `drivers/world/WorldViewDiff.envDiff` gains `worldTime` and `daytime`; the bucket is 1,000 ticks wide and vanilla's night begins at 13,807, so dusk at `:13807` and dawn at `:22193` both sit **inside** a bucket | `TheDiffCarriesTheClockTest` — asserts the two ticks share a bucket as a *premise*, so the test fails loudly rather than passing for the wrong reason | 5 | B |
| 37 | Every `ActOutcome` factory site in the act and plan drivers **names its derivation**, and the site is the claim | five named constants in `drivers/act/ActOutcome.java`: `:67` `READ_DIRECTLY = Belief.OBSERVED`, `:77` `DERIVED_FROM_READS = Belief.INFERRED`, `:89` `PLANNED_ON_CLIENT_WORLD`, `:100` `REUSED_CELL`, `:112` `READ_CAME_BACK_EMPTY = Belief.UNKNOWN`; the permitted set is `GRADED_DERIVATIONS` at `:115-118` | `ABeliefCensusOverTheActScenariosTest.everyOutcomeSiteInTheActLayerStatesItsDerivation` — fails on one more **or one fewer** site | 7 | B |
| 38 | The unread count rides **beside** the grade, never inside it, and absent is not zero | `ActOutcome.java:37` (the `Integer unreadCells` component) beside `belief`; recorded on `SlotRecord.java:46` with `NO_COUNT = null` at `:149`; carried through **three** `MoveApplier` stamps at `:393`, `:442`, `:459` (and two in `InteractApplier` at `:59`, `:76`); on the wire at `ActTools.java:1015` | `ABeliefCensusOverTheActScenariosTest` — asserts `unreadCells == 412` beside `UNKNOWN` and `unreadCells == 0` beside `OBSERVED` (`:422-437`) | 7 | B |

**Row 33's defect was worse than a stale value, and the severity is the whole reason this
row exists.** `World.isDaytime()` is `this.skylightSubtracted < 4` (`World.java:866-869`), and
`skylightSubtracted` has exactly two writers in the whole vendored tree. On a **client**, only
one of them ever runs:

1. `WorldInfo.populateFromWorldSettings` (`WorldInfo.java:253-262`) assigns **seven** fields —
   `randomSeed`, `theGameType`, `mapFeaturesEnabled`, `hardcore`, `terrainType`,
   `generatorOptions`, `allowCommands` — and `worldTime` is **not among them**. It is a bare
   `private long worldTime;` (`:35`), so it keeps its default of `0`.
2. `calculateInitialSkylight` reads the clock once, in the `WorldClient` constructor, at
   `worldTime == 0`. Vanilla's own curve is `0` there, so the field is set to `0`.
3. `WorldClient.tick` (`WorldClient.java:71-74`) advances `worldTime` under `doDaylightCycle`
   and **never recomputes the skylight**. The only clock-path caller of the setter is
   `WorldServer.tick` — a **server** method.

**So the answer was not wrong at one hour of the day. It was wrong at every hour of the day:**
`isDaytime()` returned `TRUE` for the entire life of a client world, **including at midnight**,
where vanilla's own curve says `11` of `15` subtracted. The one production read of "is it
night" said no, on every tick, for the whole session. That is a CRITICAL against the north
star, because the prompt a weak model gets is assembled from facts the client reports.

**One thing that looks like a third writer and is not.** `EntityMob.java:162,164` appears in
any grep for `setSkylight` — it calls `setSkylightSubtracted(10)` and then
`setSkylightSubtracted(j)`. That is a **save/restore probe** around a thunder-lit light read,
not a clock path: `World.tick()` does not call it and `WorldClient.tick()` does not either. It
is named here because the grep a reader will actually run finds it, and because "two writers"
is a claim somebody has to check.

**Row 34's numbers were wrong in the design pass, in both directions, and the literals will be
copied.** Measured against the real `World.calculateSkylightSubtracted` over all 24,000 ticks:

| | design pass | **measured** | delta |
|---|---|---|---|
| dusk — first tick vanilla calls night | 13806 | **13807** | one tick LATER |
| dawn — first tick vanilla calls day again | 22194 | **22193** | one tick EARLIER |
| noon, sunrise | 6000, 0 | **6000, 0** | — |

Night is **8386** ticks long (`22193 - 13807`), asserted as an exact equality
(`DaylightMatchesTheVanillaCurveTest:263`), so a curve that moved fails on the count and not
only on a boundary. **The reason the transcription is off-by-zero is that `MathHelper.cos` is a
65536-entry lookup table** (`MathHelper.java:38-41`), not a function. `Daylight` keeps
`MathHelper.cos` and the class javadoc says so, because substituting `Math.cos` is the kind of
simplification that looks like a cleanup and moves dusk by a tick.

**Row 37 is graded at the claim, not at the read — deliberately, and it is the interesting
decision in the wave.** A grade says how *the sentence* was obtained, so `NavController`'s
arrival line is where `onGround` is graded, not `LivePlayerActuator` where `onGround` is read.
Grading at the read would have been both a larger diff across a live sibling's files and the
wrong place to put the claim.

**Row 38 is beside the grade because a grade and a count are two unrelated sentences.** `Graded`
says *how a value was obtained*; "412 cells were unread" is a *fact about the world the claim
was about*. Putting the count inside `Graded` would have made one type say two things at once,
which is how the parallel structures this layer forbids come back. It rides the way `NavHazard`
rides — and `null` is a distinct value from `0` at every hop
(`RoutePlanning.NO_SEARCH_RAN = null` at `RoutePlanning.java:181`, `SlotRecord.NO_COUNT = null`
at `:149`), because `0` would claim a search ran and found nothing unread, which is a stronger
claim about the world than any site made.

**The census is the acceptance criterion, not the count of sites graded.**
`ABeliefCensusOverTheActScenariosTest` has 7 tests and it drives the **whole act scenario
set**, because a graded site the census never visits is the dead-value shape in a new costume —
a field nobody reads, typed. Its most useful result was a negative one: **the eight walk
scenarios produce exactly one grade, `INFERRED`, and that is the correct answer**, because every
walking sentence is arithmetic over the player's own position copy. A wave that had graded them
`OBSERVED` because the tests were green would have shipped a column with one word in it. The
spread comes from the sites that are not a walk: a body wedged against something unnameable is
`UNKNOWN`, a route refusal over unread terrain is `UNKNOWN` + `unreadCells: 412`, one over fully
read terrain is `OBSERVED` + `unreadCells: 0`, a dig completion into air is `UNKNOWN`, and the
controller-state lines (`already finished`, `not in world`) are `OBSERVED`.

**Not reached by any scenario, and stated rather than hidden:** `LookController`'s unresolved-target
`UNKNOWN` and `InteractController`'s entity-gone `UNKNOWN`. Both are graded; neither is exercised
by this scenario set, and both are reachable from their own controller tests. That is a list,
not a gap.

**And `UNGRADED` is not a fourth `Belief`.** `Belief` (`core/util/Belief.java:50`) has exactly
three values — `OBSERVED` at `:60`, `INFERRED` at `:70`, `UNKNOWN` at `:80`. `UNGRADED` is the
**null default** on `SlotRecord` (`:89`) and `ActOutcome` (`:147`), and `mayActOn()` is its only
safe reader, because a bare `null != UNKNOWN` comparison **fails open**: every ungraded line
would read as actionable.

### 1f. Damage, enclosure, the corpus, the seam, and the box row: the slices after the last refresh

| # | guarantee | implemented at | enforced by | tests | 距北极星 |
|---|---|---|---|---|---|
| 39 | The eval's survival row reads the **low-water mark of a bar the world moved**, not the end of a fixture field | `EvalSuite.survived` at `eval/EvalSuite.java:155-157` returns `w.minimumHealth() > 0.0D && w.alive()`; `EvalSuite.neverBelowNorthStar` at `:167-169` is the 18-floor claim; `NORTH_STAR_HEALTH` at `:136` is re-exported from `SurvivalDamage.NORTH_STAR_FLOOR` rather than repeated; the producer is the class `eval/SurvivalDamage` in the **main** tree, and the accessor is `SimWorld.minimumHealth()` at `SimWorld.java:703` | `TheSurvivalRowDetectsAPlantedHazardTest` | 4 | D |
| 40 | The eval **can fail**, and the proof is a planted hazard rather than a demonstration | `EvalSuite.T20TheDirectLineIsLava` (`EvalSuite.java:1681`) grew a `plantedHazardRun()` seam at `:1823`, **inside T20**, so the control reuses T20's own five pass rows (`:1834-1840`) instead of copying them; the control calls it at `TheSurvivalRowDetectsAPlantedHazardTest.java:166`, and that method runs the rows through T20's own `runWith` policy seam (`EvalSuite.java:1724`, the shipped policy entering it at `:1709`) | same class | 4 | D |
| 41 | The damage arithmetic is vanilla's, and the authority is **not** the block class | `drivers/world/FireDamage.java:61` — `LAVA_CONTACT_DAMAGE = 8`, correct because the whole class is in half-hearts; vanilla's `Entity.setOnFireFromLava` (`Entity.java:539-545`) deals `DamageSource.lava, 4.0F` at `:543` and then `setFire(15)` at `:544` | `ALavaBandThatKillsThePlayerIsVanillasArithmeticTest` — asserts the sequence tick by tick, not at an end state | 7 | D |
| 42 | "Enclosed" is **two halves reported separately**, so a failure can say which one failed | `eval/Enclosure.java:159` — `public record Verdict(boolean skyClosed, int wallsMissing, int wallsTotal)`, not a boolean; `sideClosed()` at `:162-164`, `enclosed()` at `:167-169`, `describe()` at `:172-175` | `TheEnclosurePredicateHasBothVerdictsFromOneWorldTest` | 5 | B |
| 43 | The shelter claim is over a **window**, and a window that was never measured is refused rather than reported as held | `NightEnclosure.sheltered()` — `measured() && openSamples == 0`; the window's ticks are derived from the production clock at `NightEnclosure.duskTick()`. **Symbol-cited rather than line-cited (§6.14): this is a file the current wave edited, and the line numbers it carried had already moved** | `AShelterIsARegionOverTheNightAndNotARoofAtDawnTest` | 5 | B |
| 44 | Shelter and health floor are **joined into one value**, and each half is measured independently of the other | `EvalSuite.NightClaim` at `EvalSuite.java:202-204`; `nightClaim(w, enclosure)` at `:212-217` reads both and defaults neither; `T25ShelterThroughTheNight` registered at `:116`, class at `:2494` | `TheNightShelterAndTheHealthFloorAreJoinedTest` | 4 | B |
| 45 | SEC-2 changes the real compiled vanilla class and the engine says so | `compat/Compat.java:96` registers it; the defect is `ResourcePackRepository.deleteOldServerResourcesPacks` (`ResourcePackRepository.java:254-268`) calling `FileUtils.listFiles` on a directory nothing created, unguarded at `:256` | `ServerResourcePackDirGuardPatchArmingTest`, `ServerResourcePackDirGuardPatchMeetsTheRealVanillaClassTest` | 8 + 7 | C |
| 46 | SEC-3 guards both null-dereference entry points and the engine says so | `compat/Compat.java:101`; `Scoreboard.java:218` and `Scoreboard.java:296` | `ScoreboardNullGuardPatchArmingTest`, `ScoreboardNullGuardPatchMeetsTheRealVanillaClassTest` | 8 + 8 | C |
| 47 | **A corrected claim.** The top-ranked corpus defect is an **availability** failure, not a path traversal — and a containment check there would have fixed nothing | `ResourcePackRepository.java:179-186` constrains the destination name to `hash.matches("^[a-f0-9]{40}$")` or the literal `"legacy"` **before any `File` is constructed** at `:188` | **UNENFORCED** — nothing in the repository fails if that regex ever changes | — | D |
| 48 | **The fourth north-star criterion has a producer**, and the producer is the agent: the world starts with no chest and the eval task has to build one | `EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn`, registered at `EvalSuite.java:117`, class at `:2766`, `ID` at `:2773`; the predicate is `DawnChest.standingAtDawn()` at `DawnChest.java:208-210` and `heldThroughout()` at `:219-221`; the window's boundaries come from the production clock at `NightEnclosure.duskTick()` (**symbol, per §6.14** — the file is under active change) | `TheDawnChestRowHasAProducerTest` | 6 | D |
| 49 | The tool surface is **layered and denies by default**: an undeclared name comes back kernel-layered, and `create_tool` is kernel-layered because a name filter blocks nothing when the verb can compile arbitrary Java into the game JVM | `ToolRegistry.LAYERS` at `ToolRegistry.java:91`, built by `buildLayers()` at `:93`; the `Layer` enum at `:73-77`; `layerOf` at `:218-221` returns `KERNEL` for a name the table has never heard of; `isDeclaredKernelLayered` at `:239-241`; `create_tool` declared at `:101`, with the reason in its own words at `:97-100`; `eval_java` at `:131` | `ToolLayeringTest` | 12 | C |
| 50 | A name the model may not choose is refused, and the refusal is checked against the **audited** registry rather than the model-facing one | `MetaTools.isReserved` at `MetaTools.java:395-398` -- `audited.isBuiltin(name)` **or** `ToolRegistry.isDeclaredKernelLayered(name)`; the `audited` field at `:64`, threaded separately from `surface` at `:58`; the call site at `:251`; the refusal text at `:252` | `ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest` | 4 | C |
| 51 | The box predicate costs the walk **no world reads** | the claim at `DawnChest.java:101-104`, and the A/B is by difference with a "damage actually happened" second half, so zero measured against a walk that never looks is excluded | `TheDawnChestCostsTheWalkNoWorldReadTest` | 3 | D |
| 52 | The box predicate reports **both verdicts from one world** -- `standingAtDawn()` and `heldThroughout()`, not one boolean | `DawnChest.java:208-210` and `:219-221` | `TheDawnChestPredicateHasBothVerdictsFromOneWorldTest` | 8 | D |

**Rows 39 and 40 exist because of the defect they replace, and the defect was this document's own
subject matter.** Ten `w.health() > 0.0D` comparisons stood in `EvalSuite` — seven plus one
conjoined, and **two more inside T24's two legs that the task brief did not name**. Every one of
them evaluated `20.0 > 0.0`, because nothing in an eval loop could write the field: `health` was
set by the fixture's own `atHealth`, raised by a food heal, and otherwise untouched. **There was
no world in which any eval task could fail on its survival row**, which means there was no world in
which it passed either. `SimWorld.health` is gone; the ledger replaced it.

**All ten are now zero.** `grep -n "health() > 0.0D" EvalSuite.java` returns comments only, and
the one conjoined site (formerly `:947`) is `EvalSuite.java:1103` —
`survived(w) && w.minimumHealth() == 3.0D`, where the `== 3.0` half is now the low point over a
700-tick walk rather than the value `atHealth(3.0D)` set four lines above.

**Row 40 is a design decision and not a convenience, and it is the part worth stealing.** The
control plants **T20's own hazard** by removing the one thing that makes the task easy —
`GoalPolicy`'s route around the lava band — and walking the straight line instead, then drives it
through T20's own `plantedHazardRun()` seam rather than re-deriving the verdict. **A control with
its own copy of the pass condition is a second suite, and the two drift.**

**One half of the fixture *is* duplicated, on purpose, and the distinction is worth keeping.**
The control's `bandBetweenSpawnAndOre()` writes T20's geometry out again rather than sharing a
builder, and says so in its own javadoc, because the claim it makes is about *that* world's
geometry and a reader should be able to check the cells against the band the numbers describe.
**The pass rows are shared; the geometry is written out. Those are two different decisions and
only the first one is about avoiding drift.**

**Row 40's mutation numbers, as reported by the slice that ran them.** Deleting the lava contact
from `SurvivalDamage.step` gives **9** failures; making lava decorative with
`if (false && hazards.inLava() && !dead)` gives **9**; routing the hazard probe through
`ActActuator.blockAt` instead of the world's own grid gives **2**. The second is the interesting
one: it fails with *"the safe run ended at 20.0 and the planted run at 20.0"*, which is exactly
the world T20 previously could not be told apart from.

**Row 41 records an asymmetry so the next person does not spend the hour.** `BlockLava.java` is
**absent from the vendored block tree** — `client/src/main/java/net/minecraft/block/` holds 155
top-level block classes (158 entries, counting the `material`, `properties` and `state`
subpackages), and **no `*Lava*` block exists anywhere in the vendored client.**
The authority for lava contact damage is therefore `Entity.setOnFireFromLava`, not a block
class, and an integrator briefed to expect a block will look for it, not find it, and conclude
the number is unverifiable. **And the javadoc above the constant leaves out the guard**:
`setOnFireFromLava` is wrapped in `if (!this.isImmuneToFire)` at `Entity.java:541`, so the `8`
is what a non-immune body takes. The constant's own javadoc (`FireDamage.java:57-60`) notes
armour but not immunity.

**Row 42's rejections are the guarantee as much as its definition is.** Three better-sounding
definitions were considered and each fails for a different reason:

- **"Can the player see the sky", alone.** This is a **light** question in vanilla, not a safety
  one — `Chunk.canSeeSky` (`Chunk.java:904-910`) exists to decide whether skylight reaches a cell.
  A player at the bottom of a one-wide, four-deep pit is sky-closed and reachable by anything that
  walks. Necessary, not sufficient.
- **Light level**, which is the number a player would actually feel and the truest version of "the
  player was safe". **`SimWorld` cannot evaluate it**, and `KNOWN_GAPS` says there is no lighting
  here rather than approximating it. **Writing a rule nothing can evaluate would be a predicate
  that has only ever agreed with itself.**
- **Path reachability**, the most faithful definition available: "can anything walk from outside
  to where the player is". Deliberately **not** taken, because it is a decision about what the
  world permits rather than a measurement of what the body has — **that belongs to the planner,
  and this is the instrument.** It is named as the next definition rather than smuggled in.

**Row 44's acceptance criterion is invisible once the test exists, so it is written down as
§6.11.** `held == sheltered && floorHeld` is false in three of the four combinations, so a test
that only ever asserts `held` cannot tell "sheltered and hurt" from "exposed and untouched", and
either run is a way for one half to do all the work. The class asserts the whole four-cell table.
Its measured mutation numbers: making `Verdict.enclosed()` return true gives **12**; reverting
`neverBelowNorthStar` to bare `minimumHealth() > 0` gives **4**; making `nightClaim` drop the
enclosure half so `held == floorHeld` gives **3**. The first two fail **different** tests inside
the file, which is the whole claim.

**The class carries 4 tests and 28 assertion call sites**, counted off the file rather than the
reports, which do not carry assertion counts: 12 in the four-cell table, 5 in the
floor-is-not-survival test, 8 in the row-reads-both-halves test, 3 in the shared-fixture test.
A brief for this slice called it 31. **The file is the authority and the file says 28.**

**Rows 45 and 46 are the landing ratio going from 1 of 10 to 3 of 10, and both prove
`targetsChanged`, never merely "armed".** SEC-2's third mutation is the one worth recording:
commenting out the instruction insertion reproduces **exactly the `GlClampToEdge` armed-but-inert
shape**, and the suite goes red — **3** failures out of 15. A patch test that has never seen that
shape cannot distinguish armed from applied, which is the same reason row 1 exists at all.

**Row 47 is the first row in this section whose value is a claim about what the source does *not*
permit, and it is the reason the section has one.** `ResourcePackRepository.java:179-186`
constrains the destination name before any `File` is built, so no separator, `.`, `..` or NUL can
reach the filesystem; `deleteOldServerResourcesPacks` only ever deletes files already inside the
directory it lists. SEC-1 is a containment failure; this is an availability failure. **A row that
records a corrected claim is worth as much as one that records a confirmed one**, and the test
that would enforce it does not exist, which is what the **UNENFORCED** in its column means.

**Rows 48 and 51-52 arrived together, and the reason they are listed separately from rows 39-47 is
§2's own: an instrument guards a property, and a row guards a capability.** `DawnChest` costs the
walk no world reads (row 51) and answers **both ways from one world** (row 52), neither of which
row 48 says. Row 48 is the capability; rows 51 and 52 are the measurements that keep it honest.

**Row 49's `create_tool` entry is a security claim, and the file says so in its own words at
`ToolRegistry.java:97-100`:** a name filter blocks nothing when the verb can compile arbitrary Java
into the game JVM, so filtering the name would have been the shape of a fix with none of the
effect. **`SocketTransportServer.INSTRUCTIONS` is now `:85-87`, two clauses naming exactly one
verb** -- and the clause it used to carry is recorded as a **deletion** at `:52-58`, not as a
correction. §5.5 says why deleting beat rewording.

**Row 50 read the wrong registry, and 33 of 34 registered kernel-layered names were squattable**
-- the test's own measurement, in its javadoc at
`ReservedToolNamesAreCheckedAgainstTheAuditedRegistryTest.java:37-39`, naming `eval_java`,
`install_hook`, `invoke_method`, `write_field` and `send_raw_packet` among them. **The counts
re-derived from the table agree with it:** `ToolRegistry.buildLayers` declares **45** kernel-layered
names and **51** model-facing, of the 45 exactly **eleven** are the ADR-0004 `debug_*` folds that
nothing registers (`:121-124`), leaving **34** registered.
`isReserved` read `IoManager.isBuiltin` on the registry it had been *constructed* with, and that
registry is the model-facing **surface**, where kernel-layered names are absent **by construction**.
`IoManager.register`'s backstop does not close it. The test proves which check fired by refusing a
name that is not on the surface at all and asserting the **refusal text** `"is a reserved core tool"`
-- because a compile failure is also an error, and would otherwise satisfy the test for the wrong
reason. **What fixed it, stated as fixed:** `MetaTools` now holds a second registry, `audited`
(`:64`), threaded separately from `surface` (`:58`), and `isReserved` consults the **union of two
live terms**. `ToolRegistry.java:231-237` records why the second term is
`isDeclaredKernelLayered` and not `isKernelLayered`: the latter denies by default, so using it
there would "refuse EVERY name the model could ever invent and turn `create_tool` into a no-op
that fails with a gate error instead of doing its job." `promote("create_tool")` was **one property
away** (`-Dmcp.core.promote` read at `McpCore.java:143`, `mcp_promote.txt` at `:149`, and
`McpCore.promote` itself at `:185-212`), so this was reachable, not latent. The eleven `debug_*`
folds are reserved even though **nothing registers them**, because they are **declared** in the
layer table at `ToolRegistry.java:120-124` and the reservation reads declarations rather than
registrations -- which is what `MetaTools.java:364-374` argues, and the cost of a false positive
there is one refused tool name against a false negative being a forged hypervisor verb.

---

### 1g. What this sweep corrected, so the next pass does not have to guess

Sixteen citations in the previous version of this file were wrong. Two of the groups were wrong
**as a block**, every member off by exactly one line, which is what a number copied out of a diff
hunk header looks like rather than a number counted off an open file:

| where | was | is |
|---|---|---|
| row 7, §1e tail | `ActOutcome.java:146` | `ActOutcome.java:147` — `:146` is the javadoc closer |
| row 34 | `Daylight.java:97`, `:112` | `:98`, `:113` |
| row 35 | `Daylight`'s methods at `:70`, `:97`, `:112`, `:117`, `:122` | `:71`, `:98`, `:113`, `:118`, `:123` — all five |
| row 37 | constants at `:66`, `:76`, `:88`, `:99`, `:111`, `:114-117` | `:67`, `:77`, `:89`, `:100`, `:112`, `:115-118` — all six |
| §2.3 | `ALLOWLIST` entries at `:71-101`; size `4` at `:156` | `:72-101`; `:156-159` |
| §4 | `SimWorld.KNOWN_GAPS` at `SimWorld.java:156-164` | the list is declared at `SimWorld.java:110`; the chest sentence quoted there is at `:166-167` |
| §4.1 | `droppedOntoFloor()` at `SimWorld.java:285`, accessor `:1193` | `:327` and `:1342` |
| §4 | "24 tasks", "`18.0` appears zero times" | **25** tasks, and `18.0` appears **five** times |
| §5.5 | "two class names are cited in javadoc that do not exist" | **both were repaired**; the paragraph had become false without the file changing |

**The count is the finding, not the corrections.** Sixteen is not a handful, and they were spread
across nine rows and four sections rather than concentrated in one stale corner — which says the
anchors here were being written **by inference**, not by reading. That is why **§6.10** exists.

### 1g.1 The second sweep, whose fifteen were all in one section

This is the record of the **third** anchor sweep, and it is here because its shape is different
from the sixteen above and the difference is the finding.

| where | was | is |
|---|---|---|
| row 39 | `EvalSuite.survived` `:153-155` | `:155-157` |
| row 39 | `EvalSuite.neverBelowNorthStar` `:165-167` | `:167-169` |
| row 39 | `NORTH_STAR_HEALTH` `:134` | `:136` |
| row 39 | `SimWorld.minimumHealth()` `:665` | `:703` |
| row 39 | the producer cited as *"(main tree, 451 lines)"* | **the count is gone** and the class is cited; see §6.15 |
| row 40 | `T20TheDirectLineIsLava` `:1671` | `:1681` |
| row 40 | the `plantedHazardRun()` seam `:1772` | `:1823` |
| row 40 | T20's five pass rows `:1789` | `:1834-1840` |
| row 40 | the control's call site `:166` | **unchanged — still correct**; T20's `runWith` seam added at `:1724` |
| row 44 | `EvalSuite.NightClaim` `:200` | `:202-204` |
| row 44 | `nightClaim(w, enclosure)` `:210` | `:212-217` |
| row 44 | T25 registered `:115`, class `:2443` | `:116`, `:2494` |
| §1f tail | the conjoined survival site `:1101` | `:1103` |
| §0 | the one skip's `tests="9"` | `tests="10"` |
| §4 | "25 tasks" | **26** — T26 registered at `EvalSuite.java:117`, class at `:2766` |
| §4, §4.1 | `droppedOntoFloor()` field `:327`, accessor `:1342` | `:365`, `:1380` |
| §4.1 | `GoalPolicy.obtain`'s recipe-cycle guard `:204` | `:226` |
| §4.1 | the `thrownByPlayer` reader `:321` | `:343` |
| §3 | `333` `.java` files / `310` collected | `346` / `323` — rows 3-5 of that table are unchanged |

**All fifteen wrong anchors the sweep found were `EvalSuite.java` and `SimWorld.java`, and all
fifteen were in §1f except three.** That is not a coincidence and it is not "this section was
written carelessly": **those two files are the ones the last three waves edited**, and every one of
the fifteen numbers moved because a task or a field was added *below* it. The anchors in §1a
through §1e, which point at files nobody had touched that day, **all opened clean.** The rules that
come out of this are **§6.13** (do not re-open a section you did not touch) and **§6.14** (an
anchor into a file under active change is a half-life, not a citation).

**Two of §1g's own "is" values above have moved again,** which is the section arguing against
itself and is why it is worth printing: `droppedOntoFloor()` is now `:365`/`:1380` rather than
`:327`/`:1342`, and the task count is **26** rather than 25. **A correction log rots exactly like
the thing it logs.**

**And three claims inverted rather than decayed, which is the serious finding and not this
table.** §1d row 30 and §4 item 4 both said the eval had **no seam** and therefore **no negative
control of its own**; §1d row 40's neighbour had already credited one. The seam now exists in the
**main** tree and §1d has been rewritten to say so. **A claim of absence is the hardest kind of
rot to notice, because the fix is invisible from the document and the sentence still reads
fluently** — that is **§6.16**.

**And §6.14 proved itself while this pass was still running, which is worth more than a
measurement.** `Enclosure.java` was verified at `:124` early in this pass — `record Verdict`,
opened and confirmed — and **another worker edited the file while the pass was in flight**. By
the time the whole document was re-checked, `Verdict` was at **`:159`**, `sideClosed()` had moved
`:127` -> `:162`, `enclosed()` `:132` -> `:167`, and `describe()` `:137` -> `:172`. **Every one of
them was correct when written and wrong about forty minutes later**, which is the whole argument
in one incident: the number did not rot because it was counted wrong, it rotted because it was
counted right about a file somebody else was editing. The anchors above are the corrected ones.
**Nothing in the document records that `:124` was ever true**, and that is the point — a citation
has no expiry field, so **the only defence is to cite the symbol** and let the line be a
convenience.

### 1h. What the column says, counted off the column rather than asserted

Counted from the tables above by reading the last cell of every row, not carried from a previous
version of this file (the rule that applies to every count here, §6.9):

| letter | rows |
|---|---|
| **A** | 6 |
| **B** | 20 |
| **C** | 12 |
| **D** | 15 |
| | **53** |

**Six of fifty-three guarantees are load-bearing for a night survived, and that is the number
that matters.** Everything else in §1 is either an instrument for one of those six, a guard on
one specific branch, or a receipt for a fixture. **This is not a criticism of the other 47** —
most of them are worth having, and §5's mechanisms are real. It is a statement about what the
table could and could not tell you before this column existed: **it could not tell you at all**,
and rows 39-44 and row 21 read exactly like rows 33 and 25 did.

**The `A` list, and the one line each is really about.**

| row | the line that decides a night |
|---|---|
| 25 | every `act_set` interact/look argument is both read and named — **an unnamed `hitX/hitY/hitZ` is a box placed in the wrong cell**, and §1c's own note says those three shipped unusable once already |
| 28 | a hazard is readable **before** the walk arrives — the early warning is the only thing that lets a model stop walking into lava |
| 29 | a hazard is never guessed from an unread cell — **after a break, unloaded chunks are phantom pits everywhere and a model that trusts them does not take one step** |
| 33 | the client's "is it night" comes off the clock — the frozen read said *no* on every tick for the whole session |
| 34 | the night boundaries are vanilla's, over all 24,000 ticks — a curve that moved a tick moves when the model thinks to build a roof |
| 35 | the clock helper is structurally incapable of becoming a second frozen read, **proven from bytecode** — because a correct helper nobody calls reproduces row 33's defect with every behavioural test green |

> ### The Owner's question, answered by this column
>
> *"These guarantees — what do they have to do with the game I want?"*
>
> **Before this pass: no.** Not because the guarantees were wrong, but because the table had no
> vocabulary for the question. A row about `FireDamage` and a row about the client's frozen
> `isDaytime` both printed a `file:line`, a test class, and a green count, and a reader had no
> way to rank them.
>
> **After it: yes, with a number.** **Six guarantees are load-bearing** — 25 (a box placed in
> the right cell), 28 and 29 (knowing a hazard is there, and never inventing one from a chunk
> that would not load), 33/34/35 (knowing when it is night). **Twenty more raise the win rate**
> by telling the model *why* a walk ended. **Twelve guard one branch or one verb.** **Fifteen are
> receipts for a fixture, a script, or a registry table** — worth keeping, and not one night.
>
> **And the honest limit, which the column makes visible rather than hides.** The `A` six are
> all **preconditions**: the game is lost when they break, and it is winnable when they hold.
> **None of them is evidence that a weak model plus a system prompt will actually choose to
> build the roof and dig the box.** §6.2 already says the acceptance object is the model's
> decision, and §4's first three rows already say the behaviour is *"measured, not yet
> produced"*. **This column does not close that gap and does not pretend to** — what it does is
> stop the table from implying the gap is smaller than it is, which is the specific failure this
> file exists to stop.

### 1h.1 The guarantees that were overstated by their own placement

**This is the section the column was added for.** Each entry below is a row that carries a
`file:line`, a real test class, and a green report — and whose test drives a **fixture**, not a
client. The receipt is genuine. **What it is a receipt for is not a night.** These are ordered by
how much they would mislead a reader who trusted the table.

**1. Rows 39, 40 and 41 — the damage ledger has no production caller at all.** This is the
biggest finding, and it was verified twice by grep rather than inferred. `FireDamage`'s only
code references in `core/src/main` are **five lines inside `SurvivalDamage`**; `SurvivalDamage`'s
only constructor call anywhere in the repository is **`SimWorld.java:272`**, which is test tree.
`NORTH_STAR_FLOOR` is re-exported into `EvalSuite` — also test tree. **Nothing in a running
client constructs `SurvivalDamage` or reads `FireDamage`.** The live client reads health the
vanilla way, `p.getHealth()` (`WorldViewCapture.safeHealth`). So: row 41 pins that an
**eval-fixture** applies vanilla's lava arithmetic, row 39 pins that an **eval row** reads a
low-water mark, and row 40 pins that the **eval can go red**. None of the three touches a body a
player is watching. **§4's table calls the health floor "Yes" — that is right about the
instrument and silent about the client, and this column is what makes the difference visible.**

**2. Row 48 — "the fourth north-star criterion has a producer, and the producer is the agent".**
The producer is `GoalPolicy`, a **test-tree** class (§1d row 30 says so in its own row), reached
through the `Policy` seam. `new DawnChest` appears in **five files, none of them in
`core/src/main`**. The row's own sentence is accurate; **its position in §1f, four rows below two
A-class clock rows and above nothing, invited reading it as a live capability.** §4 states the
gap correctly — *"What is still missing is a **model** deciding to build it"* — and the two
sentences are not in conflict, but only one of them is in the table.

**3. Row 30 — a ledger against a policy written in Java.** The row is admirably honest (*"still a
**test-tree** class"*), and §6.2 is the rule that makes it irrelevant to the north star: **the
acceptance object is the weak model's decision.** `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest`
drives `GoalPolicy` against `SimWorld`. Its mutation number is real and its ledger is real; what
it proves is that *this Java chooser* would rather make room than refuse. **§6.2 is precisely why
that is not a north-star measurement, and this column is §6.2 applied row by row.**

**4. Rows 10 and 11 — the crosshair grade.** §4.2 already records that `mouseOver()`'s grade has
**zero production consumers**. Rows 10 and 11 are honest about what they are, and they are still
in a table where a reader counts `tests` numbers. **Row 11 in particular pins a javadoc** — it
cannot be `A` under any reading, and its green report is a real receipt for a comment.

**5. Rows 51 and 52 — the box predicate, twice.** `DawnChest` has no `core/src/main` constructor
caller, same as row 48. Two rows about a predicate only the eval constructs, one of them
carrying **8** tests. **This is the clearest case of the general shape: the count is high and the
distance is maximal.**

**6. Row 47 — the one `UNENFORCED` row, and it is honestly labelled.** Worth noting for a
different reason: it is the only row whose *value* is a claim about what vendored source does
**not** permit, and it carries no test at all. It cannot be misread as a receipt, which is
exactly what the `UNENFORCED` in its column buys.

**7. Row 53 — the seam, which is `main` and still not a match.** `Policy`, `PolicyRun` and
`BrokenPolicies` really are in the main tree and `TheSuiteGoesRedThroughTheSeamTest` really does
assert the package name. **What it establishes is that the suite can be made to fail** — an
honest negative control over a **test-tree** policy. Shipping the seam into `main` was the right
call and the row says why; **a seam that cannot be undone by a test refactor is still a seam
around `GoalPolicy`, and `GoalPolicy` is not the agent.**

**What this list is not.** It is not an argument for deleting any of these rows. **A receipt for
a fixture is still a receipt, and the instrument work in §2 and §5 is what made the `A` rows
trustworthy in the first place** — §2.2's zero-read gate is what lets row 35's structural claim
be about the walk rather than about a helper. **The defect was never the presence of these rows.
It was that before this column, a reader had no way to tell them apart** — and this file's entire
premise is that a claim a reader cannot check is the failure mode worth stopping.


---

## 2. The measurement instruments, and what each one pins

Nine instruments. For each: what it would catch, and — the half that matters more — what it
is structurally blind to.

### 2.1 The eight-scenario movement digest

`core/src/test/java/net/marcloud/mcp/core/drivers/act/TheWalkThisSliceChangedMovesAsItDidBeforeTest.java`

Drives eight walks (clear, a recovery, a recovery refused because the far side is a pit, a
point walk, a walk that publishes a jump for forty ticks, a body boxed in with nowhere to
step, a line with a deep drop, a line with water) and hashes every published axis, every
jump, every tick count, every recovery cost, every terminal message.

```
TRACE_SHA256_BEFORE = 7b7913703b103ec87e460e0ebf0394f30b5314a2da037d93b523d9d41b5b7d2f
TRACE_CHARS_BEFORE  = 17427
TRACE_LINES_BEFORE  = 340
```

asserted at `:153-170`. The expected values were taken from the tree **before `MoveTactic`
existed**, by running the same trace against the unmodified controller.

- **Catches:** any change to walking arithmetic, whatever its cause and however small. A
  per-tick expectation list would be a second copy of the controller's arithmetic, and a
  test that duplicates the thing it is testing catches the duplication, not the drift. The
  digest catches both drift and duplication.
- **Blind to:** anything about the *decision* rather than the movement — which is why it was
  needed when `MoveTactic` appeared and would not be needed again for a reporting change. It
  is also blind to anything the eight scenarios do not contain; it is a fixture of eight
  worlds, not a property of the controller.
- **Rule that keeps it meaningful:** **do not widen a walk change into a record-only change
  by relaxing this.** If the trace moves, the right response is to find out why the walk
  moved, not to re-baseline the digest. Re-baselining is the only way this instrument
  becomes a tautology, and it is silent.

### 2.2 The zero-world-read gate

`core/src/test/java/net/marcloud/mcp/core/drivers/act/TheBeliefLayerCostsNoWorldReadTest.java`

```
PRE_CHANGE_READS_10_TICKS  = 25      (:62)
PRE_CHANGE_READS_60_TICKS  = 25      (:63)
```

asserted as **exact equality**, not a bound (`:79-83`, `:100-104`), plus a counting-view
equality for the planner's half (`:148-150`) and a bytecode assertion that `BlockProbe.probe`
contains exactly one `INVOKESTATIC BlockProbe.at` and zero world-reading calls of its own
(`:189-194`).

- **Catches:** a belief decision that re-reads something the walk already read. This is
  ADR-0005's third category — a mechanism that spends capability and buys none — arriving
  under a new name, and it is the shape most likely to arrive by accident: a second probe to
  check readability, a re-verification pass, a "let me just confirm the chunk is loaded"
  whose answer was already in hand. All of those are invisible in a diff review. **A bound
  would let a regression of ten reads pass, and ten reads is exactly the shape being
  guarded against**, so equality is the right assertion.
- **Blind to:** `World.getBlockState` inside `BlockProbe` itself, which needs a real `World`.
  That half is covered structurally by reading the compiled call rather than by pretending
  to count something it cannot count. And it is a property of *one* walk over *one*
  corridor — a per-tick cost added on a path this fixture never takes would not register.

### 2.2a The clock's zero-read proof, which is bytecode rather than reading

`core/src/test/java/net/marcloud/mcp/core/drivers/act/TheClockCostsTheWalkNoWorldReadTest.java`
— **6 tests**, and the reason it is a separate instrument rather than another arm of §2.2 is
the second half of it.

The behavioural half is §2.2 again: asking `isDaytime()` on all 60 ticks of a walk leaves the
block-read count at `25 == 25`, and a third test drives a 10-tick and a 60-tick walk through the
same `CountingActuator` so the clock's cost is measured **by difference on the same walk** — a
single run's total is the walk's cost, not the clock's.

**The structural half is the part that matters, and it is ASM, not a grep.** A helper that took
a `World` and returned `w.isDaytime()` would pass every behavioural test in the slice and be
exactly the defect again: correct-looking, identically broken, this time with a green suite
attached. So two claims are read from the compiled classes:

- `theDaylightHelperContainsNoWorldCallAtAll` (`:170-220`) walks `Daylight`'s methods and
  asserts **zero** calls into `net/minecraft/world/` or `net/minecraft/entity/`, **zero** to
  `World.isDaytime`, and **zero** to `getSkylightSubtracted` — the two reads whose values on a
  client never change. It also asserts the method count is exactly **5** (`:205-209`) and the
  message says why: a different count means a method was added and the test has drifted off the
  code it guards, so name the new one here rather than widening the bound.
  The check deliberately **excludes `net.minecraft.world` rather than all of `net.minecraft`**,
  and says so: `MathHelper` *is* called, and must be, because its 65536-entry cos table is what
  puts dusk at 13,807 instead of 13,806. It is a static table with no world in it.
- `theLiveActuatorDoesNotDelegateToTheFrozenVanillaRead` (`:231-271`) reads
  `LivePlayerActuator.isDaytime()` and asserts **exactly one** call to `Daylight.isDaytime` and
  **zero** to `World.isDaytime`. That is the one-line mistake available there —
  `return w != null && w.isDaytime();` — which compiles, reads correctly, and reproduces the
  defect exactly.

- **Blind to:** everything that is not a call. A `Daylight` method that returned a **cached**
  answer computed from a world read elsewhere would pass all of this, because the cache would
  live in another class. The structural claim is "no world enters *this* class", and the
  behavioural claim is "asking it costs nothing on the tick path"; between them they leave the
  seam where it was — which is where §1e row 33's real proof lives.

### 2.3 The belief allowlist

`core/src/test/java/net/marcloud/mcp/core/util/GradedCallSitesAreAllowlistedTest.java`

Scans `core/src/main` for both spellings of an OBSERVED claim — `Graded.observed(` (bare or
qualified) and `Belief.OBSERVED`, one regex at `:114-116` — and fails on **one more or one
fewer** than the **four** allowlisted files (`ALLOWLIST` at `:67`, entries `:72-101`; one-more
at `:126-132`, one-fewer at `:136-141`; the size — `4` — at `:156-159`, file existence at `:161-163`
and a reason on every entry at `:165-167`). The class itself carries **4** tests.

The four are `BlockProbe` (design 2.A #5, the one correctly ordered read in the repository),
`RoutePlanning` (both refusal reasons are field reads in the expression that builds the
sentence), `DigController` (the completion where both names were read this tick and differ —
the other two completions on that same site are graded `INFERRED` and `UNKNOWN` beside it),
and **`ActOutcome`**, which is the new one. `ActOutcome` earns the entry by holding the five
named derivations of §1e row 37 and being the **only** spelling of `Belief.OBSERVED` anywhere
in the act and plan drivers. The reason one entry beats nine files is the whole point: a
permission held in one named place is reviewable; the same permission copied into nine files is
nine permissions nobody granted.

- **Catches:** layer growth. The stated failure mode is dated and specific: *"without this
  test, day 19 will grow a 19th `observed(`"*. Nobody adds one out of malice — they add it
  because the site genuinely was read directly and the factory is right there. Under the
  cheap mapping (everything is OBSERVED), 12 of the design's 18 sites are lying and **3 of
  those change an action already emitted**.
- **Blind to:** source text is not bytecode. A comment mentioning `observed(` counts, which
  is why the allowlist lists whole files and the failure message reports the offending line
  — a false positive is a one-line allowlist edit and a false negative is the layer quietly
  eroding. That asymmetry is a deliberate choice, documented at `:39-47`.
- **The "one fewer" direction is the half most allowlists omit.** An entry nobody holds reads
  as permission, reaches a future caller who greps for it, and makes the list look larger
  than the set of sites that were actually reviewed.

### 2.4 What `act_status` now exposes to a model

Emitted at `drivers/action/ActTools.java:1003-1016`, described at `:909` and `:959-977`.

Per slot: `slot`, `phase`, `hasIntent`, `intentKind`, `ticksActive`, `message`, `heldBy`,
`hazard`, `belief`, `unreadCells`, `tactic` — **ten** keys, up from eight. `belief` is `null` or
one of `OBSERVED` / `INFERRED` / `UNKNOWN`; `unreadCells` is `null` or a count; `tactic` is
`{forward, strafe, jump, yawChange, lane, givenUp, describe}` or `null`. The source of truth is
`ActStatus.SlotStatus` (`ActStatus.java:47-57`), projected at `ActRuntime.java:659-667`.

**`unreadCells` is `null` or a number, and the two are different claims.** The description says
so at `ActTools.java:967` in the terms a caller needs: a number means a search ran and could not
read that many of the cells it asked about — `412` is a chunk-loading problem quantified rather
than guessed at — and `null` means the line is not about a searched area at all, **which is not
the same as `0`**. `0` would claim a search ran and read everything, which is a stronger claim
about the world than any site in the tree makes.

- **Catches:** a caller that has to substring-match a prose `message` to learn *why* a walk
  ended. Before this, a model could not tell a walk that spent a lane from one that spent
  nothing, which is the whole of `NavHazard`'s argument for a typed field applied to a field
  that already existed.
- **Blind to — and this is the important one:** exposing a value is not the same as a model
  *using* it. The cheapest falsifier is to delete the three fields and re-run: **if no test
  goes red and no eval task changes its outcome, the fields were decoration**, and the
  correct response is to delete them, not to add a test asserting the field exists. That
  experiment has **not been run**. The behavioural falsifier — does a model given
  `THE_UNSURVEYED_WEDGE` and `THE_NAMED_HAZARD` issue *different* next commands — is also
  unrun, and it is the one that decides whether this bought anything.
- **`belief` and `givenUp` are read from values the decision layer discards.** On a `FAILED`
  MOVE step, `ActPlanInterpreter.step` copies `rec.message()` into the abort reason
  (`:119-120`) and drops both. So `act_plan`'s terminal report is **strictly less informative
  than the record it came from**, and the loss is by construction. Reading `act_status`
  gets you the grade; reading `act_plan`'s failure reason does not.

### 2.5 The survival ledger's zero-read proof

`core/src/test/java/net/marcloud/mcp/core/eval/TheSurvivalDamageCostsNoWorldReadTest.java` —
**3 tests**, and the same A/B shape as §2.2 applied to the hazard rather than to the clock.

Two runs differing **only** in whether the cell the body stands in holds lava, with no intent
submitted, so both halves do identical controller work and any difference in the block-read count
is the damage path's own cost. It is **0** for lava, **0** for water, and **0** for the void.

- **Catches:** a damage rule that asks the world something the walk already read — a second
  probe to confirm the cell, a hazard check on top of the collision check.
- **Blind to — and this is why the second half of each test exists:** a path that read nothing
  **and did nothing** also costs zero. So every test's second half asserts the damage actually
  happened, and the third test asserts the seam is still being used, because **a zero measured
  against a walk that never looks is blindness, not economy.**
- **Mutation:** routing the hazard probe through `ActActuator.blockAt` instead of the world's own
  grid gives **2** failures with the message *"60 ticks of standing in lava cost 300 reads through
  the controller seam and the same 60 ticks on a plain cost 360."* Zero on both sides would have
  been a different defect and this assertion shape is what distinguishes them.

### 2.6 The enclosure's zero-read proof, which is the same argument twice

`core/src/test/java/net/marcloud/mcp/core/eval/TheEnclosureCostsTheWalkNoWorldReadTest.java` —
**4 tests**.

Answering "is this body enclosed" every night tick costs nothing, which is a claim worth pinning
because the obvious implementation — route the enclosure question through `ActActuator` so it is
consistent with everything else — spends a read per tick and would be invisible in a diff.

- **Catches:** a cell-by-cell enclosure scan that reaches the controller seam instead of the
  world's own block grid.
- **Blind to:** the same thing §2.5 is blind to, and for the same reason. `NightEnclosure` also
  exposes a **sampling period**, so a scan that is cheap per sample and expensive per second is
  still possible; what is pinned is the cost per sample, not the cost per night.
- **Why it is listed separately rather than folded into §2.2:** two instruments guarding the same
  property on two different substrates is not duplication. §2.2 guards the walk; this guards the
  night, and the two have different fixtures, different failure messages, and different mutants.

### 2.7 The box predicate's zero-read proof, which arrived with the box row

`core/src/test/java/net/marcloud/mcp/core/eval/TheDawnChestCostsTheWalkNoWorldReadTest.java` —
**3 tests**. §2.5 and §2.6 again, on the third substrate: `DawnChest` is sampled on **every tick
of a night**, and the question it answers is the obvious expensive one.

- **Catches:** a chest check routed through `ActActuator.blockAt` rather than through the
  `Enclosure.CellGrid` method reference `DawnChest` takes (`DawnChest.java:101-104`). That would
  push the exact `25`-read figure §2.2 and §2.2a guard by roughly thirty thousand over one night,
  and it would look entirely reasonable in a diff.
- **Blind to:** the same thing §2.5 is blind to, and for the same reason. A predicate that reads
  nothing **and measures nothing** also costs zero, so the A/B is **by difference** over the same
  world and the same clock, and the "damage actually happened" second half excludes a walk that
  never looked.
- **Why it is listed separately rather than folded into §2.2:** the same reason as §2.6. Three
  instruments guarding one property on three substrates is not duplication; they have different
  fixtures, different failure messages, and different mutants.

### 2.8 The box predicate answers both ways, from one world

`core/src/test/java/net/marcloud/mcp/core/eval/TheDawnChestPredicateHasBothVerdictsFromOneWorldTest.java`
— **8 tests**, and the reason it is an instrument rather than another arm of §2.4: **the predicate
has to be able to say no.**

"A box standing at dawn" answers **twice**, and the two verdicts can disagree:
`DawnChest.standingAtDawn()` (`:208-210`) is the point reading on the last tick, and
`heldThroughout()` (`:219-221`) is the region reading over the whole window. A predicate reduced to
one boolean could not tell a box that stood from a box that happened to be there at dawn.

- **Catches:** the obvious implementation — "is a chest in the world when the night ends" — which
  **cannot say no in any world where the player never touches the chest**, because nothing in this
  substrate removes a chest except the agent's own dig.
- **Blind to:** anything about how good the box is. Both verdicts are about *presence over time*;
  neither says whether the enclosure was sealed or whether the health floor held. That is §1f rows
  39, 43 and 44, and the four-cell table in §6.11 is what keeps those three separate.
- **Why one world:** two worlds differing in a chest could differ in anything else as well, and a
  failure would not say which. One world read two ways over the same clock has exactly one
  variable, and here that variable is the agent's own dig.

---

## 3. The surefire collection gap — a trap for the next person

**Read this before adding a test.**

Surefire's default includes match `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`,
`**/*TestCase.java`. This repository's convention for contract tests is
`AThingDoesSomething.java` and `TheThingIsSomething.java` — **which surefire does not
collect.** `core/pom.xml` configures no `<includes>`, so the defaults apply.

Measured on this tree, 2026-10-02, **re-derived during the second anchor sweep**:

| # | measurement | value |
|---|---|---|
| 1 | `.java` files under `core/src/test/java` | **346**
| 2 | matching surefire's default includes | **323**
| 3 | not matching | **23**
| 4 | of the 23, files carrying at least one `@Test` | **7 — and all 7 end in `LiveIT`**
| 5 | of the 23, files carrying **zero** `@Test` | **16**

**323 is also the number of live reports in `core/target/surefire-reports`**, one for one, with
zero files lacking a report and zero reports without a file (§0). That is the strongest statement
available about this table: every file the include pattern matches was asked a question by the
last run. **Rows 1 and 2 move while other people are working and only they move** — they were 333
and 310 at the last sweep, 323 and 300 before the damage, enclosure and corpus waves. Rows 3, 4 and
5 have not moved across any of those, because they are properties of the *naming convention*
rather than of the tree's size. Re-measure rows 1 and 2; **do not subtract, and do not carry the
old pair forward** — a pair of numbers that was correct last sweep is not evidence today.

**§3.1's arithmetic does not move, and it is worth saying why.** `23 + 7 = 30` reads off row 3 of
this table plus the seven renames, and row 3 is stable.

### 3.1 What was fixed

**Twenty-nine test files were silently uncollected when the gap was found; the same count run
against today's tree gives thirty. Seven real contract tests were renamed** to
`…Test`, and each of the seven now has a surefire report on disk. The count is not a remembered
number: **23 files still do not match** (the table above) **plus the 7 that were renamed** = 30.
The 23rd is `RouteRefusalProbe`, a helper the grading wave added after the original count was
taken; it is the sixteenth zero-`@Test` file and it is listed in §3.2 rather than quietly
folding the count back to 29.

| class | tests |
|---|---|
| `ABlockLastsUntilTheCallerReleasesItTest` | 3 |
| `ACritIsSwungInTheFallingWindowAndNotOnFlatGroundTest` | 4 |
| `SneakingStopsAtTheEdgeAndWalkingOffDoesNotTest` | 1 |
| `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest` | 4 |
| `TheWalkThisSliceChangedMovesAsItDidBeforeTest` | 1 |
| `TheFallDamageNumberIsVanillasTest` | 4 |
| `OpenPauseMenuIsHonestAboutNotBeingThereTest` | 2 |

The suite moved **1803 to 1822** from the rename (+19, and the seven rows above sum to `19`
against their own reports) and **1822 to 1825** from the `ARRIVED_AFTER_A_LANE` slice that
landed alongside it (+3). The commonly-quoted "1803 to 1825" therefore covers two changes,
not one; the rename alone is +19. **From 1850 onward the decomposition is in §0.1**, where
each leg is marked as measured or historical.

Two of those seven were load-bearing for the slices that produced them: the movement digest
(§2.1) and the routed-terminal-record test (row 13). Before the rename, **the claim "the
walk did not move" was backed only by a developer running one test by hand.**

### 3.2 What deliberately remains uncollected, and why

**The 7 `*LiveIT` classes.** `GuiClickLiveIT`, `NativeDebugOpLiveIT`,
`SeamOnLiveConnectionLiveIT`, `DigLiveIT`, `HoldLiveIT`, `InteractLiveIT`, `LookLiveIT`.
They need a live Minecraft client (or the native JVMTI agent). `core/pom.xml:123-155` binds
them to **failsafe** with `skipITs` defaulting true, run via
`./mvnw -pl core verify -Dcore.it.skip=false`. `debugging.md` §10 gives the reason they
cannot be JUnit at all: `GameAccess` reads `Minecraft.getMinecraft()`, a static singleton
that exists only in the game JVM, so it is permanently `null` in a forked surefire JVM.
**Do not rename these to `*Test`.** That would move a test that can only skip into the
suite that reports green.

**The 16 helpers.** `BodySim`, `FakeActuator`, `FakeWorld`, `CraftBench`,
`FakeCraftWindow`, `EvalHarness`, `EvalSuite`, `GoalPolicy`, `RouteRefusalProbe`,
`SelfPlayEval`, `SimBody`, `SimWorld`, `SimMob`, `SimCraftWindow`, `LiveGameGate`,
`DeepAccessProtectedBase`. Each carries **zero** `@Test` methods.
**Do not rename these to `*Test` either** — a class with no `@Test` that matches the include
pattern produces an empty report and, on some surefire configurations, a failure that looks
like a build problem.

### 3.3 The rule

> **A contract test goes in a file named `…Test`. A test helper does not.**
> The name is not a style preference. It is the only thing that decides whether the suite
> ever asks the file a question.

Adding a test named `AFooDoesBar.java` produces a green suite and a test that has never
run. That is the exact failure this session spent itself removing, and it is the failure a
green suite cannot report — which is why it is written here rather than left to a review.

### 3.4 What must stay out of the tree, and where the reason is written down

Same shape of trap, one layer down: an artifact with **no producer in the repository** that
appears anyway, because something extracts it at runtime and nobody deleted it afterwards.
`.gitignore` already carries the entries; what is worth recording here is *why each one is
there*, because an ignore line without a reason is an entry somebody eventually "tidies up".

**`.lwjgl/` — `.gitignore:48-52`, reason at `:48-51`.** LWJGL extracts its native libraries
into a `.lwjgl/<version>/` directory **beside whatever module loads them**, so it lands under
`core/` here and would land under `client/` too. Measured on this tree:
`core/.lwjgl/3.3.6-snapshot/x64/` holds `lwjgl.dll` (493,056 bytes) and `lwjgl_opengl.dll`
(359,936 bytes) — **836K** in two binaries. **No source file in the repository produces
them** and they are rebuilt on demand, so committing them would put a binary in the tree with
no producer and no reviewer able to say what changed. The same reasoning, in the same file,
covers `target/`, `test_run/`/`test_natives/` (game assets, saves, logs), `_tools/` (the
vendored JDK), and `core-jvmti.{dll,dylib,so}`.

**`.ai-notes/` — `.gitignore:56`.** The audit reports this document is distilled from, all of
§0's history, and this section. They are working notes, they are large, and they are worthless
without the tree they describe. **They are deliberately not in the repository, and this file is
the part that is.**

---

## 4. What remains unmeasured

Stated plainly, because the list is short and the claims behind it are large. **Three of the four
north-star criteria changed status while this document was being refreshed** — two of them while
the version before this one was still on disk — and every change was invisible from here until
somebody opened the files.

**The self-play eval has 26 tasks** (`grep -c 'implements Task' eval/EvalSuite.java` = 26 — it read
24 before the enclosure wave added `T25ShelterThroughTheNight`, and 25 before the box wave added
`T26TheAgentBuiltTheBoxAndItStoodAtDawn`, registered at `EvalSuite.java:117`).
It is a high-quality
integration and composition gate over the production controllers: world-fact assertions across
locomotion, the input-layer timing gate, hazard reporting, inventory transfer, two container
models, the placement chain, and now a survival ledger and an enclosure predicate, with mutations
run in both directions. **It is still not a north-star measurement in the sense the Owner's
sentence means**, and the reason has not changed: it drives a policy written in Java, and the
acceptance object is the weak model's decision (§6.2).

The north star names four things. Here is what exists for each, re-measured:

| criterion | measured? | what exists instead |
|---|---|---|
| survive a shelter / have a shelter by dawn | **Yes, as a predicate; not yet as a decision.** `eval/Enclosure.java` and `eval/NightEnclosure.java` exist, `T25ShelterThroughTheNight` reports both halves of the claim, and three controls prove each piece can fail (§1f rows 42-44). What is missing is a **model** choosing to build the roof — the claim is measured, the behaviour is not yet produced | `Enclosure.Verdict` at `Enclosure.java:159` (sky-closed AND side-closed, reported as three fields), folded over a window by `NightEnclosure.sheltered()` |
| health never below 18 | **Yes — as an instrument. Not as a client fact, and §1h.1 is where that is spelled out.** `SurvivalDamage.NORTH_STAR_FLOOR = 18.0F` (`SurvivalDamage.java:71`), re-exported as `EvalSuite.NORTH_STAR_HEALTH` (`EvalSuite.java:136`) and asserted by `EvalSuite.neverBelowNorthStar` at `:167-169`. `18.0` appears **five** times in the eval tree where it used to appear **zero**. **The correction this pass found: `SurvivalDamage`'s only constructor call in the whole repository is `SimWorld.java:272`, which is test tree, so the 18-floor is enforced over the eval substrate and not over a running client** — the live body reads vanilla's own `p.getHealth()`. The row's "Yes" was about the harness and read as about the product, which is §4's own named failure shape wearing a new costume | the bar is now a ledger the world's own damage rules write, and `ALavaBandThatKillsThePlayerIsVanillasArithmeticTest` walks it tick by tick; **the ledger has no production caller, so nothing in a client consults it** |
| a box standing at dawn | **Yes, as a predicate and as a task — and the producer is the agent.** `EvalSuite.T26TheAgentBuiltTheBoxAndItStoodAtDawn` (class at `:2766`) starts from a world with no chest and has to build one; the predicate is `DawnChest.standingAtDawn()` (`DawnChest.java:208-210`) beside `heldThroughout()` at `:219-221` (§1f row 48). What is still missing is a **model** deciding to build it | `DawnChest`, over the production window from `NightEnclosure.duskTick()` |
| a whole night | **Partly, and the "partly" has not moved.** The substrate **has a clock** — `SimWorld` advances `worldTime` 1:1 per tick, transcribed from `WorldServer.java:206-209`, and T24 walks a leg in daylight, waits the night out, and walks a second leg inside it | "it got dark" is assertable, and since the enclosure wave "it was enclosed" is too. **"it was dangerous" is still not**, and `KNOWN_GAPS` says exactly that: *"the CLOCK exists and the LIGHTING does not, and the two are not the same gap"*. Nothing spawns in the dark, no zombie burns at dawn, no light level gates a cell, a torch changes nothing |

**The first three rows are the reason §4 shrank, and they are still not the whole north star.** A
predicate that exists is not a behaviour that happens: what is measured is that the harness can
tell whether a body was sheltered, whether its bar held, and whether a box stood at dawn — not
that a weak model plus a system prompt builds the roof or digs the box. That gap is the same gap
§6.2 describes from the other side, and it is unchanged by this wave.

**The third row used to read "**No.** A chest does not exist in this substrate", and it cited
`SimWorld.KNOWN_GAPS` at `:166-167` as proof. That sentence is **still true about `SimWorld`** and
it is **irrelevant to the question**, because the criterion is measured over the eval substrate,
which does model a chest. **A fact about the fixture was being read as a fact about the project's
capability** — which is `failure-shapes.md`'s shape exactly, wearing the costume of a measured
gap. It is worth keeping in mind how reasonable the wrong row looked: it cited a real declaration,
quoted it accurately, and pointed at the right line.

Four verified facts behind that table:

1. **`SimWorld` has a clock, and the lighting it does not have.**
   `grep -c 'worldTime\|timeOfDay\|isNight\|dayTime\|isDay\|24000' SimWorld.java` returns
   **23** (it returned **0** before the clock wave). `ticks` is now mapped onto a day:
   `atWorldTime(long)` pins it, `doDaylightCycle(boolean)` is the control, `skipToNextDay()`
   reproduces vanilla's own `i - i % 24000L` arithmetic so it lands on exactly `0`, and
   `isNight()` / `ticksUntilDawn()` ask `Daylight` — the **production** helper, so the fixture
   and the vanilla code agree because they are the same arithmetic rather than because the
   task was written to match. A night is 8,386 ticks long, so any other rate would answer a
   different question.

   **What this did not fix, in the substrate's own words:** `KNOWN_GAPS` records that a night
   here is a counter saying so and nothing else. No hostile mob spawns in the dark, no zombie
   burns at dawn, no light level gates a cell, and a torch changes nothing.

2. **The clock reaches no controller, and that is now a decision rather than an accident.**
   `EnvView` carries `worldTime`, `timeOfDay` and `daytime`
   (`drivers/world/EnvView.java:18-20`), and
   `grep EnvView core/src/main/java/net/marcloud/mcp/core/drivers/act core/src/main/java/net/marcloud/mcp/core/drivers/plan`
   returns **0 matches** — re-measured, not carried. `EnvView` is referenced only by `WorldView`,
   `WorldViewCapture`, `WorldViewDiff` and `WorldViewJson` — the observation surface. **A
   model can read the time of day, and what it reads is now true (§1e row 33). Nothing that
   decides anything can.** `ActActuator.isDaytime()` is documented as a reportable fact
   precisely so a future controller cannot quietly branch on it: the Owner's acceptance object
   is the weak model's decision, and a Java component choosing from the clock would replace
   that decision with an arithmetic fact.

3. **A damage source is reachable from a task now, and was not.** This entry used to read *"No
   damage source is reachable from a task"* with the proof that `SimWorld.health` had three
   writers, none of them an environmental hazard, so `w.health() > 0.0D` was the constant
   `20.0 > 0.0` at **ten** sites. **All ten are gone.** `SimWorld.health` is gone with them:
   `SurvivalDamage` (a class in the **main** tree, and deliberately cited as one — no length,
   see §6.15) now holds the ledger, and lava, drowning, fire, the void and falls all write it
   through the same order vanilla's `Entity.update` runs them in.
   Two things about the old entry are worth keeping because they are still true and now
   constrain the fix rather than describe a hole:

   - `spawnMob` is still called from **zero** `EvalSuite` tasks — its four callers are all in
     `AMobThatActsIsMeasuredInTheWorldTest` (`SimWorld.java` aside), which is not one of the 26.
     So no mob moves the bar from inside an eval task; the hazards that do are environmental.
   - The mob path had a real transcription defect that this wave fixed rather than reproduced:
     `SimWorld.damagePlayer` used the **full** 20-tick hurt window, while
     `EntityLivingBase.attackEntityFrom` tests `hurtResistantTime > maxHurtResistantTime / 2.0F`
     — a **10**-tick band. The old code made every mob hit land at half the game's rate. It now
     routes through the same `SurvivalDamage.attackFrom` the hazards use, so a body cannot be
     invulnerable to lava and mortal to a zombie.

   **What replaced the ten tautologies, and why the replacement is not equally vacuous.** Two
   shared rows do the work: `EvalSuite.survived` reads `SimWorld.minimumHealth()`, the **low-water
   mark** of the bar rather than its final value, so a survivor who was one lava tick from dead
   three hundred ticks earlier is reported as what it was; and `EvalSuite.neverBelowNorthStar` is
   the project's own 18-floor claim rather than a bare survival check. T20 is the task that has to
   carry it, because T20 is the task with a lethal hazard next to the route — and `lavaTicks`
   there is the damage rules' own counter, not a scan of a trace, so it cannot be satisfied by a
   code path that never applied damage. §1f row 40 is the control that proves it.

4. **`GoalPolicy` is the reference policy, is still `public final`, exposes one action family,
   and is now substitutable through a main-tree seam.** The class declaration at
   `GoalPolicy.java:80` reads `public final class GoalPolicy implements Policy`, and its own
   javadoc at `:66-72` says what changed: *"before it, this class was `public final` and
   constructed directly at eleven sites, so no task could be run with any policy but this one and
   the suite had no negative control. `obtain(String)` was already the whole public decision
   surface, so the interface cost nothing and moved nothing."* **The eleven is now ten** —
   `grep -rn 'new GoalPolicy' core/src` returns fifteen lines, **ten** of them code and five
   javadoc, re-counted this pass with comments stripped.
   Its own javadoc still says what it measures (`GoalPolicy.java:61-65`): *"given a plan, do the
   production controllers execute it against a real world? A pass is evidence the controllers
   compose; it is NOT evidence a model would produce this plan."*

   **This entry previously said the policy "has zero references from `core/src/main`". That is now
   false, and the false part was the load-bearing part** — the whole claim rested on nothing in
   `main` being able to reach the decision. `core/src/main/java/net/marcloud/mcp/core/eval/` now
   holds `Policy.java`, `PolicyRun.java` and `BrokenPolicies.java`, and
   `TheSuiteGoesRedThroughTheSeamTest.theSeamClassesAreLoadedFromTheShippedArtifactNotTheTestTree` asserts
   the package name so a refactor cannot quietly move the seam back into the test tree. **§1d's
   row 30 said the same thing from the other direction and was equally wrong**; both are corrected
   together because **two sections of one document contradicting each other about a fact is a
   defect in the document**, and §6.16 is the rule that would have caught it.
   **Its line count is deliberately not given: this entry once said 1317, and it was already wrong
   when written.** See §6.15 for why that is a rule and not a preference.

### 4.1 Two smaller items, verified this session

- **`SimWorld.droppedOntoFloor()` has zero readers in the entire repository**
  (field declared at `SimWorld.java:365`, accessor at `:1380`; re-measured three times now, because
  the previous two versions of this document cited `:285`/`:1193` and then `:327`/`:1342`, and
  **all four were wrong** — `:285` is `private double motionZ;`, a field declaration of an
  unrelated field rather than the javadoc opener this entry called it). The only *callers* of the
  name anywhere in `core/src` are inside `SimWorld.java` itself; the one hit outside it is a
  **javadoc mention** in `DawnChest.java:91`, which is not a reader. Its sibling `thrownByPlayer`
  carries five readers — four assertions in `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest`
  (`:58`, `:81`, `:132`, `:187`) and one in `GoalPolicy.java:343`. The split is right and
  only one half is load-bearing. By this repository's own stated test for decoration — *"a
  case that changes no behaviour is decoration"* — the other half is decoration.
- **`GoalPolicy.obtain`'s recipe-cycle guard (`GoalPolicy.java:226`) has zero coverage.**
  `grep -rn "recipe graph cycles" core/src` returns that line and nothing else. The T19
  fixture fix removed the suite's only exerciser of it. That fix was correct; the cost is
  that nothing in the repository noticed the coverage disappearing, **because nothing
  measures coverage.**

### 4.2 Half-done, stated as half-done

- **The crosshair's grade still has zero production consumers.** `ActActuator.mouseOver()`
  is declared at `:64` and implemented at `LivePlayerActuator.java:75`; `grep -rn 'mouseOver'
  core/src/main` returns those two lines plus one comment in `LookController.java:364`, and
  **no caller**. Rows 10 and 11 of §1b are honest about what it is; they do **not** make it
  used. This is one instance of "documented as existing, actually absent", and the fix here
  changed the claim rather than building the consumer. **Building the consumer is the
  unfinished half**, and it is the same unfinished half the grading wave ran into from the
  other direction: 132 sites named their derivation and the crosshair is still not one of the
  things a model asks.
- **The plan-step verdict is still prose.** `ActPlanInterpreter.step` aborts on every
  `FAILED` step with `"step N failed: " + rec.message()` and no typed verdict carrying the
  belief and the `givenUp`. There is no `StepVerdict` type in the tree. The three-way
  verdict exists one package over as a reference shape (`InteractController.awaitCritWindow`
  at `:244`, called at `:207`) — so the pattern is finished and local, and only its
  application is missing.

---

## 5. The failure shape that recurred, and the mechanisms now against it

**Not a postmortem — an operating note.** The shape is *"documented as doing something,
whose producer is absent, renamed, or never executed."* It appeared at least **ten** times in
one session — the tenth being row 32 of §1d, found by the worker writing this file while
writing this file. Here is what is now in place, and for each, what it catches **and what it
cannot catch**.

### 5.1 The compat layer reports `targetsChanged`, not only `armed`

**Catches:** a patch whose signature verifies and whose matcher never fires — the exact
failure of `GlClampToEdgePatch`, which matched an `LDC` where javac emits `SIPUSH` and
therefore armed, verified, and changed nothing on every real load.
**Cannot catch:** a patch that changes bytes *incorrectly*. `targetsChanged > 0` says the
engine handed the JVM a different array; it says nothing about whether the rewrite was
semantically right. The engine's own javadoc says this at `CompatEngine.java:56-58`.

### 5.2 The production access gate has a mutation-verified test

**Catches:** a gate wired in production whose `require()` is an empty body — `AllowAllGate`
was exactly that, and `HookTools`' own javadoc claimed the tools were gated by "the ring AND
an AccessGate defense-in-depth check". The second term did nothing.
**Cannot catch:** a capability that is correctly gated but should not exist. `McpCore.java:655`
still builds the monitor over `SeToken.wideOpen()` — the dev default that passes every layer —
and that is a decision with a record rather than an oversight: tightening first would leave the
agent unable to do anything while the layer that decides what those capabilities are **for**
does not yet exist (§4.2). **An open question with a record, not a closed one.**

### 5.3 `Graded.observed` is allowlisted, and the allowlist fails on one more *or fewer*

**Catches:** silent layer growth, and allowlist rot. The list is **four** files, and it fails on
one more *or* one fewer (§2.3).
**Cannot catch:** a site that claims `INFERRED` when it should claim `OBSERVED`. The
allowlist governs the strong claim only; nothing checks that the *weak* grades are earned
either — and the grading wave made that sharper rather than softer, because after it **every**
site in the act and plan layers names one of five derivations and only `Belief.OBSERVED` has an
allowlist. `REUSED_CELL` and `PLANNED_ON_CLIENT_WORLD` are both `INFERRED`, and nothing on earth
distinguishes a site that earned either from one that guessed.

### 5.4 Descriptions and schemas are asserted against each other

**Catches:** a description naming an argument the parser does not read, a parser reading an
argument the description never names, a tool description naming a tool that does not exist, a
`sections` entry silently dropped, and an emitted key the description omits. The `act_status`
gate derives its key list from the emitted map, so adding a field without documenting it
fails.
**Cannot catch:** a description that is *wrong* rather than *incomplete* — one that names an
argument correctly and then misdescribes what it does. Every mechanism in this list checks
agreement, and agreement is not correctness. `ToolDescriptionsMatchTheirBoundsTest` is the
closest thing here to a semantic check, and it is per-tool and hand-written, so it covers
what somebody thought to cover.

### 5.5 The shape that none of the above catches

**Every mechanism in §5 is a drift guard, and a drift guard cannot catch a defect that was
never a drift.** The move-applier grade drop, the `droppedOntoFloor` reader that does not
exist, the cycle guard whose last exerciser a fixture fix removed, the crosshair with zero
consumers, and **the client's frozen `isDaytime`** were all present from the moment they were
written. Each was caught by a test that **reads the far side of a seam**, or by a measurement,
not by a test that watches for change:

- `ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest`
  exists because the type-level test could not see the seam.
- `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` exists because two audits
  disagreed and the disagreement was only resolvable by driving production.
- `TheProductionDayNightReadIsNotFrozenTest` exists because a perfect `Daylight` that nothing
  called would have left the world view claiming noon at midnight **with every helper test
  green**. The defect was never the absence of a helper; it was the presence of a frozen read
  on the production path.
- `ABeliefCensusOverTheActScenariosTest` exists because 132 sites naming a derivation is a
  count, and a count is exactly what a decoration also produces.

**The generalisable form: a test that asserts a property of a component is silent about the
property of the seam between components.** Each of these was caught by asking that question
("and who reads *that*?") or by driving the real thing, which is why §4.1 is a list rather than
a green suite.

**And the mirror-image mistake has its own form, which is easier to make because it looks
careful: cite a test class that reads something else.** `BuildScriptContractTest` is the class a
search for "what tests the scripts?" points at, and it reads `build-clang.sh` in a different
directory (§1d). A row citing it for the signing script was wrong that way once, in draft. **A
test class's name is not evidence of what it reads**, and unlike a wrong line number a wrong
class name cannot be caught by following the citation — a reader has no reason to doubt a name
that specific. Every `file:line` in §1 and every class name beside it is therefore checked by
asking the file system, and **by asking what the class opens, reads, or drives.**

**The two invented-javadoc citations this section used to name are both repaired.** The previous
version of this paragraph recorded that `Daylight.java` and `ActOutcome.java` each pointed at a
test class that did not exist. Both now name real ones — `Daylight.java:32` names
`TheClockCostsTheWalkNoWorldReadTest` and `ActOutcome.java:44` names
`ABeliefCensusOverTheActScenariosTest` — and **the paragraph had become false without either file
changing.** That is the cheapest possible demonstration of the whole shape: a document can
describe a defect that has since been fixed, and keep doing it confidently.

**And a document can go wrong on a *supported configuration*, which is worse, because the
document is what a reader trusts when the configuration is unusual.** The handshake sentence at
`SocketTransportServer.java:85-87` used to name `create_tool`. The obvious repair was to reword
it — *"use `list_capabilities` to see every tool you can call, but not `create_tool`"* — and that
repair is **wrong**, for a reason no test would catch. `create_tool` is kernel-layered
(`ToolRegistry.java:101`) **but promotion is a supported operator feature**
(`-Dmcp.core.promote`, read at `McpCore.java:143`). **The moment somebody sets
`-Dmcp.core.promote=create_tool`, a reworded negative becomes a false sentence**, and a document
that goes wrong on a configuration the product supports teaches its reader to distrust the whole
document.

**So the clause was deleted rather than reworded, and the deletion is recorded as a deletion** at
`SocketTransportServer.java:52-58` — not as a correction to a list. **Deleting the clause leaves a
true sentence under every configuration the operator can actually reach**, and a reworded negative
leaves a true sentence only under the configurations somebody remembered to check.
`TheHandshakeSentenceNamesNoKernelVerbTest` (2 tests) enforces it against **`ToolRegistry.layerOf`**
— that is, against the layer table itself — **and not against a typed list of forbidden words**;
its javadoc at `:39-45` gives the reason, and the reason is this whole section: a list copied
into the test is **the stale-gate-row shape**, a second copy of a truth that already lives in
`ToolRegistry` and will drift from it.

---

## 6. Do not do this

The rules that survive contact with a well-meaning implementer. Each is stated as a
prohibition, because that is the part of a design that gets remembered.

**6.1 Do not build a humanisation module.**
A corpus-wide search of **41 reference clients** for
`humaniz|jitter|antihuman|mouselook|eases|smoothing|stealth|disguise` returns **0 files
each** (`wave2-CorpusDecisionLayer`). The only humanisation-adjacent hits are OptiFine noise
textures and framebuffer smoothers. There are exactly two server-measurable mechanisms —
click-interval distributions and rotation curves — and on both this repository is already
equal or better; `wave2-TimingHarvest` records **0/41** perception to action latency and **0/41**
hesitation. ADR-0005 §1 classifies an input change that spends time to buy nothing measurable
as **forbidden**, and this is the case it was written for.

**6.2 Do not write a goal policy in Java.**
The acceptance object is the **weak model's decision**. A Java chooser that decides *walk or
dig* would make that acceptance criterion permanently untestable. This is why the verdict
carries a reason and a grade rather than a substitute action: **the runtime reports, the
model decides.** Corollary: a chooser that makes the agent slower, or that spends a
capability to look human, is the failure mode rather than the goal.

**6.3 Do not widen a walk change into a record-only change.**
The moment walking and recording are separated even once, the eight-scenario digest (§2.1)
stops meaning anything, because it was taken against a tree where they were the same change.
If the digest moves, find out why the walk moved. Re-baselining the expected value is the
only way that instrument becomes a tautology, and it does so silently.

**6.4 Do not relay a conclusion without the reasoning that produced it.**
This mistake corrupted three separate briefs in one session. A conclusion handed over
without its derivation cannot be re-checked by the next reader, and **a wrong conclusion
handed over as an anchor is more dangerous than no anchor at all** — because a worker given a
"verified anchor" stops verifying. One brief shipped a wrong method descriptor
(`File.<init>:(Ljava/lang/String;)V` where the real signature is
`(Ljava/io/File;Ljava/lang/String;)V`); the worker caught it. When you hand over a finding,
hand over **how you got it**.

**6.5 Do not rename a test helper to `*Test`.**
§3.2. A helper with no `@Test` methods that matches surefire's include pattern produces an
empty report. And do not rename a `*LiveIT` to `*Test` either: that moves a test that can
only skip into the suite that reports green. **The rule is "a contract test goes in a file
named `…Test`; a helper and a live-gated test do not."**

**6.6 Do not add an `<includes>` block to work around a naming problem.**
It would collect the **16** helpers (§3.2) along with the seven real tests. Fix the names.

**6.7 Do not put a replan policy inside `RoutePlanning.executorFor`.**
`RoutePlanning.java:16-22` names "plan once" a deliberate limit and gives the reason: a
replan shipped silently inside that factory would make the first failure hard to attribute,
which is worse than having no replan.

**6.8 Do not add a fourth `Belief`, a `double confidence`, or a `Map<Cell, Belief>`.**
`Graded.java:9` forbids the parallel structure (it would be a second source of truth);
`:20` forbids `double confidence` (uncalibrated, and 1.8.9 sends no verdict back); `Belief`
is exactly three constants on purpose — `UNKNOWN` is not coarser than `INFERRED`, it means
*refuse or go load the chunk*, and folding one into the other makes the layer decoration.

**6.9 Do not pin a number in this document.**
The counts in §0, §0.1 and §3 were accurate when written and §0 says so explicitly, **and §0.1
marks which of its own legs are measured and which are carried history** — because a
decomposition whose parts can be shuffled without changing its sum is a decomposition nobody
has checked. Re-measure them; the instruction is in the document for the same reason the
instruction is in `branch-topology.md` §0.1. **Three later rules narrow this one rather than
restating it: §6.15 forbids the one number with no symbol to fall back on, §6.17 forbids summing
a report set that has not been joined against the source tree, and §6.13 says which numbers to
re-measure first.**

**6.10 Do not cite a line number you did not open.**
The first sweep found **sixteen** wrong anchors across nine rows of §1 and four places in §4/§5,
and **two of the groups were wrong as a block — every member off by exactly one line**, which is
the signature of a number copied out of a diff hunk header rather than counted off an open file.
Sixteen is not a handful, and they were spread across the document rather than concentrated in one
stale corner, which says the anchors here were being written **by inference**.

So: open the file and count. Do not carry a number forward from a previous version of this
document, from a brief, or from a stack trace. **If a line cannot be opened, cite the symbol
instead** — `EvalSuite.survived`, `FireDamage.LAVA_CONTACT_DAMAGE`, `Enclosure.Verdict` — and say
plainly that the line number was not verified. A symbol survives a refactor. A stale line number
is a confident wrong answer, and it is indistinguishable from a right one to every reader except
the person who opens the file.

**This rule says how to write a correct anchor. §6.13 and §6.14 say which one is most likely to
have stopped being correct** — and the second sweep's fifteen were *all* in the section whose
files the current waves were editing (§1g.1).

**6.11 Do not call it a join test unless removing either half breaks it.**
`held == sheltered && floorHeld` is false in three of the four combinations and true in one, so
a test that only ever asserts `held` cannot tell *"sheltered and hurt"* from *"exposed and
untouched"* — and either of those two runs is a way for one half to do all the work while the
suite stays green. `TheNightShelterAndTheHealthFloorAreJoinedTest` therefore asserts the whole
four-cell table, and its acceptance criterion is a **pair of mutations that fail different tests
inside the same file**: making `Enclosure.Verdict.enclosed()` return true gives **12**, and
reverting `neverBelowNorthStar` to bare `minimumHealth() > 0` gives **4**.

The reason this is a rule and not a footnote: **the criterion is invisible the moment the test
exists.** A green join test looks exactly like a real one. The only thing that distinguishes them
is an experiment somebody ran and recorded, and nobody is going to re-derive it from reading the
test. **A test that has never been seen red is not evidence, and a join test that has never had a
half deleted is not a join test.**

**6.12 Do not cite a test that does not read the thing the row is about.**
This is the error §1 exists to stop and the one a reader cannot detect for themselves. A row is
a receipt or it is a claim, and it is a claim the moment the named test opens a different file
than the row describes. When you add a row, open the enforcing class and name what it reads.

**6.13 Do not re-open a section you did not touch.**
This pass opened **every** anchor in this document. **Fifteen were wrong and all fifteen were in
one section** (§1g.1). The first sweep's sixteen were spread across nine rows and four sections.
**A sweep that re-reads the *moving* part finds the same thing a sweep that re-reads everything
finds, at a fraction of the cost.** Re-open the newest section first and hardest — and treat an old
row as **evidence about itself** rather than as evidence about the tree. §6.10 says how to write a
correct anchor; this says which anchor is most likely to have stopped being one.

**6.14 An anchor into a file under active change is a half-life, not a citation.**
**Nine of the fifteen wrong anchors in §1g.1 were `EvalSuite.java` line numbers, and every one
moved because a task was added below it.** The number was not wrong when written; it was correct
with an expiry date, and **nothing recorded the expiry**. So: when a row cites a file the current
wave is editing, **cite the symbol** — `EvalSuite.survived`,
`EvalSuite.T20TheDirectLineIsLava`, `SimWorld.minimumHealth()` — and add the line only as a
convenience. This is §6.10's fallback made mandatory in the one case where §6.10's fallback is
*always* needed, because a file being edited today is a file whose lines are all still moving.

**6.15 Do not publish a line count.**
Not a code line count, not a test count, not an audit-report count. **A line count is the one
number in this document with no symbol to fall back to: every other citation degrades to
`Class.member` when the line moves, and a bare count degrades to nothing but a wrong number that
still looks like evidence.** §4 item 4 already learned this for `GoalPolicy` — *"its line count is
deliberately not given"* — and §1f row 39 re-introduced the same mistake two sections later, in the
same document, on the same day. Cite the class; `SurvivalDamage` is unambiguous without a length.

**6.16 A row that says a hole is open must be re-read when something closes it, and "open" is not
a stable verb.**
§1d row 30 and §4 item 4 both said the eval had **no seam** and therefore **no negative control of
its own**, while §1f had already credited one. **A claim of absence is the hardest kind of rot to
notice, because the fix is invisible from the document and the sentence still reads fluently** —
there is no diff to review and nothing to contradict it. When a wave closes something, grep this
file for the absence as hard as you grep the tree for the presence.

**6.17 Do not sum a test directory without joining it against the source tree first.**
`core/target/surefire-reports` held **326** XML files summing to `2028`; **three** had no source
anywhere under `core/src/test/java`, and the live total is **2025** (§0). **A report with no
source is a deletion, not a test.** "Clear the directory" is not a rule anybody can follow after
somebody else ran a probe. **And §0's command builds the pair** — `-pl core` alone resolves a
stale `client` jar, because `core/pom.xml:55-60` declares it `provided`, and a `NoSuchMethodError`
on a method that exists in the source is a stale artifact rather than a defect.

**6.17 now has a command.** `scripts/test-census.py` performs this join, over every module and
both report populations, and refuses to print a number when a ghost or an uncollected class is
present. `docs/agency/test-census.md` is its convention, and §1 there is why the surefire and
failsafe populations are never added into one figure. Read that instead of re-deriving the join
by hand — hand-deriving it is how this file's own `2025` went stale three times.

