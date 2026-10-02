# Guarantees and instruments: what this kernel now holds, and what pins it

2026-10-02. Written after a day of auditing-and-fixing, not before one. Every row below
carries the `file:line` that implements the guarantee and the **exact test class** that
goes red if it regresses — or the word **unenforced**, which is the only honest entry when
no such test exists.

**Why this file exists at all.** This repository has produced the same defect shape
repeatedly: a capability documented as doing something whose producer is absent, renamed,
or never asked a question. A patch that armed and changed nothing. A security gate whose
`require()` was an empty method body. A schema that refused a verb the actuator had. Tool
descriptions naming arguments that did not exist. An interface method documented "never
null" that returned a one-frame-stale value to zero callers. **A movement-trace regression
test that surefire never collected.**

So the job of this document is not to describe the design. It is to make each guarantee
**checkable rather than believable**. A row without a test class is a claim; a row with one
is a receipt. §1 separates them deliberately and does not blur the line.

**Language.** English, unlike the rest of `docs/`. The session's own reports are English and
this file is a distillation of them; where the two disagree, the reports under
`.ai-notes/` are the longer form and this file is the shorter one.

**What is not here.** `.ai-notes/docs/audits/` holds 57 audit reports, 34 of them dated
2026-10-02. Those produced these changes, and the directory is gitignored
(`.gitignore:51`, the `.ai-notes/` line), so none of them will travel with the repository.
This file is the part that will.

---

## 0. How to check anything in this document

One command, and nothing in §1 that claims enforcement survives without it:

```bash
export JAVA_HOME='D:\Software\Developer\jdk\25'
./mvnw.cmd -B -ntp -pl core test
```

Run it **serially**. Two concurrent runs against one `core/target` have already collided
once this session: `EphemeralSynthesizerTest` killed the surefire fork and the run aborted
at 97 of 124 tests with `BUILD FAILURE`, and those numbers had to be discarded.

Read the result from `core/target/surefire-reports/*.xml`, not from the console line. The
console total and the on-disk reports disagreed until the report directory was cleared,
because probe runs leave stale entries in it.

**The number as of this writing.** The on-disk reports aggregate to **293 classes /
1845 tests / 0 failures / 0 errors / 1 skipped** — but that is *not* one coherent run.
292 of those reports span 09:05:44–09:07:17 and one
(`ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest`)
was rewritten at 09:15:38 by a later targeted invocation. The coherent aggregate is
**292 classes / 1839 tests / 0 failures / 0 errors / 1 skipped**.

One collected-by-name test file has **no report at all**:
`ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` (5 `@Test` methods,
written 09:17:37, after the last full run). So `1845` and `1839` are both **stale** relative
to the tree, and the real current figure is at least `1845 + 5`. **Re-measure; do not quote
either number.** That instruction is not hedging — this repository has pinned a test count
in a document and had it be wrong within one round, twice (§0.1 of `branch-topology.md`
records it for commit counts; this is the same failure with a different subject).

> **A reader who wants the strongest statement available should note what is missing:**
> the guarantee in §1 row 12 (`act_status` reads back what the walk decided) is enforced by
> a test file that **exists and has never been executed by a suite**. Its logic is readable
> and its sibling rows are green, but "written" is not "verified". Treat that row as
> **unverified-pending-first-run** until a full `./mvnw.cmd -pl core test` has executed it.

---

## 1. What the system now guarantees that it did not before

Each row: the claim, where it is implemented, the test class that fails on regression, and
how many test methods that class actually has (read from the surefire XML, not counted by
eye). **UNENFORCED** means no test in the repository fails if the guarantee stops holding.

