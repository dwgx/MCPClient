# Test quality audit -- uncommitted working tree, D:/Project/MCPClient

Auditor: TestQuality (read-only). HEAD 46249ca, branch mcp-core. No file in the repo was created, edited or deleted; no build, no test run, no git write command.

## Scope, as measured (not as briefed)

    git diff --name-only -- "*src/test*"                    ->  33 modified tracked test files
    git ls-files --others --exclude-standard -- "*src/test*" -> 101 untracked test files
    total in scope: 134 files, of which 13 are support classes with no @Test
    (BodySim, EvalHarness, EvalSuite, GoalPolicy, SelfPlayEval, SimBody, SimCraftWindow,
    SimMob, SimWorld, SourceScan, Probe, plus tracked FakeActuator and FakeWorld)
    => 121 test-bearing files audited.

The brief said 90 untracked; the measured number is 101. The delta includes
core/src/test/java/net/marcloud/mcp/core/eval/AMobThatActsIsMeasuredInTheWorldTest.java,
which was not in the listing the brief was written from. Every one of the 101 is covered below.
No file is silently omitted; the support classes are listed and marked.

Verdict key:

    TEETH    the assertion can fail for a reason that is a real defect
    WEAK     it can fail, but only for reasons that are not the property named
    VACUOUS  it cannot fail, or cannot distinguish the two states it claims to
    PINS-BUG green precisely because the current (defective or undocumented) behaviour is there

## A. The eval package (core/src/test/java/net/marcloud/mcp/core/eval/)

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| EvalSuite.java T01 | 157 | world fact | TEETH | endpoint (sign, distance, ground) asserted; threshold 4.0 chosen so no rounding decides it |
| EvalSuite.java T02 | 186 | world fact | TEETH | arrival + onGround + COMPLETE; bound 1.0 deliberately looser than the private 0.6 epsilon |
| EvalSuite.java T03 | 232 | sweep | TEETH | 8 headings, collects every failure instead of returning on the first |
| EvalSuite.java T04 | 284 | world fact | TEETH | reads SimWorld.inputTrace(), what the input layer handed the body, not what was submitted |
| EvalSuite.java T05 | 317 | tautology risk | WEAK | stayedNear is posZ() > -8.0 and the spawn is z=0, so a controller that refuses at tick 1 satisfies it without moving. The `failed` half carries the task |
| EvalSuite.java T06 | 355 | plan, not world | WEAK | asserts on Planner output only, reached via the harness route factory (EvalHarness.java:92-99), so a broken executor would still pass. Declared at SimWorld.KNOWN_GAPS:130-140 |
| EvalSuite.java T07 | 401-403 | world fact | TEETH | cobble==1 with stone dug, plus gone; noBlockItem = stone == 0 is dead weight (a block is never an item) but harmless |
| EvalSuite.java T08 | 479 | world fact | TEETH | jump axis read from inputTrace, not the endpoint; KNOWN_GAPS records that the endpoint proxy passes with jump() forced false |
| EvalSuite.java T09 | 547 | world fact | TEETH | plan bridges + player across + a block now standing in the trench: three independent facts |
| EvalSuite.java T10 | 620 | world fact | TEETH | survivor walked, fatal line reported-then-cancelled, on the typed NavHazard field not a sentence |
| EvalSuite.java T11 | 798-799 | world fact | TEETH | client+server dest both 5, source emptied on both sides, cursor null; the window must mutate for this to mean anything |
| EvalSuite.java T12/T13 | 867, 912 | world fact | TEETH | real CraftingManager, real 3x3 vs 2x2, four independent facts each |
| EvalSuite.java T14 | 947-948 | world fact | TEETH | health > 0 AND == 3.0 exactly, i.e. no damage taken on the route |
| EvalSuite.java T15 inside | 1000-1017 | world fact | WEAK | reach is checked against EntityCombat.serverAccepts but the substrate refuses at reachDistance()+1.0 (SimWorld.java:1325); two different numbers, so the bound named is not the one that refuses |
| EvalSuite.java T15 beyond | 1037 | self-fulfilling | WEAK | asserts hits==0 past the bound; InteractController refuses at act.reachDistance()=5.0 before calling attackEntity, and SimWorld refuses again at 1325. Cannot distinguish refused from swung-and-missed |
| EvalSuite.java T16 | 1113, 1146 | world fact | TEETH | heal amount read from the registered ItemFood, not typed; interrupted use asserts bread==2 AND health==10.0 |
| EvalSuite.java T17 | 1213-1227 | world fact | TEETH | the load-bearing fact is that no crafting_table exists afterwards; the controller verdict is the second half |
| EvalSuite.java T18 | 1326 | world fact | TEETH | ore cell air + holds ore + alive; the policy boolean is ANDed but not sufficient |
| EvalSuite.java T19 | 1414 | world fact | TEETH | same three facts on the second harvest gate |
| EvalSuite.java T20 | 1513 | world fact + self-check | TEETH | lineBlocked > 0 is IN the pass condition, so moving the lava band cannot leave a green row testing nothing |
| EvalSuite.java T21 | 1623 | arithmetic | WEAK | stoneSpent <= held admits a run that over-dug if the ordering was lucky; the comment admits the previous form was unsatisfiable, the new one is loose rather than wrong |
| EvalSuite.java T22 | 1727-1728 | world fact | TEETH | ladder held + a bench STANDING in the world + zero bench items in the bag |
| EvalSuite.java T23 | 1812 | world fact | TEETH | full-bag world, cobblestone>0 asserted; describe() distinguishes dug-and-lost from refused-before-digging |
| SelfPlayEvalTest.java | 41 | aggregate | WEAK | one assertion over 23 tasks; a throw inside a task is caught and counted as a failure (SelfPlayEval.java:47-52) so it is not a silent skip, but one red task names itself only in the message |
| SelfPlayEval.java runAll | 39-41 | early-return guard | WEAK | `continue` on the only-prefix filter means a typo in -Dtest yields zero tasks and an all-pass report. Nothing asserts scored.size() > 0 |
| EvalHarness.java runUntil | 172 | unused return | TEETH | documented; every caller asserts an endpoint, none asserts the tick count |
| EvalHarness.java run(int) | 203 | dead API | WEAK | run(maxTicks) delegates to run(maxTicks, () -> false) and can never return true; no caller found |
| EvalHarness.java routeFor | 92-99 | substitution | WEAK | supplies its own route factory instead of RoutePlanning.executorFor, so the startup composition is unverified. Declared in KNOWN_GAPS |
| GoalPolicy.java walkTo | 819-826 | self-fulfilling | WEAK | the policy is test-owned; a pass says the CONTROLLERS compose under a policy the same commit wrote, not that a planner would produce this chain. The class doc says so at :33-40 |
| GoalPolicy.java obtainInner | 192 | early-return | TEETH | returns false on a full bag BEFORE digging, and the note says why; this is the T23 behaviour under test |
| GoalPolicy.java haveIngredientsFor | 239 | bound | TEETH | MAX_INGREDIENT_PASSES=12 is a bound with a stated termination argument, not a skip |
| SimWorld.java constants | 54-75 | provenance | TEETH | every physics constant carries the vanilla line it was transcribed from |
| SimWorld.java | 927-929 | mirror | WEAK | reachDistance() hardcodes 5.0 citing LivePlayerActuator; production reads PlayerControllerMP.getBlockReachDistance(), so the transcription cannot be falsified |
| SimWorld.java | 1314-1329 | self-fulfilling | WEAK | attackEntity applies its OWN reach refusal before damaging; T15 therefore tests the substrate guard as much as InteractController's |
| SimWorld.java KNOWN_GAPS | 102-137 | honesty | TEETH | 7 named divergences, including the one that would have invalidated T08: a descending body is lifted onto a ledge with NO jump, measured y=65.12 with jump() forced false |
| SimWorld.java canSee | 493-501 | approximation | WEAK | sight is a 0.2-block walk of the eye-to-eye ray, not a trace; declared at KNOWN_GAPS:114 |
| SimBody.java | 1-30 | shared impl | TEETH | one collision implementation for player and mobs, so they cannot drift; the alternative is what the doc says would happen |
| SimMob.java | 1-70 | provenance | TEETH | per-type followRange, duty cycle, 30 deg/tick turn, stale path, width-squared reach, invulnerability window, each with a vanilla line number; gaps declared |
| SimCraftWindow.java | 14-73 | provenance | TEETH | ContainerPlayer armour slots 5..8 vs ContainerWorkbench none, as a field; real CraftingManager; client/server copies split deliberately |
| AnIngredientThatArrives...Test | 97-104 | control/treatment | TEETH | the two arms differ ONLY in when the log arrives; both assert planks/log/matrix/cursor |
| same | 158-176 | cause | TEETH | both cursor copies and both slot copies read directly, not through the controller |
| same | 186-199 | pre-take read | TEETH | asserts the matrix cell BEFORE the take, the only point at which the shared-array defect is visible |
| AMobThatActsIsMeasured...Test | 50-56 | world fact | TEETH | sight flips with a wall and flips back when removed; a constant-true canSee cannot pass |
| same | 71-86 | world fact | TEETH | the health bar is read across five damagePlayer calls, so a stub returning true cannot pass |
| same | 108-113 | mirror risk | WEAK | before - 3.0*hits is computed from SimMob's OWN counter in the substrate that applied the damage; a compensating bug in both passes |
| same | 128-133 | world fact | TEETH | closing measured on its own, so a standing-still mob cannot satisfy the damage assertion |
| same | 141-147 | negative | TEETH | an untranscribed type is refused rather than improvised; nothing acted |

