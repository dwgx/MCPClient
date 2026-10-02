# Guarantees and instruments: what this kernel now holds, and what pins it

2026-10-02. Written after a day of auditing-and-fixing, not before one; refreshed the same
evening after the clock and grading waves landed. **38 guarantees**, each carrying the
`file:line` that implements it and the **exact test class** that goes red if it regresses —
or the word **unenforced**, which is the only honest entry when no such test exists.

**Every `file:line` in this file has been opened.** Nothing here is quoted from a report. When
a line moved, the citation moved with it, and the corrections are listed in
`.ai-notes/docs/audits/2026-10-02-wave10-guarantees-refresh.md`.

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

**What is not here.** `.ai-notes/docs/audits/` holds 61 audit reports, 38 of them dated
2026-10-02. Those produced these changes, and the directory is gitignored
(`.gitignore:56`, the `.ai-notes/` line), so none of them will travel with the repository.
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

**The number as of this writing: 300 classes / 1883 tests / 0 failures / 0 errors / 1
skipped.** It is one coherent run, and the directory's total is **not** the same number: the
directory holds **301** XML files and a naive sum says `1884`, wrong by exactly one. One
report, `TEST-net.marcloud.mcp.core.drivers.action.DiagTest.xml`, carries an mtime of `09:19`
while the other **300** all carry `10:35`; it is left over from a probe run of a class that
**does not exist in the tree** (`core/src/test/java/.../DiagTest.java` is absent).
**Group the reports by mtime before summing them.** That one file is why §0 is a section and
not a sentence: a test count is a claim about a set of files, and the set has to be named.

**And the number moved while this section was being written, which is the honest reason it is
dated rather than stated as a constant.** It read **299 classes / 1877 tests** off a `10:11`
run, and by the time the file was finished a sibling had added
`core/src/test/java/net/marcloud/mcp/core/eval/ScratchLavaProbeTest.java` (6 tests) and the
suite had been re-run at `10:35`. The six are the whole difference. Anyone who re-measures and
gets something else should **diff the report directory**, not assume the tree is broken.

**The same bijection checked from the other end.** `core/src/test/java` holds **300** files
matching surefire's default includes (§3), and there are **300** reports for them, one for one,
with **zero** files lacking a report. The earlier warning in this section — that
`ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` existed and had never been
executed — is **resolved**: it is collected, it has a report, and its five tests are green. Row
12 is no longer a design claim.

### 0.1 Where the count came from, and which of its legs are evidence

The total is measured. The path that got there is not, in three of its seven steps, and the
difference is worth printing rather than rounding:

| step | delta | what backs the number |
|---|---|---|
| baseline before the collection gap was closed | — | historical; **not** re-derivable from the reports now on disk |
| the 7 renamed contract tests (§3.1) | **+19** | **measured**: `3+4+1+4+1+4+2`, each read off its own report |
| `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` | **+3** | **measured**: its report says `tests="3"` |
| belief-consumers' three classes | +14 | historical: measured on disk at the time, and the coherent `1839` it produced |
| wave 8 to the grading wave's baseline | +11 | historical |
| **the clock wave** (§1e rows 33-36) | **+21** | **measured**: its four new classes carry `6 + 4 + 5 + 6` on their own reports |
| **the grading wave** (§1e rows 37-38) | **+6** | **measured**: `ABeliefCensusOverTheActScenariosTest` is `7`, less the one `@Test` method the wave **deleted** from `ADigCompletionSaysWhatItActuallySawTest` (report now `6`; this document recorded `7` before) |
| **wave-9 subtotal** | **+27** | 1850 + 21 + 6 = **1877**, which is what the reports said at `10:11` |
| a sibling's probe class, since | **+6** | **measured**: `ScratchLavaProbeTest` is `tests="6"` on its own report |
| **total, at `10:35`** | **+33** | 1850 + 21 + 6 + 6 = **1883**, which is what the reports say now |

**The two waves are not `+16` and `+11`.** They are `+21` and `+6`, and the total is the same
either way, which is exactly why the split is worth getting right: a decomposition whose parts
can be redistributed without changing its sum is a decomposition nobody has checked. **Five** of
the eight deltas above are read off a report; the other three are marked historical and are not
laundered into evidence by being in the same table.