### 1a. Trust and patching

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 1 | The compat layer reports **bytes actually changed**, not only "armed" | `compat/CompatEngine.java:472-479` counts `transformRuns` and `targetsChanged` on reference inequality; record `ApplyRecord` at `:73-78` | `CompatEngineAppliedObservableTest` | 7 |
| 2 | `list_compat_patches` publishes that count and classifies a patch as **inert** (ran, changed nothing) | `compat/CompatTools.java:99-140`; `targetsChanged` row at `:123`; inert at `:134` | same as row 1 | 7 |
| 3 | SEC-1 (`level://` path traversal) is armed **and** changes the real compiled vanilla class | `compat/patches/LevelSchemePathGuardPatch.java`; canonical-path containment test, documented at `:475` and called by the patched bytecode as `INVOKESTATIC` | `LevelSchemePathGuardPatchArmingTest` | 7 |
| 4 | The root private key is not silently overwritten — the refusal lives in the **write function**, not in `main()` | `compat/tools/RootCeremonyCli.java:257-262` | `RootCeremonyRefusesToClobberTheRootKeyTest` | 4 |
| 5 | The production `AccessGate` enforces L4 and L5 rather than no-oping | wired at `McpCore.java:339` (`new MonitorAccessGate(engine)`); body at `se/MonitorAccessGate.java:92-131` | `MonitorAccessGateIsLiveTest` | 14 |

Three notes on this table, because the shape matters more than the rows.

**Row 1 is the load-bearing one.** Before it, the only signal was `armed`, and
`GlClampToEdgePatch` matched an `LDC` where javac emits `SIPUSH` — so it armed, its
signature verified, and it returned `null` on every real load. The log said "N patch(es)
armed" and the log was telling the truth about the wrong thing. `targetsChanged` is what
separates "registered", "armed", "ran", and "changed something", and they are four
different states. Row 2's inert classification is the specific answer to the specific
failure: a patch with zero runs is not inert, it simply has not been reached yet.

**Row 5's fix moved a `MonitorAccessGate` into `McpCore`, and `AllowAllGate` still exists
with an empty `require()` body** (`se/AllowAllGate.java:16-18`). That is correct: it is the
dev-default, and 15 test classes construct it deliberately so the thing under test is not
the gate. What is no longer true is the claim that the *production* wiring used it.
`MonitorAccessGateIsLiveTest` reaches the `MmAccess` instance `registerBuiltins` built and
asserts both terms of the AND separately (L5 capability, L4 privilege), plus that the
subject is re-read per call — which is what separates a live gate from one snapshotted at
wiring time.

**Row 4's placement is the whole fix.** The guard was in `main()`, so any new call site
bypassed it. It is now in `writeOwnerOnly`, the only function that writes the key.