## B. drivers/act (20 untracked + 7 modified)

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| AWorkerWriteInsideTheApplierWindowTest | 68-72 | identity | TEETH | assertSame on the intent record, not on a message; applies.get()==1 proves the loop did not retry |
| same | 92-104 | both directions | TEETH | a cancel inside apply is accepted, survives into the teardown tick, costs exactly one tick |
| same | 129-138 | fault isolation | TEETH | a throwing applier fails only its own slot AND the mid-window submit still stands |
| TheReactionDelayIsOneDrawNotARaceTest | 57-67 | randomness | TEETH | 300 draws, asserts the draw is IDENTICAL across the wait; the defect was a re-draw, so equality is the property |
| same | 84-96 | randomness | TEETH | firstKey == drawn+1 for every draw in the band; the constant is derived (MAX+1), not copied |
| TheWalkDelayIsSkewedLikeALatencyTest | 76-92 | distribution | TEETH | sample skewness > 0.15 over 6000 draws; uniform 4..8 is 0 by construction. The second assertion (COUNTS[4] > COUNTS[8]) needs no moment at all |
| same | 105-116 | boundary | TEETH | every value 4..8 must be reachable, or the description's band is fiction |
| same | 122-127 | statistical | WEAK | mean asserted in (5.2, 5.9) over 6000 draws. ~10 sigma so stable in practice, but this is the one place a green row depends on a sample |
| AMoveIsKeyPressesAndWaitsLikeAPersonTest | 61-67 | tautology risk | TEETH | diagonal is (1,1) with |input|=sqrt(2); the 0.707 mistake is named and excluded |
| same | 106-124 | chatter | WEAK | changes <= 1 over 40 ticks; an alternating controller reads 20 and fails, so it has teeth, but the bound is loose |
| same | 136-145 | timing | TEETH | firstMovingTick in [4,8], derived from the documented band which is itself pinned at TheWalkDelayIsSkewedLikeALatencyTest:50 |
| ARawMovementAxisIsAKeyPressTest | 48-52 | exhaustive | TEETH | every (f,s) pair must be 0/+-1: a sweep, not a sample |
| same | 61-67 | sign | TEETH | each axis keeps its side; 0.9 forward becomes the (1,1) diagonal |
| same | 78-83 | boundary | TEETH | 0.001 is nothing, 0.49 is nothing, 0.5 is a press: the half-key boundary pinned on both sides |
| same | 90-96 | cross-path | TEETH | navigator and raw path must agree over a grid; a divergence between the two key snaps fails |
| ALookIsAMousePositionAndAHandShapeTest | 33-37 | lattice | TEETH | quantising a lattice point is the identity over +-180 |
| same | 44-48 | lattice | TEETH | the result is ON the lattice and within half a mouse of the request |
| same | 68-75 | curve | TEETH | leaves rest, arrives at rest, peaks at the midpoint, midpoint is exactly half |
| same | 81-84 | monotone | TEETH | 200 samples, no dip; a non-monotone profile would send the crosshair backwards |
| same | 116-119 | range | WEAK | peak/mean in (1.45, 1.75) is a fitted human number stated as a constant; the two ends (a constant rate, textbook minimum-jerk) are excluded by name |
| ALookTurnsAtTheRateAHumanEyeDoesTest | 74-76 | derived | TEETH | ticksAllowedByTheCapOnly uses the profile's own mean; the assertion is that the measured trajectory is SLOWER than the cap-only rate |
| same | 123-130 | premise | TEETH | asserts the law is tighter than the cap for every arc in the table, or the test says nothing |
| same | 152-159 | measured | TEETH | steps.length >= capOnly+1: the look really was slower than a flat cap |
| same | 200-213 | law shape | TEETH | the rate is monotone in amplitude and saturates exactly at the fitted edge |
| same | 214-218 | sublinear | TEETH | an 8x longer turn may not be 8x faster; ratio < 12 against a proportional 30 |
| TheLookProfileIsShapedToTheMeasuredVelocityTest | 31-33 | MIRROR | WEAK | progress() delegates to LookController.shapedProgress, i.e. the expected value is computed by the code under test |
| same | 87-90 | boundary | TEETH | shapedProgress(0)==0 and (1)==1 for every warp ratio |
| same | 94-97 | degenerate | TEETH | ratio 1.0 and NaN must not break the curve |
| same | 102-105 | constant | WEAK | assertEquals(1.805D, VELOCITY_PEAK_MEAN, 0.0) pins a literal to itself; the differs-from-pi/2 assertion beside it is the one with information |
| TheLookChannelHasNoReactionDelayTest | 137-166 | exhaustive | TEETH | 11 arc/cap pairs x 200 runs, EVERY run must not be a tick-one hold; a restored delay fails on iteration 1 at either end of the draw |
| same | 175-192 | premise | TEETH | only arcs whose opening clears the lattice; the restriction is argued, not convenient |
| same | 203-217 | bounded | TEETH | sub-lattice openings finish within 6 ticks, so slow-to-start is bounded by the lattice and not by a draw |
| same | 228-238 | string absence | TEETH | no tick contains 'reacting' or 'first move at'; reintroduction under other wording still fails |
| same | 254-283 | both directions | TEETH | the micro-correction lands on one tick; 25 degrees lands, 26 slews and wrote its first step on tick 1 |
| AWedgedBodySidestepsOrSays...Test | 98-106 | world fact | TEETH | route finishes, feet in the destination's OWN cell, unwedgeSteps >= 1 so a walk that never needed recovery cannot pass |
| same | 136-141 | premise | TEETH | asserts the recovery actually ran before asserting what it achieved |
| same | 163-167 | zero-cost | TEETH | a clean walk spends ZERO recovery ticks and ZERO steps: the cost claim both ways |
| same | 192-201 | honest failure | TEETH | terminal, not ok, names position, block and cell, admits it tried nothing |
| same | 231-240 | bounded | TEETH | names the tick bound, says it tried both sides, did not spend the whole 300-tick walk |
| ABlockLastsUntilTheCallerReleasesIt | 39-63 | both directions | TEETH | 400 ticks with nothing expiring, then the caller releases and the key goes up |
| same | 74-76 | negative | TEETH | a meal is refused rather than held forever, and the key is released not abandoned |
| same | 83-87 | idempotence | TEETH | a release with nothing in use is a release, not a failure |
| ACritIsSwungInTheFallingWindow... | 53-63 | window | TEETH | flat ground does not swing (attackCalls==0); in the window exactly one swing lands |
| same | 76-82 | bounded refusal | TEETH | the wait ends in a refusal naming the term it is waiting on |
| same | 91-94 | control | TEETH | a plain attack is not gated on the body |
| same | 104-112 | substrate | TEETH | drives the real harness until fallDistance rises, then asserts CritWindow agrees with the body |
| SneakingStopsAtTheEdge... | 49-61 | paired | TEETH | sneaking key reached the body, player stayed on the floor, and a NON-sneaking body walks off the SAME ledge |
| TheHazardScanIsNotPaidEveryTickTest | 52-60 | ratio | WEAK | reads <= 97*4 over 60 ticks; a 4x regression in scan cost passes. A judgement, not a measurement |
| same | 70-77 | non-vacuity | TEETH | the scan must still run at all: caching must not have become never-scan |
| same | 90-107 | regression | TEETH | a cached answer must KEEP being reported; a warning that appears once is one nobody acts on |
| TheTwoMoveTargetsAreNamedForWhatTheyDoTest | 34-45 | discriminator | TEETH | walk_straight produces NavIntent, go_to produces RouteIntent: the shapes are distinguishable |
| same | 57-70 | rename | TEETH | a refusal that merely mentions the new key while complaining about something else fails |
| same | 87-95 | both directions | TEETH | 'to' replacement must not claim routing; 'route' replacement must not claim a straight line |
| AMoveThatCannotHappenIsRefusedTest | 73-78 | coercion | WEAK | a map-shaped walk_straight becomes NavIntent with coordinates read out: this pins the coercion, not a refusal |
| same | 92-98 | honest refusal | TEETH | names 'neither' and says what IS accepted |
| same | 104-107 | missing field | TEETH | a coordinate object missing 'y' is refused, not defaulted to 0 |
| same | 120-123 | no-op | TEETH | all axes at rest with durationTicks 0 is refused; accepting it reports fake success forever |
| same | 146-150 | mutation guard | TEETH | a new inert-move guard that rejects a real move fails loudly |
| AStraightLineNamesWhatIsOnItTest | 60-63 | negative | TEETH | an ordinary flat line must not cry wolf: no LAVA, no WATER, no drop |
| same | 73-77 | positive | TEETH | lava is named AND the coordinates given, because a block name alone does not tell a caller whether it matters |
| same | 93-96 | consequence | TEETH | water is named together with the consequence (drown) |
| same | 106-112 | arithmetic | TEETH | a 30-block fall names the drop, the price ceil(distance-3), and the gap depth |
| same | 151-155 | no-stale-warning | TEETH | after walking past, the hazard message must stop |
| AHazardIsReadableBeforeTheWalkArrivesTest | whole file | ordering | TEETH | the hazard must be readable BEFORE arrival, which is why it exists |
| TheEarlyHazardWarningReachesActStatusTest | whole file | propagation | TEETH | the typed hazard reaches act_status |
| ActSetRefusesBlockTargetsItCannotHonourTest | 270-274 | coverage | TEETH | refusal covers EVERY kind that cannot carry a block target, not just 'use' |
| ActSetValidatesEveryChannelBeforeSubmittingAny | whole file | atomicity | TEETH | validation precedes any channel being submitted |
| PressKeyBindingTest | whole file | reachability | TEETH | binding claimed vs key actually down |
| InteractControllerTest (modified) | whole file | controller | TEETH | reach refusal, dig stall, hold release |
| FakeActuator (modified) | 511-533 | fake honesty | TEETH | models unPressAllKeys on screen open, so HoldController is tested against the game rule and not the harness bookkeeping |
| LookControllerTest (modified) | 52-58 | tolerance + guard | WEAK | overshoot bound 22f+0.075 is one mouse (0.15/2), legitimate but a lattice-derived magic number; and the per-tick cap assertion sits behind `if (!out.terminal())` so the final tick is unchecked |
| LookTrackingDoesNotSelfTerminateTest (mod) | 74-75 | MIRROR | WEAK | expectedYaw() calls LookController.anglesTo, the same production method whose result the controller must match. If anglesTo is wrong both sides move together. The diff also widened tolerance 1e-3 -> 0.15 on these lines |
| ActRuntimeTest (modified) | 153-154 | re-pin | WEAK | was assertEquals(0.5f, moveForward()); now assertEquals(1.0f, ...). The change is correct (MoveIntent snaps axes on construction, MoveIntent.java:49-53) but an old assertion is being re-pinned to the new value rather than replaced by a property |
| ActRuntimeGatesArePinnedAtItsBoundaryTest (mod) | 109-118 | FLAKE FIX | TEETH | the known flake: the loop capped at 8, equal to the top of the 4..8 band, so a max draw left the axes neutral and the test failed ~1 run in 3. Now capped at 32 with the band asserted separately at 114-118. The precedent fixed correctly |
| same | 132-135 | loosened | WEAK | was assertEquals(0.8f, moveForward(), 1e-6); now assertTrue(Math.abs(...)==1.0f), which discards the sign, and strafe accepts 0 OR 1. A mirrored axis, the defect T03 exists to catch, passes this line |
| NavControllerTest (modified) | 201-249 | both directions | TEETH | 0.55 from the centre but in the WRONG CELL is not arrival; 0.20 off centre inside the cell is. The epsilon alone cannot decide either |
| RawAxesWithoutDurationAreRefusedAtSubmitTest (mod) | whole file | refusal | TEETH | a duration-less intent that would never end is refused at submit |

