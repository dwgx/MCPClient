# The test census: what is counted, what is green, and how to check it

2026-10-04. Written because this repository has published **at least thirteen different totals**
for "all tests" and every one of them was a real reading of something, and none of them said
what. The counting convention was the defect. This document fixes the convention; the command in
§1 produces every number in it; the tool in §6 is what enforces it.

**Read this before quoting a test count from anywhere, including from this file.** §6.4 is the
rule that applies to this document as much as to any other.

---

## 0. The short version

```bash
export JAVA_HOME='D:\Software\Developer\jdk\25'
python scripts/test-census.py --run
```

That prints two tables and a verdict. Measured on 2026-10-04 at `1f72334`, on a clean tree:

```
surefire: 2542 green / 2543 tests, 0 failures, 0 errors, 1 skipped, 0 ghosts, 0 uncollected
failsafe:    0 green /    0 tests, 0 failures, 0 errors, 0 skipped, 0 ghosts, 22 uncollected
not judged (this invocation did not cover it): failsafe -- see test-census.md section 3
```

Read as: the **surefire** population is **2543 tests, of which 2542 ran and passed and 1 was
skipped**, across **10 reactor modules** and **405 collectable classes** — of which `client`
contributes 4 — with **zero** ghost
reports and **zero** classes that were collected by name and never asked anything. The
**failsafe** population holds **22 `*IT` classes that `mvn test` does not reach**, and the exit
code is **0** because `mvn test` did not claim to cover them (§3).

Three things a reader must not do with that output, and all three have been done here:

- **Do not add the two populations.** Different plugins, different phases, different skip
  flags (§1.3).
- **Do not call 2543 "all tests".** The 22 `*IT` classes are outside it, *by default* (§3).
- **Do not call 2543 "2543 green".** **2542** ran. One did not, and it is named in §2.1.

---

## 1. What is counted, and the two populations

The repository has **10 Maven modules** in its reactor (`pom.xml:13-20` plus `pg/pom.xml:33-37`),
of which **8 declare a test dependency** and **6 actually have tests**. They split into two
populations that are never summed.

### 1.1 The surefire population -- the default unit tests

Collected by `maven-surefire-plugin` in the `test` phase. Selected by surefire 3.2.5's default
includes, which **no pom in this repository overrides**:

```
**/Test*.java  **/*Test.java  **/*Tests.java  **/*TestCase.java
```

A file is in this population **iff its name matches**. `core/src/test/java` holds **374** `.java`
files; **342** match. The other 32 are the `*LiveIT` scaffolds (§3) and the test helpers
(`BodySim`, `SimWorld`, `GoalPolicy`, `FakeActuator`, ...), which carry no `@Test` and are
support code.

### 1.2 The failsafe population -- the live integration tests

Collected by `maven-failsafe-plugin`, bound to `integration-test` and `verify`. Selected by
failsafe's default includes:

```
**/IT*.java  **/*IT.java  **/*ITCase.java
```

**22 classes**: `core` 7, `dwm` 14, `client` 1. **Every failsafe plugin in this repository sets
`<skipITs>` to a property whose default is `true`** — `core/pom.xml:46-47`, `dwm/pom.xml:32`,
`client/pom.xml:19` — so a plain `./mvnw.cmd verify` executes **zero** of them and reports
BUILD SUCCESS. That is not a bug in the census; it is the state §3 describes.

### 1.3 Why they are never summed

A combined figure is not merely imprecise, it is **unreproducible**: whether the `*IT` classes
appear in it depends on three different `-D` flags spelled three different ways, so the same
command on the same tree yields different totals for different readers. Every whole-repo number
quoted in this repository's history that included both is therefore a number nobody could
re-derive. The census prints them separately and prints no combined figure. **A reader who wants
"everything" must state which two populations and which flags.**

### 1.4 The module list is parsed, never written down

`scripts/test-census.py` walks `<modules>` recursively from the root pom. Recursion is the point:
`pg/` is a `pom`-packaging aggregator, and a non-recursive read sees `pg` and stops, dropping
`pg-api`, `pg-engine` and `pg-maven-plugin` from the census without any error. **That is exactly
how `pg-engine`'s five tests went missing from a whole-repo total**, and §4.1 is the account.

---

## 2. "Green" is defined, and a skip is not green