**And this table was written before the last row happened, which is the cleanest possible
argument for §6.9.** The document was refreshed against a `10:11` measurement of 1877, and
1877 was correct when it was written. A sibling added six tests, the suite was re-run, and 1877
became wrong **without a single character of this file changing**. The last two rows are the
repair, and they exist to be read as an example rather than as an arithmetic claim.

**Re-measure anyway.** This repository has pinned a test count in a document and had it be wrong
within one round, twice (§0.1 of `branch-topology.md` records it for commit counts; this is the
same failure with a different subject).

---

## 1. What the system now guarantees that it did not before

Each row: the claim, where it is implemented, the test class that fails on regression, and
how many test methods that class actually has — **read from that class's own surefire XML,
not counted by eye and not carried from a previous version of this file**. **UNENFORCED**
means no test in the repository fails if the guarantee stops holding. **There is currently no
such row**: the last one, row 32, was closed by `scripts/test_sign_patch.py` and the story of
how is kept under the row.

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

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 6 | The MOVE path propagates the belief grade to the record on **both** the terminal and the running path | `drivers/act/MoveApplier.java:388` (terminal), `:441` and `:459` (running) — five `withBelief(` call sites in `core/src/main`, three in `MoveApplier` and two in `InteractApplier` (`:58`, `:75`) | `ARouteRefusalThatCountedUnreadCellsIsNotTheSameFailureOnTheSlotAsOnTheOutcomeTest` | 6 |
| 7 | "Nobody looked" and "somebody looked and could not see" are **different values** at the record boundary | `drivers/act/SlotRecord.java:89` and `drivers/act/ActOutcome.java:146` — both `UNGRADED` are now `null`, with `mayActOn()` as the door | same as row 6 | 6 |
| 8 | A route refusal that counted unread cells is not the same failure as one over impassable terrain, **on the record** | `drivers/plan/RoutePlanning.java:202-216` (`noRouteMessage`, graded on `unreadCells` at `:210-215`); the grade and the count both leave on the `ActOutcome` at `:234-237` | row 6's test is the seam test; the type-level half is `ARouteRefusalOnUnreadTerrainIsNotTheSameFailureAsOnImpassableTerrainTest` | 6 + 8 |
| 9 | The dig's completion says whether this client **saw it, inferred it, or could not look** | `drivers/act/DigController.java` (`withoutUpgrade` grades a stale claim rather than restating it) | `ADigCompletionSaysWhatItActuallySawTest` | 6 |
| 10 | Grading the crosshair does not cost a world read | `ActActuator.mouseOver()` now returns `Graded<Target>` (`drivers/act/ActActuator.java:64`; the only production implementation is `LivePlayerActuator.java:75`) | `ABeliefOnADigCostsNoWorldReadTest`, `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 3 + 4 |
| 11 | The crosshair's javadoc no longer claims "never null" for a value traced against the previous frame | the same `ActActuator.java:41-63` javadoc, which now states the staleness and names the two writers of `mc.objectMouseOver` | `TheCrosshairSaysItWasTracedAgainstTheLastFrameTest` | 4 |
| 12 | A model's `act_status` row carries what the walk **decided** (`tactic.givenUp`) and how well-earned the message is (`belief`) | `drivers/act/ActStatus.java:47-57` (`SlotStatus`, now ten components, gains `MoveTactic tactic`); projected at `drivers/act/ActRuntime.java:648-667` (`:659` tactic, `:667` count); emitted at `drivers/action/ActTools.java:1003-1016`; named in the description at `:909` and `:959-977` | `ADecisionRecordedOnASlotIsReadableByTheOneComponentThatDecidesTest` — **collected and run**: report present, `tests="5"`, 0 failures | 5 |
| 13 | A route's terminal record names what the walk actually spent, not what a controller computed | `MoveApplier.stamp` at `MoveApplier.java:487-493` writes onto the `RouteIntent` via `route.withTactic(chosen)` at `:492`; `ActRuntime.java:659` reads it back | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest` | 4 |
| 14 | A route that falls on its **first** tick still publishes a tactic | same stamp; the fall-guard branch of `MoveApplier.apply` | `TheRoutedTerminalRecordNamesWhatTheWalkSpentTest.aRouteThatFallsBeforeItsMachineHasRunStillPublishesATactic` | (within the 4) |
| 15 | A walk that spent nothing says so on the record it carries | `AWalkThatSpentNothingSaysSoOnTheRecordItCarriesTest` reads `((RouteIntent) rec.intent()).tactic()` | same class | 3 |
| 16 | The terminal tactic distinguishes "the clock ran out" from "every recovery failed" | `MoveTactic.GivenUp` (`drivers/act/MoveTactic.java:230`; `LIMITS_REACHED` and the `OUT_OF_TICKS_AFTER_A_LANE` split are separate constants) | `TheTerminalTacticMustSayWhatTheWalkSpentToGetThereTest`, `AWalkThatRanOutIsNotAWalkThatSpentEveryOptionTest` | 4 + 4 |
| 17 | `ARRIVED_AFTER_A_LANE` is reachable on a production record | `NavController.arrivalGivenUp()` at `drivers/act/NavController.java:810`, reached from the arrival branch at `:588` (`stop(arrivalGivenUp())`) | `ARRIVED_AFTER_A_LANEIsReachableOnAProductionRecordTest` | 3 |
| 18 | Re-stamping a walk every tick does not free the channel it is standing on | `MoveApplier.stamp` allocates a fresh `RouteIntent` per tick via `withTactic`, so `ActRuntime.sameIntent` is goal-keyed, not identity-keyed | `AReStampedIntentMustNotFreeTheChannelItIsStandingOnTest`, `AReStampedWalkIsStillOursToThePlanTest` | 1 + 2 |
| 19 | A plan's supersession check reads the **lease**, not object identity, and is ANDed with the goal test at all three sites | `ActPlanInterpreter.takenByAnother` at `drivers/act/ActPlanInterpreter.java:244-247`; call sites `:111`, `:217`, `:269` | `APlanTeardownMustNotCancelTheRacingActSetTest`, `TwoConsumersOfOneSlotCannotSilentlyOverwriteTest` | 5 + 14 |
| 20 | A wedged body sidesteps or **names what is in the way** | `NavController.JAM_REACH = 0.2D` at `:88`; the ranking that keeps a measurement above a guess | `AWedgedBodySidestepsOrSaysWhatIsInTheWayTest`, `AFlushJamIsNamedAtEveryCellOffsetTest` | 5 + 5 |

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

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 21 | The DROP verb exists end to end: schema, intent, controller, **re-read confirmation** | schema enum `drivers/action/ActTools.java:302`; `InteractIntent.Kind.DROP` and `dropStack` at `drivers/act/InteractIntent.java:107,242`; `drivers/act/DropController.java:36` | `DropControllerTest`, `DropReachabilityTest`, `InteractApplierDropRoutingTest`, `ADropIsReachableThroughTheRealToolBoundaryTest` | 11 + 7 + 4 + 6 |
| 22 | The drop confirmation is "the slot is EMPTY", not "the click returned" | `DropController.isDone()` at `:62` | same as row 21 | (within the above) |
| 23 | A misspelled `sections` entry is an **error naming the offender**, not a silent omission | `drivers/world/WorldViewCapture.unknownSections` at `:61-72`, consulted at the tool boundary | `AnUnknownSectionNameIsRefusedTest` | 4 |
| 24 | Every key `act_status` emits is named in its description, and the vocabulary is **derived from the emitted map** | description at `ActTools.java:909` and `:959-977`; the gate at `ActToolsTest.java:426-440` | `ActToolsTest.everyFieldActStatusEmitsIsNamedInItsDescription` | (within 34) |
| 25 | Every interact/look argument is both **read by the parser** and **named in the description**, in both directions | `ActToolsTest.java:544` (interact), `:578` (look) | same class | (within 34) |
| 26 | A tool description naming a tool that does not exist is a build failure | registry walk in `io/transport/DescriptionsNameToolsThatExistTest` | `DescriptionsNameToolsThatExistTest` | 8 |
| 27 | A tool description's legend matches the values the handler can actually send | per-tool checks in `io/transport/ToolDescriptionsMatchTheirBoundsTest` | `ToolDescriptionsMatchTheirBoundsTest` | 7 |
| 28 | A hazard is readable **before** the walk arrives, and survives on the terminal tick | `MoveApplier.java:386` (terminal), `:440`, `:459` (running) | `AHazardIsReadableBeforeTheWalkArrivesTest`, `TheEarlyHazardWarningReachesActStatusTest` | 9 + 6 |
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
| 31 | An eval filter matching zero tasks is an **error**, not an empty PASS | `eval/SelfPlayEval.runAll(List)` at `SelfPlayEval.java:38`, the refusal at `:59-60` | `AnEvalFilterThatMatchesNothingIsNotASuccessTest.aFilterThatMatchesNoTaskIsRefusedRatherThanReportedAsSuccess` | 2 |
| 32 | The signing script derives the classpath separator **from the JVM that will consume it**, and strips the CR off the answer | `scripts/sign-patch.sh:77-79` (ask `-XshowSettings:properties`, `tr -d '\r'`, `sed` out `path.separator`), `:91` (the join) | `TheSigningClasspathIsBuiltFromTheJvmTest`, in `scripts/test_sign_patch.py` | 6 |