## C. drivers/plan, world, gui, action, observe

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| EveryVerdictIsClassifiedTest | 34-41, 78-80 | vocabulary table | TEETH | the whole BlockView vocabulary is classified, so `return verdict >= 0` cannot ship |
| same | 88-98 | named halves | TEETH | FENCE and OPEN_TRAPDOOR each asserted separately because they are the untested side |
| same | 103-113 | enumeration guard | TEETH | reflection over BlockView's WALK_* fields; a new code fails rather than inheriting passable |
| TheSearchCeilingDoesNotFireOnAShortGoalTest | 91-98 | EARLY RETURN FIXED | TEETH | documents that the previous version had `if (plan.found()) { return; }`; now the premise !plan.found() is asserted and the refusal must not blame the ceiling. Second precedent fixed correctly |
| APlannedDropIsNotAFallTest | 63-81 | simulated descent | TEETH | the fall is stepped at 0.4 blocks a tick, not teleported, so the old guard's threshold is actually crossed; both directions asserted |
| same | 91-101 | negative | TEETH | a fall past the landing is terminal, not ok, and says 'fell out of the route' not 'stuck' |
| AWedgedRouteFinishesOrNamesTheBlockTest | 88-91 | exact cell | TEETH | feet in the cell the PLAN named, not merely nearer it |
| same | 117-128 | honest failure | TEETH | no move credited, names position/block/cell, quotes the steering's own verdict |
| same | 153-156 | premise | TEETH | asserts the three-move route succeeds before measuring the wedged case |
| ABridgeIsConfirmedByIdentityNotByPresenceTest | 70-75 | negative | TEETH | placement into water spends nothing and the route fails; a click that succeeded but placed no block is not credited |
| same | 93-97 | identity | TEETH | placement INTO water IS a placement, costs exactly one block, block exists afterwards |
| same | 129-134 | height | TEETH | feet at 63.5 against a plan naming 64 is not arrival; the message names the wanted height |
| same | 170-172 | boundary | TEETH | feet at 63.5 on a slab IS arrival: the band pinned from both sides |
| LavaIsNeverPassableToARouteTest | 70-74 | discriminator | TEETH | water is room and lava is not, from the SAME fake, so a solidity-only world cannot satisfy it |
| same | 90-92 | sealed | TEETH | a lava-sealed chamber has no route at any budget |
| same | 118-124 | pre-search | TEETH | a lava destination is refused before the search and the message says LAVA, not 'no room for a body' |
| same | 172-173 | bridging | TEETH | bridging survives the lava refusal; one cell wide, one block spent |
| ALadderIsClimbedBecauseTheForwardAxisIsHeldTest | 62-66 | plan shape | TEETH | three blocks up a ladder is three moves, first is a WALK into the column |
| same | 103-106 | published axis | TEETH | the forward axis must have been published; this fake climbs on nothing else |
| same | 128-133 | negative | TEETH | off a ladder the route fails and says 'ladder' |
| same | 187-195 | EARLY RETURN | WEAK | the loop returns inside `if (m.to().equals(...))`; if the planner stops offering that edge the test asserts nothing and passes. 'Not offering the edge is equally correct' is true, and is why it can go quiet |
| ACreepIsHowARouteCrossesALedge...Test | 80-95 | paired | TEETH | creeping body stays on the floor and stops at the brink; a NON-creeping body on the same ledge falls past y=30 and travels 10 blocks further |
| same | 117-130 | both directions | TEETH | the sneak key reaches the body on a creeping walk, absent on a plain one |
| same | 174-181 | sweep | TEETH | every move down a ledge-with-drop is marked creep; open ground is not |
| same | 213-222 | propagation | TEETH | act_status must report the creep, so 'never asked' is distinguishable from 'asked and dropped' |
| WaterIsSwumAndTheDrowningWarning...Test | 69-79 | derived cost | TEETH | a swim spends air; airTicks > 0 for every swim move |
| same | 97-103 | air budget | TEETH | 40 blocks is 520 ticks against 300 of air, so no route, and the refusal must say AIR not terrain |
| same | 134-143 | axis + arrival | TEETH | a movement key must have been published, route COMPLETE, arrival within the documented epsilon rather than exactly on centre |
| same | 174-183 | jump axis | TEETH | a rising swim needs the jump axis; the fake sinks without it |
| same | 214-219 | consequence | TEETH | the detail names the air actually left and does not claim the old consequence |
| same | 256-259 | non-termination | TEETH | a swimmer below the line keeps going rather than ending the route |
| TheLiveViewAsksVanillasWalkVerdictTest | 83-87 | three-way | TEETH | lava not passable, water passable, unread not passable, from one fake |
| same | 94-112 | bytecode | TEETH | LiveBlockView must NOT override isPassable; walkVerdict must call vanillaVerdict and check isLava first; isEmptySpace must be gone. A real call-graph assertion over the class file |
| RouteExecutorReportsWhatTheWorldSaysTest (mod) | 73-89 | both directions | TEETH | walking existing ground places nothing; an empty plan clicks nothing |
| same | 113-128 | honest failure | TEETH | a click that succeeded and placed nothing ends FAILED with zero spent, retried PLACE_RETRIES times, names BOTH indistinguishable causes without ranking |
| same | 141-145 | identity | TEETH | a real placement lets the route continue, costs one block, block exists |
| same | 194-204 | both directions | TEETH | a fall is terminal and FAILED (not CANCELLED) and says 'fell' |
| AModelCanAskForARouteThroughActSetTest (mod) | 926-943 | both directions | TEETH | 0.20 off centre inside the cell is arrival with the move counted; 0.55, CLOSER, in the neighbouring cell is not, zero moves, message names the feet's cell. The old test asserted the opposite |
| same | 230-239 | gate | TEETH | a fresh RouteIntent is not active until its effective tick; stored ACTIVE it must drive the input |
| FakeWorld (modified) | 20-32 | fake honesty | TEETH | placements MUTATE, because a fake that accepted them without recording would let a two-bridge plan pass |
| DiffLeftMeansUnsampledNotGoneTest (mod) | 359-383 | reflection sweep | TEETH | mutate() throws for an unknown field type rather than skipping, so a field that stops being diffable fails the sweep |
| same | 117-118, 137-141 | both directions | TEETH | an id that stops being sampled still ships on the left; the eviction possibility is stated |
| SelfEffectsSeparateUnreadFromNoneTest (mod) | whole file | field sweep | TEETH | derived over SelfView record components; every shipped field must be observable in diff mode |
| SelfAirSeparatesUnreadableFromDrowningTest (mod) | 60-79 | absence vs value | TEETH | an unreadable air read yields NO value (every integer is a lie); absence removes exactly the air key |
| same | 101-105 | premise | TEETH | a negative air must survive vanilla's own datawatcher, or the swept range is fiction |
| same | 113-135 | three transitions | TEETH | readable->unreadable, unreadable->readable, unchanged omitted; a one-tick drop still reported |
| WorldViewDiffTest (mod) | whole file | diff | TEETH | derived over WorldViewCapture.SECTIONS |
| WorldViewDiffIsPinnedAtItsBoundaryTest (mod) | whole file | boundary | TEETH | both sides of each boundary |
| WorldViewJsonTest (mod) | whole file | serialisation | TEETH | JSON shape asserted, not the toString |
| EveryDiffSectionSaysWhenItWasNotSampledTest | 76-92 | vocabulary | TEETH | one omission per section of the tool's own vocabulary, collected not short-circuited |
| same | 99-105 | negative | TEETH | a sampled-and-unchanged section stays off the wire, or emitting unsampled unconditionally satisfies the rule above |
| same | 113-121 | third state | TEETH | sampled AFTER an unsampled baseline ships the whole state under 'now', not dressed as a change |
| BothObservationToolsBucketTimeTheSameWayTest (mod) | whole file | cross-tool | TEETH | two tools agree on the bucket for the same instant |
| TimeOfDayHasOneReductionTest | 32-34 | boundary | TEETH | negative inputs reduce into the day; -1 is 23999 |
| same | 44-46 | range sweep | TEETH | 24000 consecutive inputs all in [0, 24000) |
| same | 55-59 | idempotence | TEETH | timeOfDay(timeOfDay(t)) == timeOfDay(t) and +-24000 invariance |
| same | 74-78 | cross-tool | TEETH | 'sunrise' and 'night' agree with timeOfDay for the same instant |
| TheFallDamageNumberIsVanillas | 26-33 | ceiling | TEETH | 0/3.0 -> 0, 3.01 -> 1, 4.0 -> 1, 5.0 -> 2: the ceiling, not truncation or rounding |
| same | 45-48 | amplifier | TEETH | amplifier 0 absorbs one block, 1 absorbs two |
| same | 54-59 | lethal boundary | TEETH | 23 blocks kill a full bar, 22 does not, 22 with Jump does |
| ArmourValueIsVanillasTest | 218-241 | reflection sweep | TEETH | sweeps vanilla's own static DamageSource constants; requires >= 10 found or the sweep is vacuous |
| same | 274-282 | unblockable | TEETH | an unblockable source bypasses armour entirely |
| same | 288-300 | both directions | TEETH | the mapping must reverse to the same type AND land on an index whose getStackInSlot returns that piece |
| ScanSurroundingsCensusesTheCube...Test | 62-68 | arithmetic | TEETH | 5 rows of dirt over 9x9 = 405; a per-column surface histogram would answer 0 |
| same | 73-79 | negative | TEETH | air is not tallied and an all-air volume censuses to nothing |
| APartialInventoryReadIsNotAnInventoryTest | 94-104 | both directions | TEETH | a read failing partway yields null, not what it collected; failing at slot 0 is also null |
| same | 118-125 | index preservation | TEETH | the kept slot must keep its ARRAY index, or every remaining slot reports as changed |
| same | 168-176 | regression | TEETH | a failed read is {'unsampled':true} and must NOT contain 'cleared': the exact regression, named |
| same | 188-191 | negative control | TEETH | real consumption still reports 'cleared', so suppressing it outright would fail |
| UnloadedChunksAreNotWalkableVoidsTest | 134-151 | sentinel | TEETH | an unread cell is the unreadable sentinel, not air |
| same | 158-162 | loaded vs unloaded | TEETH | a loaded column reads stone and air correctly |
| same | 177-188 | bytecode order | TEETH | isBlockLoaded must be asked BEFORE getBlockState in idName, standable, walkVerdict and WorldScanner.blockName: a read first has already manufactured the air the check exists to disbelieve |
| same | 207-222 | regression | TEETH | the defect: drop='deep' with NO walk key is the walkable bottomless void |
| FireAndDrownAreVanillasArithmeticTest | whole file | arithmetic | TEETH | vanilla's own formulas at their boundaries |
| SlotClickModeIsVanillasVocabularyTest | whole file | protocol | TEETH | the mode vocabulary is server-side |
| TheArmourBlockReachesTheInventorySectionTest | 72-76 | content | TEETH | the right pieces in the right cells; an empty cell is a null item, not a missing row |
| AnEntityViewAnswersCanIReachAndShouldIFightTest | 41-53 | INDEPENDENT transcription | TEETH | serverAccepts/pickable re-implemented in the test from the vanilla line numbers, then compared to production over a 0..9.0 grid at every tenth. The anti-mirror pattern done right |
| same | 76-88 | grid comparison | TEETH | production and the test copy must agree at every tenth including the strict 36.0/9.0 and 3.0 boundaries |
| same | 95-104 | deny list | TEETH | the four kick targets refused; Minecart permitted because vanilla permits it |
| AnEntityListingCarriesTheCombatAnswersTest | whole file | payload | TEETH | listing carries the combat answers |
| AnEnvironmentReportsTheWeather...Test | whole file | payload | TEETH | env reports what moves the spawn gate |
| TheBlockInspectorAnswersTheSpawnGateTest | whole file | payload | TEETH | block inspector answers the gate |
| WorldViewDescriptionBudgetTest | 90-116 | budget | TEETH | char budget with the two legitimate ways past it named, so a trip says what to do |
| same | 134-138 | non-regression | TEETH | header+deferred below the pre-split size, or the split saved nothing |
| same | 165-178 | coverage | TEETH | every emitted grid key and quoted inventory/target key named in reachable text, or the model has no legend |
| same | 202-237 | legend prose | WEAK | 20+ assertions of the form header.contains("exact prose"). A rewording that keeps the meaning fails; wording that keeps the string but lies passes |
| GuiClickVerdictTest | 204-212 | identity | TEETH | every button fired exactly once, at its own live centre |
| same | 252-261 | confirmed | TEETH | CONFIRMED plus the slot emptied and slot 0 untouched |
| same | 284-288 | negative | TEETH | a click that changed nothing must NOT claim CONFIRMED, must not be ok, must carry [UNVERIFIED] |
| GuiFingerprintDistinguishes...Test | 88-91 | stability | TEETH | five recomputations on an unchanged screen are equal |
| same | 113-116 | identity | TEETH | an empty screen still names the screen class |
| same | 142-144 | literal | WEAK | assertEquals("none#0#0#0", fingerprint(null)) pins a literal string; any format change breaks it for a reason that is not a defect |
| same | 160-163 | discrimination | TEETH | scrolling must not move the structural token, only the rectangle |
| same | 211-218 | VACUOUS | VACUOUS | assertEquals(List.of().size(), 0) at line 218. List.of() is empty by construction; cannot fail. Dead line |
| GuiGesturePrimitivesTest | 169-170 | negative | TEETH | one press+release is not mode 6 |
| same | 215-229 | modifier lifetime | TEETH | shift-click is mode 1 by vanilla's own modifier read, and the modifier is RELEASED afterwards |
| same | 240-242 | addressing | TEETH | an outside click is a throw at vanilla's -999 |
| same | 254-257 | refusal | TEETH | a refused destructive gesture must not reach vanilla and nothing is clicked |
| same | 278-285 | bracket | TEETH | every packet of a drag-split is mode 5, bracketed by -999 at both ends |
| same | 304-309 | unverified | TEETH | a drag that moved nothing is NOT_CONFIRMED with [UNVERIFIED] and says only the release could have moved anything |
| same | 324-333 | double-click | TEETH | the double-click reaches mode 6; one press does not (the mutation that kills a fake double) |
| GuiListRowsTest (185 asserts) | whole file | table | TEETH | every row kind's projection against a table |
| GuiPanelStateTest | 202-217 | real vanilla | TEETH | built from a REAL vanilla chest: InventoryBasic, size 27, diamond at 0, coal at 1, empty is null not a zero stack |
| same | 235-248 | real vanilla | TEETH | merchant panel: selected trade, emerald cost 16, no cost2 on a one-stack trade |
| GuiSnapshotTest (modified) | 286-291 | presence | WEAK | five json.contains(...) on a serialised snapshot: key names, not values |
| GuiTrajectoryTest (modified) | 715-716 | fingerprint literal | WEAK | pinned to "FakeScreen#1#0#fa437", a hash of the fake's contents; any content change moves it |
| GuiSnapshotImageAssemblyTest (modified) | whole file | assembly | TEETH | image assembly asserted |
| ObserveToolsTest (modified) | 750-758 | phantom absence | TEETH | packets_tail/packet_view must NOT name 'netty-tap' and MUST name seam_netty_install: positive and negative in one test |
| same | 772-777 | registry | TEETH | seam_netty_install is registered and netty-tap is not |
| ChatReadToolTest | 135-147 | typed output | TEETH | death row is typed: kind DEATH, locale-independent key, SERVER_FACT authority, cause as a type |
| same | 166-172 | discrimination | TEETH | void vs creeper explosion are distinct causes |
| same | 192-209 | argument position | TEETH | %2$s is the killer, %3$s the item: position carries the role, not prose order |
| same | 226-231 | honesty | TEETH | a typed line from a player is PLAYER_CLAIM, not a server fact |