This is the definition the Owner asked for. Four states, and the words for them do not overlap:

| state | what it means | counts as green? |
|---|---|---|
| **passed** | the method ran and every assertion held | **yes** |
| **failed** / **errored** | the method ran and did not hold | no — build fails |
| **skipped** | the method was **collected** but its body never executed | **no** |
| **absent** | no report exists for the class at all | **no** — it was never asked anything |

**A skip is not a green.** Surefire counts an assumption violation as `SKIPPED` and the build as
`SUCCESS`. The outcome shape is identical to a passing test and the evidentiary content is not.
Reporting "2543 green" when 2542 ran and 1 did not is the same error class as this repository's
worst recurring one.

### 2.1 The complete inventory of skips and absences in the tree today

Measured 2026-10-04 at `1f72334`. **There is no `@Disabled`, no `@Ignore`, and no
`@ParameterizedTest`/`@RepeatedTest`/`@TestFactory`/`@Nested` anywhere in the repository** — all
zero, verified by grep across every module's `src/test`. So there is exactly one skipped method
and no annotation-level disabling to reason about:

| what | where | why | verdict |
|---|---|---|---|
| **1 skipped method** | `core` `net.marcloud.mcp.core.compat.patches.LevelSchemePathGuardPatchTest.aSymlinkOutOfSavesIsRefusedByContainmentButAcceptedByVanilla` | `Assume` on Windows symlink privilege: the temp filesystem refused `Files.createSymbolicLink` with `A required privilege is not held by the caller`. It asserts a property of **vanilla 1.8.9** that the vendored source does not settle. | **accepted.** Named, environmental, identical in every run this session |
| **29 `Assume.assumeTrue` call sites in `core`** | 27 runtime conditions, **8 of them constant-false** | Constant-false assumptions are structural: the arming is unreachable without a live game or a platform the branch cannot be on. | **the census's answer**: a constant-false assumption inside a class the default run does not collect produces no skip and no failure. It is counted in §3, not here |
| **2 misnamed drivers** | `client/ServerJoinTest.java`, `client/SmokeTest.java` | `main()`-driven headless launchers with **zero** `@Test` methods, in files named `*Test`. Surefire collects the file by name, finds nothing runnable, writes no report. | **naming defect.** The census reports them under their own label and does **not** fail on them: nothing was lost, and a census that is permanently red over a rename is one nobody reads |
| **22 `*IT` classes not executed** | `core` 7, `dwm` 14, `client` 1 | `skipITs` defaults true in all three poms | **structural; §3** |
| **0 failures, 0 errors, 0 ghosts, 0 uncollected** | — | — | — |

### 2.2 The rule

> A count is green only if the method **ran**. Skipped is reported as its own number. Absent is
> reported as its own number. Never write `N/N green` where N includes either.

---

## 3. The 22 `*IT` classes: present, skipped by default, and one of them is broken

This is the largest honest gap between "the suite is green" and "the tests ran".

| module | `*IT` classes | default | flag that runs them |
|---|---|---|---|
| `core` | 7 | skipped | `-Dcore.it.skip=false` |
| `dwm` | 14 | skipped | `-Ddwm.live.skip=false` |
| `client` | 1 (`SmokeIT`) | skipped | `-Dsmoke.skip=false` |

Four facts a reader needs, all measured on 2026-10-04. **Three of them were open this morning
and are now closed**; the section records both the defect and the fix, because a fix without its
measurement is a claim.

1. **The documented `dwm` command did not work; it now does.** `dwm/pom.xml` said
   `./mvnw -pl dwm -am verify -Ddwm.live=false`. The property that pom reads is `dwm.live.skip`
   (`dwm/pom.xml:32`, `:147`). **`dwm.live` is defined nowhere in the repository**, so the
   documented command set an unknown property, left `skipITs` at its default `true`, and answered
   a live request with `Tests are skipped.` and BUILD SUCCESS with all 14 classes unrun.
   **Fixed and verified**: `./mvnw.cmd -pl dwm verify -Ddwm.live.skip=false` runs **45 tests,
   0 failures, 0 errors, 0 skipped**. They were always real tests and always green; they were
   unreachable through the command the file printed.