**Row 30's ledger is the thing standing between the agent and a false pass: with
`keep.addAll(owedItems())` removed, `got` flips from false to true** — the policy reaches the
goal by eating its own planks. That mutation is the evidence the protection is real. What is
*not* enforced is that the eval could catch a broken policy at all: `GoalPolicy` is
test-only, has zero references from `core/src/main`, and cannot be substituted
(`public final`, constructed directly at **eleven** sites). There is **no seam**, so a
deliberately broken policy cannot be run and the suite has **no negative control** — 24 tasks
with world-fact assertions, two ever observed red, none ever observed red *because the
policy was broken on purpose*. The `alive` conjunct is a second, separate hole; see §4.

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

| # | guarantee | implemented at | enforced by | tests |
|---|---|---|---|---|
| 33 | The client's "is it night" answer is **derived from the clock**, not read off a field that stopped moving | `drivers/world/WorldViewCapture.java:502` — `daytime = Daylight.isDaytime(time)`, the one line the defect lived on; the live accessor is `drivers/act/LivePlayerActuator.java:243`, declared at `drivers/act/ActActuator.java:360` | `TheProductionDayNightReadIsNotFrozenTest` (drives `WorldViewCapture.env` itself by reflection, on a world built to be exactly as wrong as a real client) + `TheClockCostsTheWalkNoWorldReadTest` | 4 + 6 |
| 34 | The night boundaries are **vanilla's own**, asserted across all 24,000 ticks | `drivers/world/Daylight.java:97` (`skylightSubtracted`), `:112` (`isDaytime`, delegating to `EnvironmentWeather.isDaytime(int)` so the threshold is restated nowhere); transcribed from `World.calculateSkylightSubtracted` at `World.java:1403-1413` | `DaylightMatchesTheVanillaCurveTest` | 6 |
| 35 | The clock helper is **structurally incapable** of becoming a second frozen read — proven from bytecode, not from reading | `Daylight`'s five public methods at `:70`, `:97`, `:112`, `:117`, `:122` take a `long` and return arithmetic; there is no `World` parameter, no field, and no seam | `TheClockCostsTheWalkNoWorldReadTest.theDaylightHelperContainsNoWorldCallAtAll` (zero calls into `net/minecraft/world/` or `net/minecraft/entity/`, zero to `World.isDaytime` or `getSkylightSubtracted`) and `.theLiveActuatorDoesNotDelegateToTheFrozenVanillaRead` (exactly one call to `Daylight.isDaytime`, zero to `World.isDaytime`) | (within the 6) |
| 36 | A sunset that does not cross the hour bucket is still **visible** in a diff | `drivers/world/WorldViewDiff.envDiff` gains `worldTime` and `daytime`; the bucket is 1,000 ticks wide and vanilla's night begins at 13,807, so dusk at `:13807` and dawn at `:22193` both sit **inside** a bucket | `TheDiffCarriesTheClockTest` — asserts the two ticks share a bucket as a *premise*, so the test fails loudly rather than passing for the wrong reason | 5 |
| 37 | Every `ActOutcome` factory site in the act and plan drivers **names its derivation**, and the site is the claim | five named constants in `drivers/act/ActOutcome.java`: `:66` `READ_DIRECTLY = Belief.OBSERVED`, `:76` `DERIVED_FROM_READS = Belief.INFERRED`, `:88` `PLANNED_ON_CLIENT_WORLD`, `:99` `REUSED_CELL`, `:111` `READ_CAME_BACK_EMPTY = Belief.UNKNOWN`; the permitted set is `GRADED_DERIVATIONS` at `:114-117` | `ABeliefCensusOverTheActScenariosTest.everyOutcomeSiteInTheActLayerStatesItsDerivation` — fails on one more **or one fewer** site | 7 |
| 38 | The unread count rides **beside** the grade, never inside it, and absent is not zero | `ActOutcome.java:37` (the `Integer unreadCells` component) beside `belief`; recorded on `SlotRecord.java:46` with `NO_COUNT = null` at `:149`; carried through **three** `MoveApplier` stamps at `:393`, `:442`, `:459` (and two in `InteractApplier` at `:59`, `:76`); on the wire at `ActTools.java:1015` | `ABeliefCensusOverTheActScenariosTest` — asserts `unreadCells == 412` beside `UNKNOWN` and `unreadCells == 0` beside `OBSERVED` (`:422-437`) | 7 |

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
**null default** on `SlotRecord` (`:89`) and `ActOutcome` (`:146`), and `mayActOn()` is its only
safe reader, because a bare `null != UNKNOWN` comparison **fails open**: every ungraded line
would read as actionable.