## D. io/transport, io/http, compat, se, flt, board, root

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| DescriptionsNameToolsThatExistTest | 258-278 | derived scan | TEETH | walks every description, extracts every snake_case token, asserts each is a real tool, a schema term, or one of six allowlisted constants each carrying its reason. read_inventory surfaced this way |
| same | 268-271 | PINS-BUG CORRECTED | TEETH | asserts read_inventory is NOT in the surface, so the premise both halves rest on is itself pinned |
| same | 286-291 | surface-wide | TEETH | no description sends the reader to compare heldSlot, a key no reply contains |
| same | 308-313 | surface-wide | TEETH | no description names the non-existent read_inventory |
| same | 318-323 | phantom absence | TEETH | no [requires:] tag names the non-existent netty-tap, under any tag spelling |
| same | 339-360 | structure | TEETH | no combinator node repeats its own description onto its oneOf branches, over every node in every schema |
| ClickSlotDescriptionMatchesVanillaTest (mod) | 139-163 | PINS-BUG CORRECTED | TEETH | the docstring records the old assertion was desc.contains("read_inventory") and passed BECAUSE the defect was present. Rewritten to the real paths and the phantom's absence |
| ToolDescriptionsMatchTheirBoundsTest (mod) | 96-106 | derived bounds | TEETH | the range is DERIVED by driving the real handler across -3..20 and watching where it stops sending; the one-sample-past mistake is named at :100-103 |
| same | added block | clamp honesty | TEETH | find_block reports the region actually swept, does NOT repeat the caller's number, and says it was clamped; a negative radius clamps to 1; an in-range radius carries no clamp note |
| ACreativeSlotIsConfirmedAgainstTheSlotTest | 187-192 | regression | TEETH | the refusal names what went wrong, says nothing was sent, and the old success string must not appear anywhere |
| same | 206-209 | offset | TEETH | the writable range (1-44) and the container-vs-inventory offset (36-44) both stated |
| same | 227-234 | honest failure | TEETH | a read failure must say re-read and name creative mode as the silent drop |
| ATransferIsConfirmedAgainstTheServersContainerTest | 79-83 | offset | TEETH | a refusal says the numbering is the container's and gives the offset |
| same | 92-95 | honest failure | TEETH | 'holds nothing' plus where to look instead |
| same | 167-170 | warning | TEETH | must warn about the window LOCK, the failure a hand-built shift-click produces |
| EnchantItemIsConfirmedAgainstTheServerTest | 88-94 | schema | TEETH | button is the only required input; the description states the legal ids and that the reply is a re-read |
| same | 107-113 | sweep | TEETH | every illegal button id refused, names the legal ids, says nothing was sent |
| same | 136-140 | precondition | TEETH | no table open is refused with the missing precondition named and no 'sent' claim |
| same | 151-153 | negative | TEETH | a table that reads unchanged is NOT_ENCHANTED, not success |
| same | 162-164 | positive | TEETH | a re-enchant that RAISES the total is success: the direction matters |
| same | 172-177 | unreadable | TEETH | a table that cannot be re-read claims nothing |
| EntityActionsAreConfirmedAgainstVanillaTest | 109-126 | both directions | TEETH | 20->17 is HIT; 20->20 is NOT_HIT; 20->20.5 (health went UP) is emphatically NOT_HIT |
| same | 137-148 | three-way | TEETH | gone-from-world is a kill, never-read is UNREADABLE, present-but-unreadable is UNREADABLE not a silent miss |
| same | 161-176 | refusal | TEETH | the kick is named, nothing sent, and a living target is NOT refused |
| same | 198-209 | disclosure | TEETH | INTERACT labelled NOT CONFIRMABLE with the reason and the dead end |
| same | 235-240 | uniform | TEETH | every unconfirmed entity action carries NOT CONFIRMED plus its own reason |
| same | 255-262 | direction | TEETH | only the flag reading what was asked for is CONFIRMED |
| AnUnknownSectionNameIsRefusedTest | 62-67 | honest refusal | TEETH | names the offending word, lists the accepted names, and produces NO view beside the error |
| same | 74-75 | sweep | TEETH | every advertised section must not be refused |
| same | 109-110 | legend | TEETH | the legend states that an unknown name is refused rather than ignored |
| ServerInfoDoesNotInventWhatItCannotReadTest | whole file | honesty | TEETH | unread fields absent, not guessed |
| InspectBlockIsAskedForBeforeAnythingIsBuiltTest | whole file | ordering | TEETH | inspect_block precedes any build |
| OpenPauseMenuIsHonestAboutNotBeingThere | 40-49 | registration | TEETH | registered under the gate-table name, at R3 because nothing goes on the wire |
| same | 52-71 | degradation | TEETH | no game means isError=true, not a thrown exception and not a success; the message names the game thread or GameBridge |
| TheOverlayIsReachableByTheSamePathAPersonUsesTest | 44-52 | registration | TEETH | open_overlay is in the registry or the test throws with the gap named |
| same | 65-79 | SOURCE SCAN | WEAK | reads DwmHotkey.java and ToolRegistry.java as text and asserts both contain 'toggleScreenForTool'. A rename in either fails the test for a reason that is not a defect; a comment containing the token would satisfy it |
| same | 82-89 | description | TEETH | must name run-mcp-overlay.bat, or a caller under run-mcp.bat cannot tell a missing panel from a broken one |
| ARecordIsWrittenAsAnObject...Test | 33-39 | serialisation | TEETH | parses as an object, is not 'Report[', every component name present |
| same | 43-48 | types | TEETH | int not quoted, boolean not quoted, double keeps its point, list stays a list |
| same | 52-58 | nested | TEETH | a record inside a record must not fall back to toString |
| same | 62-67 | regression | TEETH | Map/List/String/null shapes that already worked still work |
| S32IsTheOnlyThreeValuedAckTest | 58-67 | overclaim | TEETH | an accepted click is not a successful craft; the meaning must not imply a recipe ran |
| same | 76-84 | substance | TEETH | the rejected form must say the window is locked AND how it unlocks (C0F), or a caller retries forever |
| same | 94-97 | vocabulary | TEETH | ACCEPTED / REJECTED, and NOT_ACCEPTED must not appear as a mere negative |
| same | 114-130 | projection | TEETH | S32 must project; so must C0E and S2F |
| McpCoreHeadlessStartTest | 47-56 | smoke | WEAK | real McpCore.start() headless; asserts stderr contains '[MCP Core] initial clearance'. String-presence on log output: proves the path ran, which is the intent |
| same | 54-56 | negative | TEETH | the self-check must NOT report INCOMPLETE on a complete table, or operators learn to ignore it |
| StartupSelfCheckTest | 71-75 | VACUOUS | VACUOUS | aCompleteGateTablePassesTheStartupCheckSilently contains no assertion. reportGateGaps only throws under -Dmcp.core.gateAudit=fail (McpCore.java:486-491), so in the default warn mode this test has no failure path. Its docstring claims non-vacuity in both directions |
| same | 88-120 | both directions | TEETH | bolts an ungated tool onto the PRODUCTION registry, asserts it is reported BY NAME, runs the clean control first so a positive cannot be an artefact, and asserts -Dmcp.core.gateAudit=fail aborts |
| same | 124-135 | coverage | TEETH | every registered capability must have a Ring row or its gate silently falls back to R3 |
| L0DeclarationGateTest | 56-72 | fail-closed | TEETH | the exemption is decided from the DECLARATION, never by probing the canary; every patch is verified, signed by a trusted key and runtime-applicable, so ONLY the L0 gate can stop it |
| RootCeremonyDeclaresAnExpiryTest | 41-101 | round trip | TEETH | the expiry survives the JSON reader the client uses, and a non-positive day count is refused |
| same | 127-135 | SOURCE SCAN | WEAK | asserts the source TEXT contains 'expiresDays = 365L;' and not 'expiresDays = 0L;'. A refactor to a named constant fails the test for a reason that is not a defect |
| RootTrustUpdateChainTest | 67-121 | chain | TEETH | rollback refused and named, expired document refused naming freeze and expired, no-expiry refused, the trusted document's expiry named |
| same | 145-149 | satisfiability | TEETH | the shipped document must satisfy its own baked key set |
| EscMenuRoutesThroughTheVanillaEscapeTest | whole file | vanilla | TEETH | routes through the real vanilla escape; the reply names the screen in the way |
| EscTreeDoorsAreVanillaButtonsTest | 60-93 | real vanilla | TEETH | every door driven by the REAL actionPerformed and the REAL initGui; the screen vanilla constructs is asserted and GuiReflect.extract must publish that same id. Only Minecraft itself is Unsafe-allocated |
| GlClampToEdgePatchTest | 53-92 | SOURCE SCAN | WEAK | four src.contains(...) assertions over the vendored TextureUtil.java. Pins source text; a harmless edit breaks it |
| same | 94-165 | bytecode | TEETH | the patch itself verified over a real class file with a canary |
| DwmHotkeyEdgeTest (modified) | 146-151 | SOURCE SCAN | WEAK | reads DwmHotkey.java as text to bound a method body. The new brace-balance guard (end > body) fixes a real unbounded search, but the property is textual |
| same | 176-183 | boundary | TEETH | boundKey is -1 or a real scancode |
| ResourceTapReaderDriftTest (modified) | 48-58 | forward | TEETH | every declared resource-tap reader must require that resource's cap |
| same | 68-81 | REVERSE | TEETH | any builtin holding a resource-tap cap must be a DECLARED reader, closing the trap a new grant would otherwise open |
| RegisteredBuiltinGateCoverageTest | 123-133 | set difference | TEETH | L6 off/on must differ by exactly the one gated handle tool |
| same | 172-187 | both directions | TEETH | dev_probe denied at R3 and ALLOWED at R2 with the handler actually running |
| same | 254-261 | non-vacuity | TEETH | the inventory must be 85/84 names, or the whole audit passes on a near-empty registry. The best anti-vacuity guard in the tree |
| DebugHandleLifecycleGateTest | 84-95, 114-125, 155-169, 201-207 | layered | TEETH | each deny must come from the NAMED layer (L4 privilege / L5 capability / the agent preamble), and the allowed case must actually run |
| SeProtectedVerdictCodecTest | 28-57 | protection | TEETH | the codec SeRemoteMonitor trusts must be protected; inner and array forms covered; vanilla MC and unrelated classes must NOT be |
| BoardInitInstallsOfficialChipsTest (mod) | 35-47 | restart | TEETH | a restart must reinstall the chips and not leave a default-on chip disabled |
| same | 69-82 | drop | TEETH | a subscriber that is not a chip must still be dropped by shutdown |
| same | 92-101 | resubscribe | TEETH | a restart must resubscribe to the tick bus, not merely re-enable |
| TraceCacheTest (modified) | 143-155 | retention | TEETH | white-box on the private dispatch cache: asserts the doomed subscription IS cached first, then unreachable after cancel. The premise assertion at 144 keeps it non-vacuous |
| DebugToolsTest (modified) | 201-209 | shape | TEETH | debug_manage registered, debug_handle NOT (no action-less tool), and the unfolded names must NOT be manifest entries |
| same | 236-248 | schema-first | TEETH | a call with no action is refused by the schema before the handler runs, naming the parameter and the tool |
| same | 254-261 | unknown action | TEETH | an unknown action is refused and the valid ones listed |
| same | 282-319 | cluster invariant | TEETH | the fold is legal only if all four dimensions agree across the cluster |
| ValueCodecNarrowingTest (modified) | added block | narrowing | TEETH | every assertion FAILS on the pre-fix code: fractional, 1e300, -1e300 and exactly 2^63 all refused rather than clamped |
| same | second added block | non-regression | TEETH | every value that fits is still accepted, including nextDown(2^63) |
| KeGameDispatcherAwaitTest (modified) | 88-96, 100-113 | PINS-BUG FLIPPED | WEAK | an existing assertion was INVERTED from cancel to do-not-cancel. The new claim (a timed-out game task must be left in the queue or the next tick crashes the client) is argued from an observed crash and matches KeGameDispatcher.java:90-93's comment -- but nothing in the test can falsify it. If the original was right, this now pins a crash |
| same | 116-125 | negative | TEETH | the execution-exception path must not cancel |
| L6DebugGateThroughRegistryTest (modified) | 1268-1272 | rename | TEETH | debug_close_handle -> debug_handle, and no handle tool without L6 |
| same | 1303-1314 | schema | TEETH | every folded tool must still advertise the optional 'handle' parameter, checked in the description now that the schema moved |
| same | 1354-1380 | both directions | TEETH | an unrecognised action is denied with a handle AND without one, and a KNOWN handle-op still falls back to the name-based path when not hardened -- without that, the two denials could pass for a blanket refusal |
| CaptionButtonsLiveIT (modified) | 1462-1466 | state change | TEETH | maximize is asserted as the shell's own geometry and must NOT reach the host |
| same | 174-178 | state | TEETH | clicking maximize must actually maximize; the removed assertion (host.minimizeCalls) is no longer meaningful because the verb is gone |
| GuiClickLiveIT (modified) | 341-344 | verdict | TEETH | a live click must not be refused AND must not be UNREADABLE |
| same | 350-351 | stale | TEETH | a bogus fingerprint must be refused AND the verdict must be REFUSED_STALE specifically |