2. **`core`'s 7 `*IT` classes run, and every one of them skips.** With
   `./mvnw.cmd -pl core -am verify -Dcore.it.skip=false`: **11 tests, 0 failures, 0 errors,
   11 skipped**, BUILD SUCCESS. The skips are `LiveGameGate` doing its job — no game is attached,
   and it turns that into an assumption rather than a fake pass.
   **The `-am` is load-bearing and was the whole of the earlier failure.** Without it `core`
   resolves the *installed* `client` jar, which predates `UnknownBlockStates.nullArrivals()`, and
   11 `NoSuchMethodError`s name a method that plainly exists in the source. A stale artifact, not
   a defect — exactly what `guarantees.md` §0 and §6.17 have been saying.

3. **`client`'s `SmokeIT` was structurally unable to pass; it now passes.** Its first failure was
   `AssertionError: SmokeDriver should exit 0 (reached in-game world) expected:<0> but was:<3>`,
   and exit 3 is `SmokeDriver`'s own "never entered world" (`SmokeDriver.java:80`). Two
   independent causes, both in the fork's command line:
   - **the agent was not armed.** The bare fork hits KI-4 — Netty 4.2 binds a
     `LocalServerChannel` on an Nio group instead of a `LocalIoHandler`-backed one and throws
     `IoHandle of type LocalServerChannel$LocalServerUnsafe not supported`. The defect is fixed
     by the bytecode patch `Ki4LocalServerChannelPatch`, registered by default (`Compat.java:69`)
     and applied **only** by `-javaagent` — which is what `scripts/run-mcp.bat:104` passes and
     this fork did not. The test was exercising a path the product never takes.
   - **the argfile was looked up in the wrong directory.** `jvm-args-jdk25.txt` lives in
     `scripts/`; the test looked for it at the repo root, `if (argfile.isFile())` then quietly
     did nothing, and the fork lost every `--add-opens` / `--enable-native-access` /
     `-Dfile.encoding` line. A silent default inside a test.

   **Fixed and verified**: the fork passes `@scripts/jvm-args-jdk25.txt` and
   `-javaagent:core/target/core-1.8.9-all.jar`, and each missing precondition is an `Assume` that
   names itself rather than a `fail`. `./mvnw.cmd -pl client verify -Dsmoke.skip=false` now
   reports **`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`** in about 18 s — and since
   `SmokeDriver` halts with 0 only when `inWorld` is true, **the client has now booted to an
   in-game world under a headless fork.** It had never been able to report that before.

4. **Why CI never saw any of this.** `.github/workflows/build.yml` runs
   `./mvnw -B -ntp verify -Dsmoke.skip=false`, but a runner has neither the shaded jar nor
   `test_run/assets`, so both original `Assume`s skipped and the job was green. **The green was
   the assumptions, not the test.** The census step added to CI today does not reach the ITs
   either — it judges surefire (§6.1) — so the IT gates stand, and giving CI the assets is not
   this repository's call.

---

## 4. The de-ghost rule, and why it exists

### 4.1 `pg-engine`: the module that was dropped, and why

`pg/pg-engine` holds **one** test class, `net.marcloud.pg.engine.HardenEngineTest`, with **5**
tests. It is green, reproducible in one line, and it has been left out of a whole-repo total.

The mechanism, exactly:

- `pg-engine` is a child of the **`pg/` aggregator**, not of the root pom. Any module list read
  only from `pom.xml:13-20` sees `pg` and stops.
- The obvious way to count it alone then fails too: **`./mvnw.cmd -pl pg-engine test` does not
  resolve.** Maven answers `Could not find the selected project in the reactor: pg-engine`,
  because `-pl` takes a *path*. The two working selectors are `-pl pg/pg-engine` and
  `-pl :pg-engine`.
- So the sequence is: a per-module loop fails on `pg-engine`, and — because the failure was
  observed through a pipe — the module contributes **zero** to the total rather than an error.
  `.agent/HANDOFF.md:80-82` records this exact shape happening twice before: *"a `-pl pg-engine`
  selector that does not exist in this reactor, whose error vanished through a pipe into `grep`."*

**The fix is structural, not editorial.** The census parses the module list out of the poms, so
there is no list to get wrong, and the `pg-engine` row is in the table above: **5 tests, 0
failures**. Any total produced by this tool includes it by construction.

### 4.2 Ghost reports: a report whose class no longer exists