### 1b. The act layer: decisions and grades

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 6 | The MOVE path propagates the belief grade to the record on **both** the terminal and the running path | `drivers/act/MoveApplier.java:388` (terminal), `:446` (running) — four `withBelief` sites in `core/src/main`, two of them in `MoveApplier` | `ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest` | 6 |
| 7 | "Nobody looked" and "somebody looked and could not see" are **different values** at the record boundary | `drivers/act/SlotRecord.java:91` and `drivers/act/ActOutcome.java:58` — both `UNGRADED` are now `null`, with `mayActOn()` as the door | same as row 6 | 6 |
| 8 | A route refusal that counted unread cells is not the same failure as one over impassable terrain, **on the record** | `drivers/plan/RoutePlanning.java:169-183` (`noRouteMessage`, graded on `unreadCells`), `:198` (grade travels on the `ActOutcome`) | row 6's test is the seam test; the type-level half is `ARouteRefusalOnUnreadTerrainIsNotTheSameFailureAsOnImpassableTerrainTest` | 6 + 8 |
| 9 | The dig's completion says whether this client **saw it, inferred it, or could not look** | `drivers/act/DigController.java` (`withoutUpgrade` grades a stale claim rather than restating it) | `ADigCompletionSaysWhatItActuallySawTest` | 7 |
| 10 | Grading the crosshair does not cost a world read | `ActActuator.mouseOver()` now returns `Graded<Target>` (`drivers/act/ActActuator.java:64`; the only production implementation is `LivePlayerActuator.java:74`) | `ABeliefOnADigCostsNoWorldReadTest`, `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 3 + 4 |
| 11 | The crosshair's javadoc no longer claims "never null" for a value traced against the previous frame | the same `ActActuator.java:41-63` javadoc, which now states the staleness and names the two writers of `mc.objectMouseOver` | `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 4 |
| 12 | A model's `act_status` row carries what the walk **decided** (`tactic.givenUp`) and how well-earned the message is (`belief`) | `drivers/act/ActStatus.java:42-51` (`SlotStatus` gains `MoveTactic tactic`); projected at `drivers/act/ActRuntime.java:648-662` (`:659`); emitted at `drivers/action/ActTools.java:1002-1011`; named in the description at `:907-909` and `:973-980` | `ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` — **exists, has never been executed by a suite** (no surefire report; see §0) | 5, unrun |
| 13 | A route's terminal record names what the walk actually spent, not what a controller computed | `MoveApplier.stamp` at `MoveApplier.java:474-480` writes onto the `RouteIntent`; `ActRuntime.java:659` reads it back | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest` | 4 |
| 14 | A route that falls on its **first** tick still publishes a tactic | same stamp; the fall-guard branch of `MoveApplier.apply` | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest.aRouteThatFallsBeforeItsMachineHasRunStillPublishesATactic` | (within the 4) |
| 15 | A walk that spent nothing says so on the record it carries | `AWalkThatSpentNothingSaysSoOnTheRecordItCarriesTest` reads `((RouteIntent) rec.intent()).tactic()` | same class | 3 |
| 16 | The terminal tactic distinguishes "the clock ran out" from "every recovery failed" | `MoveTactic.GivenUp` (`drivers/act/MoveTactic.java:230`; `LIMITS_REACHED` and the `OUT_OF_TICKS_AFTER_A_LANE` split are separate constants) | `TheTerminalTacticMustSayWhatTheWalkSpentToGetThereTest`, `AWalkThatRanOutIsNotAWalkThatSpentEveryOptionTest` | 4 + 4 |
| 17 | `ARRIVED_AFTER_A_LANE` is reachable on a production record | `NavController.arrivalGivenUp()` at `drivers/act/NavController.java:749-753`, reached from the arrival branch at `:576` | `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` | 3 |
| 18 | Re-stamping a walk every tick does not free the channel it is standing on | `MoveApplier.stamp` allocates a fresh `RouteIntent` per tick via `withTactic`, so `ActRuntime.sameIntent` is goal-keyed, not identity-keyed | `AReStampedIntentMustNotFreeTheChannelItIsStandingOnTest`, `AReStampedWalkIsStillOursToThePlanTest` | 1 + 2 |
| 19 | A plan's supersession check reads the **lease**, not object identity, and is ANDed with the goal test at all three sites | `ActPlanInterpreter.takenByAnother` at `drivers/act/ActPlanInterpreter.java:244-247`; call sites `:111`, `:217`, `:269` | `APlanTeardownMustNotCancelTheRacingActSetTest`, `TwoConsumersOfOneSlotCannotSilentlyOverwriteTest` | 5 + 14 |
| 20 | A wedged body sidesteps or **names what is in the way** | `NavController.JAM_REACH = 0.2D` at `:87`; the ranking that keeps a measurement above a guess | `AWedgedBodySidestepsOrSaysWhatIsInTheWayTest`, `AFlushJamIsNamedAtEveryCellOffsetTest` | 5 + 5 |

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

**Row 12 is the only row in this table that is written but unrun.** It is listed here rather
than omitted because its existence is the reason `belief` and `tactic` are reachable from a
model at all — the projection at `ActTools.java:1002-1011` and the description at `:907-909`
have no other receipt. But `ActToolsTest.everyFieldActStatusEmitsIsNamedInItsDescription`
(`ActToolsTest.java:426-440`) only asserts that the description **names** each emitted key,
not that the value is correct, so until the new test runs, row 12 is a design claim with a
structural guard, not an enforced guarantee.

**Row 17 is a dispute that was resolved by measurement, not by argument.** Two audits
disagreed: one argued `ARRIVED_AFTER_A_LANE` was unreachable (142 completed side-steps, zero
arrivals across 2840 worlds), the other refuted it with a deterministic three-tick trace.
The **deletion did not proceed, which was the right call** — a deletion must not rest on a
geometric argument that has already been measured wrong once. The burden is on whoever
deletes it to re-run the counterexample.

**`tactic` is null for every non-`go_to` slot, including `walk_straight`, and that is
honest rather than missing.** `MoveApplier.stamp` stamps only a `RouteIntent`
(`MoveApplier.java:476-479`): a `NavIntent` has no field to stamp into and a raw
`MoveIntent` has no tactic at all. Only a route has somewhere to record a decision.

### 1c. The tool surface

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 21 | The DROP verb exists end to end: schema, intent, controller, **re-read confirmation** | schema enum `drivers/action/ActTools.java:302`; `InteractIntent.Kind.DROP` and `dropStack` at `drivers/act/InteractIntent.java:107,242`; `drivers/act/DropController.java:39` | `DropControllerTest`, `DropReachabilityTest`, `InteractApplierDropRoutingTest`, `ADropIsReachableThroughTheRealToolBoundaryTest` | 11 + 7 + 4 + 6 |
| 22 | The drop confirmation is "the slot is EMPTY", not "the click returned" | `DropController.isDone()` at `:62` | same as row 21 | (within the above) |
| 23 | A misspelled `sections` entry is an **error naming the offender**, not a silent omission | `drivers/world/WorldViewCapture.unknownSections` at `:61-72`, consulted at the tool boundary | `AnUnknownSectionNameIsRefusedTest` | 4 |
| 24 | Every key `act_status` emits is named in its description, and the vocabulary is **derived from the emitted map** | description at `ActTools.java:907-980`; the gate at `ActToolsTest.java:426-440` | `ActToolsTest.everyFieldActStatusEmitsIsNamedInItsDescription` | (within 34) |
| 25 | Every interact/look argument is both **read by the parser** and **named in the description**, in both directions | `ActToolsTest.java:544` (interact), `:578` (look) | same class | (within 34) |
| 26 | A tool description naming a tool that does not exist is a build failure | registry walk in `io/transport/DescriptionsNameToolsThatExistTest` | `DescriptionsNameToolsThatExistTest` | 8 |
| 27 | A tool description's legend matches the values the handler can actually send | per-tool checks in `io/transport/ToolDescriptionsMatchTheirBoundsTest` | `ToolDescriptionsMatchTheirBoundsTest` | 7 |
| 28 | A hazard is readable **before** the walk arrives, and survives on the terminal tick | `MoveApplier.java:386` (terminal), `:428`, `:445` (running) | `AHazardIsReadableBeforeTheWalkArrivesTest`, `TheEarlyHazardWarningReachesActStatusTest` | 9 + 6 |
| 29 | A hazard is never guessed from a cell that could not be read, so an unloaded chunk reports **no hazard** rather than a bottomless pit | the read-jam ranking in `NavController.readJam`; described at `ActTools.java:957-958` | `AHazardIsReadableBeforeTheWalkArrivesTest` | 9 |

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

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 30 | A full bag makes a room move before it refuses to dig, and the protection is a **ledger** not a guess | `eval/GoalPolicy.chooseSlotToThrow` / `makeRoom` (test-only; see §4) | `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest` | 5 |
| 31 | An eval filter matching zero tasks is an **error**, not an empty PASS | `eval/SelfPlayEval.runAll(List)` at `SelfPlayEval.java:38`, the refusal at `:56` | `AnEvalFilterThatMatchesNothingIsNotASuccessTest.aFilterThatMatchesNoTaskIsRefusedRatherThanReportedAsSuccess` | 2 |
| 32 | The signing script derives the classpath separator **from the JVM that will consume it** | `scripts/sign-patch.sh:70-88` | **UNENFORCED** — see below | — |

**Row 30's ledger is the thing standing between the agent and a false pass: with
`keep.addAll(owedItems())` removed, `got` flips from false to true** — the policy reaches the
goal by eating its own planks. That mutation is the evidence the protection is real. What is
*not* enforced is that the eval could catch a broken policy at all: `GoalPolicy` is
test-only, has zero references from `core/src/main`, and cannot be substituted
(`public final`, constructed directly at seven sites). There is **no seam**, so a
deliberately broken policy cannot be run and the suite has **no negative control** — 23 tasks
with world-fact assertions, two ever observed red, none ever observed red *because the
policy was broken on purpose*. The `alive` conjunct is a second, separate hole; see §4.

**Row 32 is UNENFORCED, and it is listed that way rather than deleted because the shape is
the point.** The fix is real and it is in the script: `sign-patch.sh` asks the JVM for
`File.pathSeparator` instead of guessing, which is what made the signing step work on
Windows. But **no test in the repository reads `sign-patch.sh`.** The nearest thing,
`BuildScriptContractTest`, asserts properties of `build-clang.sh` — a different script in a
different directory — and its one test method is about `JBRINC`. This row is therefore a
correct change with **zero enforcement**, and §3.1 is the argument that this is the exact
category of defect this document exists to stop being invisible. Fixing it costs one small
test: read the script, assert it derives the separator rather than hardcoding one, and
assert that assertion in **both** directions — a script that hardcodes `;` on Windows must
fail it, or the guard is a formality in the same way an allowlist of one entry is.

---

## 2. The measurement instruments, and what each one pins

Four instruments. For each: what it would catch, and — the half that matters more — what it
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

asserted as **exact equality**, not a bound (`:76`, `:93`), plus a counting-view equality
for the planner's half (`:131`) and a bytecode assertion that `BlockProbe.probe` contains
exactly one `INVOKESTATIC BlockProbe.at` and zero world-reading calls of its own (`:166`).

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

### 2.3 The belief allowlist

`core/src/test/java/net/marcloud/mcp/core/util/GradedCallSitesAreAllowlistedTest.java`

Scans `core/src/main` for both spellings of an OBSERVED claim — `Graded.observed(` (bare or
qualified) and `Belief.OBSERVED` — and fails on **one more or one fewer** than the three
allowlisted files (`ALLOWLIST` at `:67-91`; both directions at `:111-132`; size, file
existence and a reason on every entry at `:143-159`).

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

Emitted at `drivers/action/ActTools.java:1002-1011`, described at `:907-909` and `:959-980`.

Per slot: `slot`, `phase`, `hasIntent`, `intentKind`, `ticksActive`, `message`, `heldBy`,
`hazard`, `belief`, `tactic`. `belief` is `null` or one of `OBSERVED` / `INFERRED` /
`UNKNOWN`; `tactic` is `{forward, strafe, jump, yawChange, lane, givenUp, describe}` or
`null`.

- **Catches:** a caller that has to substring-match a prose `message` to learn *why* a walk
  ended. Before this, a model could not tell a walk that spent a lane from one that spent
  nothing, which is the whole of `NavHazard`'s argument for a typed field applied to a field
  that already existed.
- **Blind to — and this is the important one:** exposing a value is not the same as a model
  *using* it. The cheapest falsifier is to delete the two fields and re-run: **if no test
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

---

## 3. The surefire collection gap — a trap for the next person

**Read this before adding a test.**

Surefire's default includes match `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`,
`**/*TestCase.java`. This repository's convention for contract tests is
`AThingDoesSomething.java` and `TheThingIsSomething.java` — **which surefire does not
collect.** `core/pom.xml` configures no `<includes>`, so the defaults apply.

Measured on this tree, 2026-10-02:

| # | measurement | value |
|---|---|---|
| 1 | `.java` files under `core/src/test/java` | **316** |
| 2 | matching surefire's default includes | **294** |
| 3 | not matching | **22** |
| 4 | of the 22, files carrying at least one `@Test` | **7 — and all 7 end in `LiveIT`** |
| 5 | of the 22, files carrying **zero** `@Test` | **15** |

### 3.1 What was fixed

**29 test files were silently uncollected. Seven real contract tests were renamed** to
`…Test`, and each of the seven now has a surefire report on disk. The 29 is this session's
arithmetic over this tree, not a remembered number: **22 files still do not match** (the
table above) **plus the 7 that were renamed** = 29. Every one of the 29 was a file surefire
had never opened.

| class | tests |
|---|---|
| `ABlockLastsUntilTheCallerReleasesItTest` | 3 |
| `ACritIsSwungInTheFallingWindowAndNotOnFlatGroundTest` | 4 |
| `SneakingStopsAtTheEdgeAndWalkingOffDoesNotTest` | 1 |
| `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest` | 4 |
| `TheWalkThisSliceChangedMovesAsItDidBeforeTest` | 1 |
| `TheFallDamageNumberIsVanillasTest` | 4 |
| `OpenPauseMenuIsHonestAboutNotBeingThereTest` | 2 |

The suite moved **1803 to 1822** from the rename (+19) and **1822 to 1825** from the
`ARRIVED_AFTER_A_LANE` slice that landed alongside it (+3). The commonly-quoted "1803 to
1825" therefore covers two changes, not one; the rename alone is +19.

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

**The 15 helpers.** `BodySim`, `FakeActuator`, `FakeWorld`, `CraftBench`,
`FakeCraftWindow`, `EvalHarness`, `EvalSuite`, `GoalPolicy`, `SelfPlayEval`, `SimBody`,
`SimWorld`, `SimMob`, `SimCraftWindow`, `LiveGameGate`, `DeepAccessProtectedBase`. Each
carries **zero** `@Test` methods. **Do not rename these to `*Test` either** — a class with no
`@Test` that matches the include pattern produces an empty report and, on some surefire
configurations, a failure that looks like a build problem.

### 3.3 The rule

> **A contract test goes in a file named `…Test`. A test helper does not.**
> The name is not a style preference. It is the only thing that decides whether the suite
> ever asks the file a question.

Adding a test named `AFooDoesBar.java` produces a green suite and a test that has never
run. That is the exact failure this session spent itself removing, and it is the failure a
green suite cannot report — which is why it is written here rather than left to a review.

---

## 4. What remains unmeasured

Stated plainly, because the list is short and the claims behind it are large.

**The self-play eval has 23 tasks** (`grep -c 'implements Task' eval/EvalSuite.java` = 23).
It is a high-quality integration and composition gate over the production controllers:
world-fact assertions across locomotion, the input-layer timing gate, hazard reporting,
inventory transfer, two container models, and the placement chain, with mutations run in both
directions. **It is not a north-star measurement, and no task in it bears on one.**

The north star names four things. Here is what exists for each:

| criterion | measured? | what exists instead |
|---|---|---|
| survive a shelter / have a shelter by dawn | **No.** No task builds an enclosure; there is no "is the player enclosed" predicate anywhere in the substrate | nothing |
| health never below 18 | **No.** `18.0` appears **zero** times in the eval tree | `w.health() > 0.0D`, unfalsifiable in the tasks that use it |
| a box standing at dawn | **No.** A chest does not exist in this substrate — `SimWorld.KNOWN_GAPS` (`SimWorld.java:149`) says so: *"a chest, a furnace and an enchanting table all still return a plain placement refusal"* | nothing |
| a whole night | **No, and not expressible.** The substrate has no clock | nothing |

Four verified facts behind that table:

1. **`SimWorld` has no clock.**
   `grep -c 'worldTime\|timeOfDay\|isNight\|dayTime\|isDay\|24000' SimWorld.java` returns **0**.
   `ticks` is a bare counter with no day mapping.

2. **The clock exists in the observation layer and reaches no controller.**
   `EnvView` carries `worldTime`, `timeOfDay` and `daytime`
   (`drivers/world/EnvView.java:18-19`), and
   `grep EnvView core/src/main/java/net/marcloud/mcp/core/drivers/act core/src/main/java/net/marcloud/mcp/core/drivers/plan`
   returns **0 matches**. `EnvView` is referenced only by `WorldView`,
   `WorldViewCapture`, `WorldViewDiff` and `WorldViewJson` — the observation surface. **A
   model can read the time of day. Nothing that decides anything can.**

3. **No damage source is reachable from a task.** `SimWorld.health` has three writers:
   `atHealth` (fixture only), `damagePlayer` (whose only caller is `SimMob.tick`), and an
   `advanceUse` food heal that **raises** health. `damagePlayer` needs a `SimMob`, and
   `spawnMob` is called from **zero** `EvalSuite` tasks — its four callers are all in
   `AMobThatActsIsMeasuredInTheWorldTest`, which is not one of the 23. So
   `w.health() > 0.0D` is the constant `20.0 > 0.0`: it appears at **seven** sites
   (`EvalSuite.java:723, 1325, 1444, 1536, 1640, 1757, 1842`), and an eighth site at `:946`
   conjoins it with `w.health() == 3.0D`, which is equally unfalsifiable for the same
   reason. **The suite contains no run in which the player loses health at all**, and
   `alive` reads as if it were evidence. A constant-true assertion in a suite whose charter
   is "every verdict is a world fact" is worse than no assertion.

4. **`GoalPolicy` is 1317 lines, has zero references from `core/src/main`, exposes one action
   family, and cannot be substituted** — `public final`, constructed directly at seven sites.
   Its own javadoc says what it measures (`GoalPolicy.java:59-64`): *"given a plan, do the
   production controllers execute it against a real world? A pass is evidence the controllers
   compose; it is NOT evidence a model would produce this plan."*

### 4.1 Two smaller items, verified this session

- **`SimWorld.droppedOntoFloor()` has zero readers in the entire repository**
  (`SimWorld.java:247,1070,1075`). Its sibling `thrownByPlayer` carries five assertions in
  `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest`. The split is right and only one half
  is load-bearing. By this repository's own stated test for decoration — *"a case that
  changes no behaviour is decoration"* — the other half is decoration.
- **`GoalPolicy.obtain`'s recipe-cycle guard (`GoalPolicy.java:204`) has zero coverage.**
  `grep -rn "recipe graph cycles" core/src` returns that line and nothing else. The T19
  fixture fix removed the suite's only exerciser of it. That fix was correct; the cost is
  that nothing in the repository noticed the coverage disappearing, **because nothing
  measures coverage.**

### 4.2 Half-done, stated as half-done

- **The crosshair's grade has zero production consumers.** `ActActuator.mouseOver()` is
  declared at `:64` and implemented at `LivePlayerActuator.java:74`; `grep -rn 'mouseOver()'
  core/src/main` returns those two lines and no caller. Rows 10 and 11 of §1b are honest
  about what it is; they do **not** make it used. This is one of at least nine instances
  this session of "documented as existing, actually absent", and the fix here changed the
  claim rather than building the consumer. **Building the consumer is the unfinished half.**
- **The plan-step verdict is still prose.** `ActPlanInterpreter.step` aborts on every
  `FAILED` step with `"step N failed: " + rec.message()` and no typed verdict carrying the
  belief and the `givenUp`. There is no `StepVerdict` type in the tree. The three-way
  verdict exists one package over as a reference shape (`InteractController.awaitCritWindow`
  at `:197-214`) — so the pattern is finished and local, and only its application is
  missing.

---

## 5. The failure shape that recurred, and the mechanisms now against it

**Not a postmortem — an operating note.** The shape is *"documented as doing something,
whose producer is absent, renamed, or never executed."* It appeared at least nine times in
one session. Here is what is now in place, and for each, what it catches **and what it
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

**Catches:** silent layer growth, and allowlist rot.
**Cannot catch:** a site that claims `INFERRED` when it should claim `OBSERVED`. The
allowlist governs the strong claim only; nothing checks that the *weak* grades are earned
either.

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
exist, the cycle guard whose last exerciser a fixture fix removed, and the crosshair with
zero consumers were all present from the moment they were written. Each was caught by a test
that **reads the far side of a seam**, not by a test that watches for change:

- `ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest`
  exists because the type-level test could not see the seam.
- `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` exists because two audits
  disagreed and the disagreement was only resolvable by driving production.

**The generalisable form: a test that asserts a property of a component is silent about the
property of the seam between components.** Two of the four were caught by exactly that question
("and who reads *that*?"); the other two were caught by reading the tree, which is why §4.1 is
a list rather than a green suite.

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
It would collect the 15 helpers (§3.2) along with the seven real tests. Fix the names.

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
The counts in §0 and §3 were accurate when written and §0 says so explicitly. Re-measure
them; the instruction is in the document for the same reason the instruction is in
`branch-topology.md` §0.1.