## E. dwm/qml (10 untracked) + support files

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| QmlElementNamesAreReadableTest | 95-101 | real tree | TEETH | every published control named by an objectName, not an index path; ids unique |
| same | 145-149 | geometry | TEETH | a card's region and its switch's must differ, or the card shadows its control |
| same | 185-227 | resolution | TEETH | published id is the nearest ancestor's objectName; empty name must not end the walk |
| same | 252-270 | the defect | TEETH | two anonymous MouseAreas both resolve to window; NEITHER may keep it |
| same | 301-330 | bound | TEETH | lookup depth bounded, exceeds deepest shipped name, falls back to a path outside |
| same | 349-375 | churn | TEETH | unchanged tree compares equal; a moved control IS a difference |
| ShippedShellNamesEveryControlIT | 76-101 | live walk | TEETH | clicking navX must reveal PageX; settings count pinned at 20 |
| same | 120-146 | expander | TEETH | 14 closed vs 18 open interior controls; both add exactly four rows |
| same | 71, 117 | ASSUME | WEAK | both tests assume-true needs-a-display and self-skip on headless CI |
| QmlTheElementTableFollowsThePageTest | 38-52 | SOURCE SCAN | WEAK | asserts a latch is gone and three identifiers are present in QmlGuiScreen.java |
| same | 55-60 | SOURCE SCAN | WEAK | asserts a COMMENT is present; asserting on prose in a source file |
| EverySkijaEntryIsGuardedTest | 43-56 | SOURCE SCAN | WEAK | three method bodies read as text, each asserted to contain the guard calls |
| same | 60-72 | SOURCE SCAN | WEAK | open() failure path must not call closeQuietly. Textual |
| NoWrapIsReportedAndRetriedTest | 54-95 | SOURCE SCAN | WEAK | four method bodies asserted by content: reportNoWrap, wrapFailure, lastError, hasSurface |
| same | 131-162 | bytecode + text | WEAK | the cooldown field is read reflectively; three more bodies are substrings |
| CloseInsideADispatchIsDeferredTest | 48-57 | SOURCE SCAN | WEAK | close() body must contain dispatchDepth, closeRequested, closeQuietly |
| same | 63-81 | ORDERING on text | WEAK | increment before call.run() has teeth about order; the rest is presence |
| DismissReleasesTheSurfaceTest | 59-64 | SOURCE SCAN | WEAK | the negative is a substring test over a whole method body |
| same | 86-110 | SCAN + sweep | WEAK | three source greps plus a real scene sweep; the sweep is the strong half |
| QmlElementsArePublishedAsVanillaButtonsTest | 78-82 | SOURCE SCAN | TEETH | extends GuiButton, dispatch is the clicked signal |
| same | 83-87 | precedent FIXED | TEETH | file documents that it once asserted contains-class, which every Java file satisfies, and deleted it |
| same | 96-109 | body extraction | TEETH | drawButton body must be EMPTY, asserted by extraction not by call names |
| same | 117-160 | SOURCE SCAN | WEAK | four more substring assertions: enabled, visible, uiScale, floor/ceil/max |
| same | 177-181 | SOURCE SCAN | WEAK | pinned to an exact multi-line string; whitespace-sensitive |
| same | 216-235 | ORDERING | TEETH | publish must come AFTER surface.frame; initGui must NOT publish |
| same | 254-258 | ORDERING | TEETH | publish must CLEAR unconditionally and before the first add |
| ShippedPagesOfferNoInertControlsTest | 76-92 | scene sweep | TEETH | every FluentButton with neither onClicked nor enabled-false is flagged |
| same | 98-108 | guard self-check | TEETH | CLICK_ONLY must still name a shipping control that declares the clicked signal |
| same | 113-133 | SCANNER SELF-TEST | TEETH | inline fixtures exercise all four scanner outcomes |