A probe class deleted after its last run leaves its report in `target/surefire-reports/`. A naive
sum of the directory counts it exactly as loudly as a real test. **The join is the rule**: every
report is matched against a source file under `<module>/src/test/java`; a report with no source is
a **deletion**, not a test, and is excluded **and printed**.

This is not hypothetical. `guarantees.md` §0 documents three such ghosts
(`ScratchNullTraceProbeTest`, `ScratchVarintProbeTest`, `ScratchWorldNullProbeTest`) inflating a
total by exactly 3; `.ai-notes/docs/audits/2026-10-02-wave4-accessgate.md:220-227` records a
run reporting **1680 tests / 14 failures** because of one phantom failure; and
`guarantees.md` §6.17 is the standing rule.

**"Clear the directory" is not a rule.** Somebody else's probe run clears it for you.
`clean` in the command is; the join is the backstop.

### 4.3 The other direction, which is worse

A **collectable source with no report** is a test that was never asked anything. It is invisible
to anyone summing the directory, because it contributes nothing to sum. Today the census finds
**2** (`client/ServerJoinTest`, `client/SmokeTest`, §2.1) and both are name accidents rather than
real losses. The census still fails on them, because the check is what makes the day they *are*
real losses visible.

### 4.4 What the census refuses to do

It **refuses to count** two reports for one class, and exits rather than summing them. A
duplicated report means the directory was not cleaned, and the sum of a stale and a fresh report
is a number no single run produced — which is the entire subject of this document.

---

## 5. Every number this repository has published, and what each one measured

The reason §1 exists. Each row was checked against the tree; **"evidence on disk" is the honest
column**.

| number | scope | what it actually measured | evidence today |
|---|---|---|---|
| 1133 | core | one run at a commit that no longer exists as a git object, in a worktree that is gone | **none** |
| 1331 | core | 2026-09-30; the canonical example of a green number certifying nothing — the jar was stale because only `test` ever ran | **none** |
| 1477 / 1578 / 1598 / 1607 | 6 modules + "Python harness 46" | within-day snapshots of a moving tree; mixes JUnit tests with Python functions | **none** |
| 1629 / 4 red | core | the 205-entry deferred tree at `c877231`, 18 commits behind | superseded, self-declared |
| 2025 | core | 323 live reports joined against the source tree — **the method in §4.2, done by hand** | method survives; the number does not |
| 2028 | core | the same directory summed **without** the join | a documented wrong answer |
| 2076 … 2145 | core | ~15 successive console lines over two days, each overwritten by the next run | prose only |
| **2543** | **the surefire population, whole reactor** | **what §1.1 defines, at `1f72334`** | **reproducible: §6** |
| 2146 | — | **does not appear anywhere in this repository** | none |
| 2547 / 2548 | — | **do not appear anywhere in this repository** | none |
| 52 / 58 / 64 / 69 | `scripts/*.py` | `def test_` functions — a **different unit**; not comparable to any JUnit total | re-derivable from source |
| 21/23, 26/26 | `SelfPlayEvalTest` | **tasks inside one test method** — a fourth unit, reported as `tests="1"` | — |
| 84 tools | live client | `tools/list` against a running game; not a test count, though it is quoted beside test counts | needs a game |

**Two of the three numbers in circulation have no provenance in this repository at all.** They
were not measurements that got stale; they are numbers with nothing behind them.

---

## 6. The command, and the tool

### 6.1 Reproducing the numbers

```bash
python scripts/test-census.py --run          # clean test, then census the surefire population
python scripts/test-census.py --run-its      # clean verify with every IT flag enabled
python scripts/test-census.py                # census what is already on disk
python scripts/test-census.py --json         # machine-readable
python scripts/test-census.py --quiet        # one verdict line per population
```

`--run` and `--run-its` are the only supported ways to get a number that means anything: without
`clean`, §4.2 applies. **`JAVA_HOME` is not required** — the tool checks the JDK it was handed,
refuses one older than 25 (`core/pom.xml:28` compiles at `release = 25`, and a JDK 21 otherwise
fails as `release version 25 not supported`, twenty lines above the module that needed it), and
prints which one it used.

**Which population the exit code judges, and why that is not a detail.** `--run` reaches the
`test` phase, and failsafe's goals are bound to `integration-test` and `verify`, so a
`test`-only invocation cannot have produced a failsafe report for anything. Judging failsafe
there would report 22 uncollected classes on every run forever, and **a check that is always red
is a check nobody reads** — which is how the twenty-two became twenty-two nobody mentioned. So
the default scopes the verdict to the populations the invocation actually covered, prints the
others' numbers anyway, and names them as not judged:

```
not judged (this invocation did not cover it): failsafe -- see test-census.md section 3
```

`--judge all` overrides it and is what you want when auditing whether the `*IT` classes ran.

### 6.2 The rule for this document, and every other one

> **Do not publish a count you did not measure in the run you are describing, and do not publish
> it without the scope.** A number with no command behind it is a claim; a number with a command
> is a receipt. This file publishes 2543 with `scripts/test-census.py --run` beside it, and every
> other number above is marked with what backs it — including the ones with nothing.

The corollary, which is the trap this document keeps walking into: **a count of a directory is
not a count of the code.** Nine module totals that disagreed were all correct readings of a
directory at a moment in time; the disagreement was never arithmetic.

### 6.3 What the tool refuses to do

- It will not sum surefire and failsafe (§1.3).
- It will not count a report with no source (§4.2).
- It will not count two reports for one class (§4.4).
- It will not pass when a class carrying `@Test` produced no report (§4.3).
- It **will** pass over a collectable file carrying no `@Test` at all — a misnamed driver, where
  nothing was lost (§2.1) — while printing it under its own label.
- It exits non-zero on failures, errors, ghosts or uncollected tests. **Skipped does not fail
  it**: skipped is reported and named, and the judgement about a specific skip is a human's.
- It will not judge a population its invocation did not cover (§6.1), and it says which ones it
  declined rather than passing in silence.

### 6.4 What this file does not do

It does not carry a number that moves without a run behind it. §0's table is the exception and it
names the command; §5's rows are historical and each is marked. If a reader finds a number in
this file with no row in §5 and no command in §6, that is a defect in this file.

---

## 7. Closed and open
Four of the five open items from this morning are closed, with the measurement that closed each.

| was | now | evidence |
|---|---|---|
| `dwm/pom.xml` documented `-Ddwm.live=false`, a property nothing reads; the command answered BUILD SUCCESS with 14 classes unrun | the comment names `dwm.live.skip` and says what the old name cost | `-pl dwm verify -Ddwm.live.skip=false` → **45 tests, 0 failures, 0 errors, 0 skipped** |
| `core`'s ITs blocked by 11 `NoSuchMethodError`s naming a method that exists in the source | they run | `-pl core -am verify -Dcore.it.skip=false` → **11 tests, 11 skipped, BUILD SUCCESS**. The `-am` is the whole difference |
| `SmokeIT` structurally unable to pass; green in CI only via two `Assume`s | **it passes**, and the client has booted to an in-game world under a headless fork | `-pl client verify -Dsmoke.skip=false` → **Tests run: 1, Failures: 0, Errors: 0, Skipped: 0** in ~18 s. `SmokeDriver` halts 0 only when `inWorld` |
| `SmokeTest.java` / `ServerJoinTest.java` were `main()` launchers named `*Test`, sitting in every collectable-class count | `SmokeDriver` / `ServerJoinDriver`; `SmokeIT` forks the new name | the census's misnamed-driver column is empty, and `SmokeIT` reaches a real world |
| nothing stopped a `*Test` file with no `@Test` in it, a stale report, or a dropped module from landing | `scripts/test-census.py` fails on all three, and runs in CI | `python scripts/test-census.py --run` → **exit 0**; 29 regression cases pin the tool |

### 7.1 Still open

1. **CI still cannot reach any `*IT`.** The census step judges surefire by design (§6.1), and
   the ITs need assets a runner does not have. Nothing in this repository fixes that without
   fetching the 1.8 asset set onto the runner — a decision, not a code change.
2. **`core`'s 11 IT methods all skip**, because no game is attached. `LiveGameGate` is
   deliberate about it and `DigLiveIT`'s javadoc calls itself an "HONEST TOMBSTONE". Making them
   run needs a live client, which is the north star's own open Owner question.
3. **`guarantees.md` §0 still prints `2025`.** It is annotated in place and points here.
   Replacing the number outright would delete the evidence of what it used to be, which §6.16 of
   that same file forbids — so this is a decision about that document, not a number.

### 7.2 What was deliberately not done

- **No existing number in another document was rewritten.** See item 3 above.