---

## 2. The measurement instruments, and what each one pins

Five instruments. For each: what it would catch, and — the half that matters more — what it
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
fewer** than the **four** allowlisted files (`ALLOWLIST` at `:67`, entries `:71-101`; one-more
at `:126-132`, one-fewer at `:136-141`; the size — `4` — at `:156`, file existence at `:161-163`
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

---

## 3. The surefire collection gap — a trap for the next person

**Read this before adding a test.**

Surefire's default includes match `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`,
`**/*TestCase.java`. This repository's convention for contract tests is
`AThingDoesSomething.java` and `TheThingIsSomething.java` — **which surefire does not
collect.** `core/pom.xml` configures no `<includes>`, so the defaults apply.

Measured on this tree, 2026-10-02, after the wave-9 additions:

| # | measurement | value |
|---|---|---|
| 1 | `.java` files under `core/src/test/java` | **323** |
| 2 | matching surefire's default includes | **300** |
| 3 | not matching | **23** |
| 4 | of the 23, files carrying at least one `@Test` | **7 — and all 7 end in `LiveIT`** |
| 5 | of the 23, files carrying **zero** `@Test` | **16** |

**300 is also the number of reports in `core/target/surefire-reports` for real classes**, one
for one, with zero files lacking a report (§0). That is the strongest statement available about
this table: every file the include pattern matches was asked a question by the last run.
Rows 1 and 2 also move while other people are working — they were 322 and 299 an hour before
this table was written, and the difference is a sibling's new test file. Re-measure; do not
subtract.

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

Stated plainly, because the list is short and the claims behind it are large.

**The self-play eval has 24 tasks** (`grep -c 'implements Task' eval/EvalSuite.java` = 24).
It is a high-quality integration and composition gate over the production controllers:
world-fact assertions across locomotion, the input-layer timing gate, hazard reporting,
inventory transfer, two container models, and the placement chain, with mutations run in both
directions. **It is not a north-star measurement, and no task in it bears on one.**

The north star names four things. Here is what exists for each:

| criterion | measured? | what exists instead |
|---|---|---|
| survive a shelter / have a shelter by dawn | **No.** No task builds an enclosure; there is no "is the player enclosed" predicate anywhere in the substrate | nothing |
| health never below 18 | **No.** `18.0` appears **zero** times in the eval tree | `w.health() > 0.0D`, unfalsifiable in the tasks that use it |
| a box standing at dawn | **No.** A chest does not exist in this substrate — `SimWorld.KNOWN_GAPS` (`SimWorld.java:156-164`) says so: *"a chest, a furnace and an enchanting table all still return a plain placement refusal"* | nothing |
| a whole night | **Partly, and the "partly" is exactly the interesting part.** The substrate **has a clock** — `SimWorld` advances `worldTime` 1:1 per tick, transcribed from `WorldServer.java:206-209`, and T24 walks a leg in daylight, waits the night out, and walks a second leg inside it | "it got dark" is assertable. **"it was dangerous" is not**, and `SimWorld.KNOWN_GAPS` says exactly that: *"the CLOCK exists and the LIGHTING does not, and the two are not the same gap"*. Nothing spawns in the dark, no zombie burns at dawn, no light level gates a cell, a torch changes nothing |

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

3. **No damage source is reachable from a task.** `SimWorld.health` has three writers:
   `atHealth` (fixture only), `damagePlayer` (whose only caller is `SimMob.tick`), and an
   `advanceUse` food heal that **raises** health. `damagePlayer` needs a `SimMob`, and
   `spawnMob` is called from **zero** `EvalSuite` tasks — its four callers are all in
   `AMobThatActsIsMeasuredInTheWorldTest`, which is not one of the 24. So
   `w.health() > 0.0D` is the constant `20.0 > 0.0`: it appears at **ten** sites
   (`EvalSuite.java:724, 1326, 1445, 1537, 1641, 1758, 1843, 1976, 2008`, plus the one at
   `:947`), and that last one conjoins it with `w.health() == 3.0D`, which is equally
   unfalsifiable for the same reason. T24 added two of the ten and asserted `health=20.0`
   at both ends of a night it waited out, which is the same constant read twice. **The suite
   contains no run in which the player loses health at all**, and `alive` reads as if it were
   evidence. A constant-true assertion in a suite whose charter is "every verdict is a world
   fact" is worse than no assertion.

4. **`GoalPolicy` is 1317 lines, has zero references from `core/src/main`, exposes one action
   family, and cannot be substituted** — `public final`, constructed directly at eleven sites.
   Its own javadoc says what it measures (`GoalPolicy.java:61-65`): *"given a plan, do the
   production controllers execute it against a real world? A pass is evidence the controllers
   compose; it is NOT evidence a model would produce this plan."*

### 4.1 Two smaller items, verified this session

- **`SimWorld.droppedOntoFloor()` has zero readers in the entire repository**
  (declared at `SimWorld.java:285`, accessor at `:1193`; re-measured, because the file has
  grown since the original line numbers were taken). Its sibling `thrownByPlayer` carries
  five readers — four assertions in `AFullBagGetsAMakeRoomMoveBeforeItRefusesToDigTest`
  (`:58`, `:81`, `:132`, `:187`) and one in `GoalPolicy.java:321`. The split is right and
  only one half is load-bearing. By this repository's own stated test for decoration — *"a
  case that changes no behaviour is decoration"* — the other half is decoration.
- **`GoalPolicy.obtain`'s recipe-cycle guard (`GoalPolicy.java:204`) has zero coverage.**
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
property of the seam between components.** Each of the five was caught by asking that question
("and who reads *that*?") or by driving the real thing, which is why §4.1 is a list rather than
a green suite.

**And the mirror-image mistake has its own form, which is easier to make because it looks
careful.** Two class names are cited in main-source javadoc that **do not exist**, and they are
named here only so they are never copied again: `Daylight.java` points at a clock test and
`ActOutcome.java` points at a derivation test, and neither file is in the tree. The real homes
are `TheClockCostsTheWalkNoWorldReadTest` and `ABeliefCensusOverTheActScenariosTest`. **A
plausible, specific, invented test-class name is the most convincing form this shape takes**,
because a reader has no reason to doubt a name that specific — and unlike a wrong line number, a
wrong class name cannot be caught by following the citation. Every `file:line` in §1 and every
class name beside it was therefore checked by asking the file system, not by asking whether the
name looked right. (§2.3's allowlist is the standing counter: it asserts every entry is a file
that really exists, for the same reason.)

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
marks which of its own seven deltas are measured and which are carried history** — because a
decomposition whose parts can be shuffled without changing its sum is a decomposition nobody
has checked. Re-measure them; the instruction is in the document for the same reason the
instruction is in `branch-topology.md` §0.1.