### Support classes with no @Test (listed so nothing is silently omitted)

    core/.../drivers/act/BodySim.java              0 @Test  body stepping for the ledge/sneak tests
    core/.../drivers/act/FakeActuator.java (mod)   0 @Test  the actuator every act test drives; models unPressAllKeys
    core/.../drivers/plan/FakeWorld.java     (mod)  0 @Test  BlockView fake whose placements MUTATE
    core/.../drivers/craft/FakeCraftWindow.java      0 @Test  CraftWindow fake for the craft tests
    core/.../drivers/craft/CraftBench.java           0 @Test  empty Container subclass for InventoryCrafting
    core/.../eval/EvalHarness.java                 0 @Test  wires the real appliers to SimWorld
    core/.../eval/EvalSuite.java                   0 @Test  the 23 tasks, scored by SelfPlayEvalTest
    core/.../eval/GoalPolicy.java                  0 @Test  the goal-directed policy (test-owned)
    core/.../eval/SelfPlayEval.java               0 @Test  the runner and renderer
    core/.../eval/SimBody.java                    0 @Test  shared moveEntity collision and fall state
    core/.../eval/SimCraftWindow.java             0 @Test  real-container-layout CraftWindow
    core/.../eval/SimMob.java                      0 @Test  the transcribed mob
    core/.../eval/SimWorld.java                    0 @Test  the substrate (BlockView + ActActuator)
    dwm/.../qml/SourceScan.java                   0 @Test  stripComments, bodyOf, matchingDelimiter
    core/.../drivers/world/Probe.java             1 @Test, 0 assertions -- separate finding below


### drivers/world/Probe.java (untracked) -- separate finding

| file | line | shape | verdict | reasoning |
|---|---|---|---|---|
| Probe.java | 7-15 | VACUOUS / SCRATCH | VACUOUS | one @Test that reflects over DamageSource fields and prints them. Zero assertions, no javadoc, class named Probe. A scratch probe left in src/test; it cannot fail. Delete it or move it out |


## F. The six failure shapes, each with real instances

### 1. Vacuous / tautological -- FOUND (5 live, 2 self-corrected)

| file:line | instance |
|---|---|
| core/.../drivers/gui/GuiFingerprintDistinguishesSameShapedPagesTest.java:218 | assertEquals(List.of().size(), 0). List.of() is empty by construction. Cannot fail. Dead line |
| core/.../eval/EvalSuite.java:402 | noBlockItem = stone == 0 inside T07's pass condition. A block is never an item, so this is a tautology. Harmless (ANDed with two real facts) but it reads as load-bearing |
| core/.../eval/SelfPlayEval.java:39-41 with SelfPlayEvalTest.java:41 | the only-prefix filter continues; the test then asserts an empty failure list is empty. A typo in -Dtest yields a green all-pass report for zero tasks. Nothing asserts scored.size() > 0 |
| core/.../StartupSelfCheckTest.java:71-75 | aCompleteGateTablePassesTheStartupCheckSilently has no assertion. reportGateGaps only throws under the fail-mode property (McpCore.java:486-491), so in warn mode the test has no failure path |
| core/.../eval/EvalHarness.java:203-207 | run(int maxTicks) delegates to an always-false predicate and can only return false. No caller. Dead API that reads like a completion signal |

Self-corrected by the same session, recorded because they are the shape:

| dwm/.../qml/QmlElementsArePublishedAsVanillaButtonsTest.java:83-87 | documents that it once asserted contains-class -- which every Java file satisfies -- and deleted it |
| dwm/.../qml/QmlTheElementTableFollowsThePageTest.java:55-60 | currently asserts a COMMENT is present in a source file: prose-presence, one step weaker than code-presence |

### 2. Bug-pinning -- FOUND (2 live, 2 fixed)

LIVE:

| file:line | instance |
|---|---|
| core/.../ke/KeGameDispatcherAwaitTest.java:88-96 and 100-113 | asserts NOT cancelled on the timeout and interrupt paths. The diff shows this was assertTrue(timeout path must cancel) and was INVERTED. The new claim is argued from an observed live crash and matches the comment at KeGameDispatcher.java:90-93 -- but nothing in the test can falsify it. If the original reasoning was right, this now pins a client crash. The closest live relative of the read_inventory precedent |
| core/.../eval/SimWorld.java:1314-1329 with EvalSuite.java:1037 | SimWorld.attackEntity applies its OWN reach refusal before damaging. T15's beyond-the-bound arm asserts hits==0, which the substrate guard satisfies as much as InteractController does. The substrate is test-owned, so the fake defines the truth |

FIXED by this session:

| file:line | instance |
|---|---|
| core/.../io/transport/ClickSlotDescriptionMatchesVanillaTest.java:139-163 | the docstring records the old assertion was contains-read_inventory and passed BECAUSE the defect was present |
| core/.../io/transport/DescriptionsNameToolsThatExistTest.java:268-271 | the premise is now pinned as an assertion: read_inventory is NOT in the surface, and the scan that found it is derived (258-278) |

### 3. Early-return guards -- FOUND (4, and 2 are the known precedents)

| file:line | instance |
|---|---|
| core/.../drivers/act/ActRuntimeGatesArePinnedAtItsBoundaryTest.java:109-118 | THE KNOWN PRECEDENT, FIXED. The loop capped at 8, equal to the top of the 4..8 band, so a maximum draw left the axes neutral and the test failed about one run in three. Cap now 32, band asserted separately at 114-118 |
| core/.../drivers/plan/TheSearchCeilingDoesNotFireOnAShortGoalTest.java:91-98 | THE SECOND KNOWN PRECEDENT, FIXED. The previous version had an early return when the plan was found. Now the premise is asserted and the refusal must not blame the ceiling. The docstring names the escape hatch as what produced the defect |
| core/.../eval/SelfPlayEval.java:39-41 | LIVE. A typo'd -Dtest runs zero tasks and reports all-pass; SelfPlayEvalTest:41 asserts an empty failure list is empty |
| core/.../drivers/plan/ALadderIsClimbedBecauseTheForwardAxisIsHeldTest.java:187-195 | LIVE. The loop returns inside the match branch; if the planner stops offering that edge the test asserts nothing and passes. The comment says not-offering-is-equally-correct, which is exactly why it can go quiet |

Not a skip: ArmourValueIsVanillasTest.java:274-277 returns early when a source is not unblockable, but the caller at :221-223 only invokes it in the unblockable branch, so the guard is unreachable rather than a hole.

### 4. Self-fulfilling harness -- FOUND (5)
| file:line | instance |
|---|---|
| eval/SimWorld.java:1314-1329 | attackEntity re-implements the reach refusal inside the substrate; T15 asserts on it |
| eval/SimWorld.java:927-929 | reachDistance() hardcodes 5.0 citing LivePlayerActuator; production reads PlayerControllerMP |
| eval/GoalPolicy.java whole | the policy is test-owned. T18-T23 assert the controllers compose under a policy the same commit wrote |
| eval/EvalSuite.java:355 | T06 scores the plan through the harness route factory, not RoutePlanning.executorFor. Declared in KNOWN_GAPS:130-140 |
| eval/AMobThatActs...Test:108-113 | expected health computed from the mob own counter in the substrate that applied the damage |

What the substrate gets RIGHT, which is a lot:

    SimWorld.java:54-75     every physics constant carries the vanilla line it came from

    SimWorld.java:102-137   KNOWN_GAPS names 7 divergences, including the one that would
                             invalidate T08: a descending body is lifted onto a ledge with
                             NO jump (measured y=65.12 with jump forced false)

    SimBody.java:1-30        ONE collision implementation shared by player and mobs

    SimCraftWindow.java:14-40 the armour-slot gap between the two containers is a field,
                             not arithmetic that happens to come out right once

    SimMob.java:1-70         per-type followRange, duty cycle, 30 deg/tick turn, stale
                             path, width-squared reach, invulnerability window, each cited

The disclosed weak point: sight is a 0.2-block walk of the eye-to-eye ray
(SimWorld.java:493-501, declared at KNOWN_GAPS:114), not a traced ray.

### 5. Randomness / timing -- FOUND (4; the known precedent is FIXED)

| file:line | instance |
|---|---|
| act/ActRuntimeGatesArePinned...Test:109-118 | THE KNOWN PRECEDENT. The wait ceiling equalled the value under test (8 == top of the 4..8 band) so it failed about one run in three. Fixed: cap 32, band asserted separately |
| act/TheWalkDelayIsSkewed...Test:105-127 | two assertions over 6000 ThreadLocalRandom draws: skewness > 0.15 and COUNTS[4] > COUNTS[8], plus every value 4..8 reachable and the mean in (5.2, 5.9). The skew margin is about 10 sigma and the count assertion needs no moment at all, so it is well built -- but it IS a sample, and the one place a green row depends on a random draw |
| act/TheReactionDelayIsOneDraw...Test:57-67, 84-96 | 300 draws each, correct by construction: the first asserts the draw is IDENTICAL across the wait (the defect was a re-draw), the second asserts firstKey == drawn+1 where MAX+1 is derived not copied |
| eval/EvalSuite.java whole | the only randomness in the eval is the reaction delay, and the stated rule (:43-46) is that no task asserts a tick COUNT. Verified: every runMove/runInteract/run call site discards the returned count and asserts position, block identity, inventory or health (:157, :186, :401, :547, :1326) |

Three docstrings are stale: EvalHarness.java:161, EvalSuite.java:44 and GoalPolicy.java:813 still
describe the old uniform 4..8 draw. The draw is a half-normal on a floor clipped at 8
(NavController.java:1367-1371). The band is unchanged, so no assertion is wrong.

### 6. Mirror assertions -- FOUND (3, one instructive counter-example)

| file:line | instance |
|---|---|
| act/LookTrackingDoesNotSelfTerminateTest:74-75 | expectedYaw() returns LookController.anglesTo, the same production method whose output the controller must match. If anglesTo is wrong both sides move together. The diff also widened tolerance 1e-3 to 0.15 here |
| act/TheLookProfileIsShaped...Test:31-33 | progress() delegates to LookController.shapedProgress, so the expected value is computed by the code under test. The surrounding assertions (ends at 0 and 1 per ratio; degenerate and NaN ratios) are independent |
| act/TheLookProfileIsShaped...Test:102-105 | assertEquals(1.805D, VELOCITY_PEAK_MEAN, 0.0) pins a literal to itself; the differs-from-pi/2 assertion beside it carries the information |

THE COUNTER-EXAMPLE, in the same tree:

    world/AnEntityViewAnswersCanIReachAndShouldIFightTest.java:41-53 re-implements serverAccepts
    and pickable from the vanilla line numbers IN THE TEST FILE, then :76-88 compares production
    against that independent transcription at every tenth of a block from 0 to 9.0, including the
    strict 36.0/9.0 and 3.0 boundaries. If the two drift, the grid catches it. That is what
    LookTrackingDoesNotSelfTerminateTest should be.

## G. Summary count per verdict

Over the 121 test-bearing files (101 untracked + 33 modified, minus 13 support classes):

    TEETH     74 files   the assertion can fail for a reason that is a real defect
    WEAK      40 files   it can fail, but the property named is not the property asserted
    VACUOUS    4 files   it cannot fail
    PINS-BUG   3 files   green because the current behaviour is there
    -------------------
    total    121

(The three dual-verdict files are counted once, at the weaker verdict, and appear in both

tables: GuiFingerprintDistinguishesSameShapedPagesTest, StartupSelfCheckTest, SelfPlayEvalTest.)


The shape of the distribution, which is the useful part:

  - The eval package (14 files) is the strongest work in the tree. Its discipline is explicit:

    facts not messages, both directions, the fixture own self-check folded into the pass
    condition (T20:1513), and a KNOWN_GAPS list naming the substrate own divergences. The
    two things wrong with it are the substrate re-implementing the reach check and the
    test-owned policy, both disclosed.

  - The WEAK cluster is concentrated in source-text scanning (18 files) and description-prose
    assertions (6 files). Together that is 24 of the 40.

  - Every VACUOUS instance is a one-line thing, not a file-level design problem.

  - Both PINS-BUG instances are in files whose docstrings argue the position at length. That
    argues the reasoning was done; it does not argue it was right.


## H. Top 10 files that most need rewriting

1. core/src/test/java/net/marcloud/mcp/core/ke/KeGameDispatcherAwaitTest.java
   The only live PINS-BUG with a crash attached. An existing assertion was INVERTED from cancel
   to do-not-cancel; the justification is prose about an observed live crash, and no assertion in
   the file can falsify it. Either the original was right (this pins a client crash) or the flip
   is right (and then nothing here proves it). Needs a decision recorded with evidence.


2. core/src/test/java/net/marcloud/mcp/core/eval/SimWorld.java (with EvalSuite T15)
   attackEntity at :1314-1329 re-implements the reach refusal, so T15 beyond-the-bound (:1037)
   cannot distinguish the controller refusing from the substrate refusing. The substrate is
   test-owned, so the fake defines the truth. Either move the refusal out of the substrate or state
   that T15 measures the substrate. As written the file claims the second and tests the first.


3. core/src/test/java/net/marcloud/mcp/core/eval/SelfPlayEval.java (with SelfPlayEvalTest.java)
   SelfPlayEval.java:39-41 continues on the only-filter and SelfPlayEvalTest.java:41 asserts an
   empty failure list is empty. A typo'd -Dtest yields a green all-pass report for zero tasks --
   the exact reports-success-while-measuring-nothing shape. One line fixes it: assert
   scored.size() > 0, and == 23 when only is empty.


4. core/src/test/java/net/marcloud/mcp/core/drivers/act/LookTrackingDoesNotSelfTerminateTest.java
   expectedYaw() at :74-75 calls LookController.anglesTo, the production method whose output is
   under test. A mirror assertion, on top of a tolerance the diff widened from 1e-3 to 0.15.
   AnEntityViewAnswersCanIReachAndShouldIFightTest.java:41-53 is the fix already in the tree.


5. core/src/test/java/net/marcloud/mcp/core/StartupSelfCheckTest.java
   :71-75 has no assertion and no failure path in the default warn mode, while its own docstring
   claims non-vacuity in both directions. The second test (:88-120) is excellent and does the work;
   the first should assert on captured stderr as McpCoreHeadlessStartTest.java:47-56 already
   does, or be deleted.


6. core/src/test/java/net/marcloud/mcp/core/drivers/gui/GuiFingerprintDistinguishesSameShapedPagesTest.java
   :218 is assertEquals(List.of().size(), 0) and cannot fail. The rest of the file is good (stability
   over 5 recomputes, discrimination between same-shaped pages, scroll must not move the
   structural token). One dead line in an otherwise sound file, in a file whose job is to fail.


7. core/src/test/java/net/marcloud/mcp/core/drivers/plan/ALadderIsClimbedBecauseTheForwardAxisIsHeldTest.java
   :187-195 returns inside the loop and asserts nothing when the edge is absent. The comment (not
   offering the edge is equally correct) is the reason it can go quiet. The other three tests in the
   file are TEETH; this one needs the premise asserted the way TheSearchCeilingDoesNotFire...
   Test.java:91-98 now does.


8. core/src/test/java/net/marcloud/mcp/core/drivers/world/Probe.java
   14 lines, one @Test, zero assertions, pure reflection println over DamageSource. A scratch
   probe committed into src/test. Delete it. Smallest item on this list, zero-effort fix.


9. dwm/src/test/java/net/marcloud/mcp/dwm/qml/QmlTheElementTableFollowsThePageTest.java
   All three tests assert on the TEXT of QmlGuiScreen.java, and one (:55-60) asserts a COMMENT is
   present. This is the bottom of the weakest cluster -- 18 files scan source text -- because the
   property it claims IS testable: QmlElementNamesAreReadableTest.java:349-375 already builds real
   Item trees and compares published tables. Either accept the scan as a deliberate proxy and say so
   in the class name, or move the property to the bridge and keep only the latch check here.


10. core/src/test/java/net/marcloud/mcp/core/drivers/act/ActRuntimeGatesArePinnedAtItsBoundaryTest.java
    The flake at :109 was FIXED correctly and is the model for the other three timing items, but the
    same diff loosened :132-135: assertEquals(0.8f, moveForward(), 1e-6) became assertTrue(Math.abs(
    moveForward())==1.0f), which discards the sign, and strafe now accepts 0 OR 1. A mirrored axis --
    the exact defect T03 exists to catch -- would pass this line. Restore the sign.


Runners-up, one line each:

  eval/EvalHarness.java:203         run(int) can only return false; delete or give it a real predicate

  eval/EvalSuite.java:355           T06 scores the plan through the harness route factory, not RoutePlanning

  eval/EvalSuite.java:402           noBlockItem is a tautology inside a real pass condition

  eval/EvalSuite.java:317           stayedNear is true of a controller that refuses at tick 1

  eval/SimWorld.java:927            reachDistance() is a hardcoded transcription nothing can falsify

  act/ActRuntimeTest.java:153       0.5f to 1.0f is a re-pin; assert the snap property instead

  act/TheWalkDelayIsSkewed...:124  the only distributional assertion in the tree; fine, but a sample

  eval three files                   docstrings still describe the old uniform 4..8 draw

  18 files                          source-text scanning across dwm/qml, compat, io.transport


## I. What this audit did NOT do

  No file in D:/Project/MCPClient was created, edited, deleted, staged or committed.
  No maven, no test run, no game launch. The tree does not currently compile (SimWorld.canSee and
   damagePlayer are called from SimMob.java:246 and :291); that was excluded from scope by the
  brief and is not counted as a finding here.
  No verdict here is backed by a run. Every judgement is from the assertion as written and the
  production code it calls, so the TEETH/WEAK line on any distributional or concurrency test is
  an upper bound on confidence, not a measurement.
