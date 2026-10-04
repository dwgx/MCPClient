# ReviewStandard - STANDARD AXIS (how the code is written)

Scope: `origin/mcp-core..HEAD` (11 commits) + the uncommitted work under `core/src`.
Read-only review. No builds, no test runs, no launches. Read via codegraph / raw diff / raw file.
Spec axis ("was the right thing chosen") is out of scope and left to the other reviewer.

---

## 1. Findings, severity-ranked

### HIGH-1 - `core/src/main/java/net/marcloud/mcp/core/ob/ObManager.java:197-229`
**The `action` gate denies every call to two shipped R1 tools whenever `-Dmcp.core.handles=true`.**

`checkRequest` derives `folded` from the *presence of an `action` argument*, not from the tool
being one of the two folded debug entries:

    String op = req.toolName();
    Object action = req.arguments().get("action");   // line 198
    if (action != null) { op = String.valueOf(action); }
    boolean folded = action != null;                // line 202
    ...
    if (folded && !HANDLE_OPS.containsKey(op)) {   // line 208, and again at 226
        return denyUnknownAction(op, req.toolName(), "no 'handle' was supplied");
    }

`HANDLE_OPS` (`ObManager.java:60-63`) holds only the six `debug_*` concrete names. But two
non-debug tools declare an `action` property:
- `do_use_entity` - `core/src/main/java/net/marcloud/mcp/core/io/transport/ToolRegistry.java:2673`
- `do_entity_action` - `ToolRegistry.java:2856`

So `do_use_entity {entityId, action:"ATTACK"}` reaches line 208 with `folded=true`, `op="ATTACK"`,
and is DENIED with "action 'ATTACK' on tool 'do_use_entity' is not a recognised handle-op ...",
a message naming a concept unrelated to what the caller did. `do_entity_action
action=START_SNEAKING` is denied identically.

Reachable on both monitor paths: `SeLocalMonitor.java:163-167` (local posture) and
`SeHandleGatedMonitor.java:74` (P-SECURE). The gate is live whenever
`McpCore.buildObjectManager()` returns non-null, i.e. `-Dmcp.core.handles=true`
(`McpCore.java:662`). Default posture is off, which is why nothing is red in the suite.

Impact: in a handles-enabled run two ring-R1 actuation tools are 100% unusable, and the deny text
actively misdirects debugging. Fix direction: `folded` must key on the tool name being
`debug_manage`/`debug_handle`, not on argument presence.

---

### HIGH-2 - `commit:39d599a` (+ `docs/branch-topology.md` around line 210)
**The replacement "verifiable" command does not print what the commit says it prints.**

Commit body: *"Replaced with `git diff --stat 5eec5b9..HEAD -- client/src`, which prints those 22
files and goes red the moment the claim stops being true."*
Doc, immediately above the command: `# ... the line below prints 22 files`.

Re-derived:

    $ git diff --name-only 5eec5b9..HEAD -- client/src | wc -l
    26

22 files under `client/src/main/java` (the vanilla source) is right, plus **4 more** under
`client/src/test/java` (`CompressionFramingTest`, `NbtRoundTripTest`, `PacketBufferCodecTest`,
`PacketIdRegistryTest`). The command as written includes them, so it prints 26. The doc's own
*next* command filters those four names out with `grep -vE`, which is the tell: the author knew
they were in the first output and did not adjust the annotation.

This is the third false-green shape this round set out to kill - a check whose stated output does
not match its actual output - committed in the commit whose stated purpose is "make the claim
falsifiable by one command". Correct annotation: "26 entries (22 vanilla source + 4 new tests)".

---

### HIGH-3 - `commit:26bf04a`
**"Verified: `git grep -n "core (" .github/workflows/build.yml` exits non-zero" is false.**

Re-derived:

    $ git grep -n "core (" -- .github/workflows/build.yml
    .github/workflows/build.yml:33:      # ("core (253) + board (172)") was off by 4-5x before anyone noticed, and any
    exit=0

The literal `core (` is still in the file - inside the very comment this commit added, quoting the
old counts to explain why they went away. The grep matches and exits 0, so the "goes red if a
count comes back" guard is not red today. Either narrow the pattern (e.g. an anchored
`core \([0-9]` outside a comment) or drop the claim.

---

### MED-1 - `dwm/src/main/java/net/marcloud/mcp/dwm/qml/QmlGuiScreen.java:108-141`
**`republishIfChanged` walks the QML tree twice per change and caches a table it never published.**

    hits = QmlElementBridge.enumerate(surface.rootItem(), width, height, ...);   // walk #1
    if (hits != null && hits.equals(lastPublished)) { return; }
    lastPublished = hits;                                                          // walk #1's table
    republishElements();                                                           // walk #2
    ...
    private void republishElements() {
        List<Hit> hits = QmlElementBridge.enumerate(...);                           // walk #2
        QmlElementBridge.publish(buttonList, hits);
    }

QML bindings evaluate during a walk, so walk #2 can differ from walk #1. When it does,
`lastPublished` is not the published table, the next frame's walk legitimately differs from it,
and the "publish only on a difference" rule the commit is built on is violated once and
self-corrects. Cost: two bounded render-thread walks per actual change on top of the one the
static-page fast path already pays. Fix: have `republishElements` take the list.

---

### MED-2 - `dwm/src/main/java/net/marcloud/mcp/dwm/qml/QmlGuiScreen.java:129-136`
**Dead branch: the `mcp.dwm.noelements` guard inside `republishElements` is unreachable.**

`republishElements` has exactly one caller - `republishIfChanged` at line 126 - and
`republishIfChanged` returns at line 111 when `mcp.dwm.noelements` is set. So the flag check and
the `buttonList.clear()` at lines 130-136 can never run. `initGui` deliberately no longer calls
`republishElements` (comment line 67; guard test `QmlElementsArePublishedAsVanillaButtonsTest.java:234-235`).
Defensive code outside the security kernel, which this project explicitly forbids leaving behind.

---

### MED-3 - `scripts/check-agent-jar.py:74`
**Reads every entry of a 10289-entry shaded jar into memory to compare 482 of them.**

    entries = {i.filename: z.read(i.filename) for i in z.infolist()}

The real artifact is `core/target/core-1.8.9-all.jar` - 10289 entries, 16.7 MB on disk. The check
only needs the compiled-class entries plus four resources. Peak memory is proportional to the whole
jar, in a tool whose stated job is to be cheap to run right after a package. `z.read(name)` per
looked-up entry, or a `namelist()` set, gives the same result with bounded footprint.

---

### MED-4 - `scripts/check-agent-jar.py:106`
**Unclosed `ZipFile`.**

    print(f"classes {len(on_disk)} compiled, {len(zipfile.ZipFile(jar).namelist())} entries in the jar")

`check()` opens and closes its handle properly (line 70, `with`). This one is opened as a temporary
and never closed - a leaked file handle on the success path, in a script whose commit message is
specifically about a check that can be run.

---

### MED-5 - `commit:d378c7f`
**"scripts/test_probe_framing.py: +542 lines over the previous version" - does not hold.**

    $ git show --numstat d378c7f -- scripts/test_probe_framing.py
    534     8       scripts/test_probe_framing.py
    $ git show d378c7f~1:scripts/test_probe_framing.py | wc -l   ->  318
    $ git show d378c7f:scripts/test_probe_framing.py   | wc -l   ->  844

534 insertions, 8 deletions, net +526, file 318 -> 844. "+542" matches none of them. A third wrong
number in a commit message in a round whose thesis is that wrong numbers in prose outlive their code.

---

### MED-6 - `scripts/verify-protocol.py:73-86, 107`
**Every RPC opens a session with two `initialize` requests, and rejection is a traceback, not a FAIL.**

`request()` unconditionally sends `initialize` (id 1), `notifications/initialized`, then the real
call (id 2). `main()` then calls `rpc.request("initialize")`, so the first exchange puts a *second*
`initialize` on the wire as id 2 in the same session. MCP forbids re-initializing a live session; a
compliant server answers with an error and `request()` raises `RuntimeError(...)`, which nothing
catches. The reported green live run means this server is lenient - the script depends on that
leniency on every call, not just the first.

---

### MED-7 - `scripts/verify-protocol.py:107-190`
**Only the first call is guarded; every later failure escapes as a Python traceback.**

    try:
        rpc = Rpc(args.port)
        init = rpc.request("initialize")
    except OSError as e:
        ... return EXIT_NOTHING_LISTENING
    server = init.get("serverInfo", {})
    tools = rpc.request("tools/list").get("tools", [])   # unguarded
    ...
    chat = rpc.call("chat_read")                          # unguarded

If the client is closed between `initialize` and `tools/list`, or answers `tools/list` with a
JSON-RPC error, the script dies with a traceback and whatever exit code Python picks - not
`EXIT_FAIL`, and not a line naming the failure. Not false-green, but a probe is a tool people read
the output of. Wrapping the body in `except (OSError, RuntimeError)` -> `EXIT_FAIL` costs four lines.

---

### MED-8 - `core/src/main/java/net/marcloud/mcp/core/compat/RootTrust.java:66-88`
**The baked path now has a wall-clock deadline with no update channel: it disarms itself
permanently on 2027-09-30.**

`effectiveAnchors()` gained a freeze check against `System.currentTimeMillis()`, and the shipped
resource was re-minted with an expiry:

    core/src/main/resources/net/marcloud/mcp/core/compat/root-metadata.json
      "expires":1822271706369   ==  2027-09-30T02:35:06Z

`verifyRootUpdate` - the only path that could supersede the baked document - is documented as
having **no caller in the shipped client** (`RootTrust.java` ~line 117). So after that instant the
client arms no patches, forever, with one stderr line and no in-client remedy. The freeze rule
itself is sound; what is missing is either an expiry far beyond any plausible horizon for a baked
artifact, or a build check that fails when the baked document is within N days of expiry.

---

### MED-9 - `core/src/main/java/net/marcloud/mcp/core/compat/RootTrust.java:41` and
### MED-9 - `core/src/main/java/net/marcloud/mcp/core/compat/RootMetadata.java:76`
**Two comments state that the shipped document declares no expiry. It declares one.**

- `RootTrust.java:41`: *"The shipped document declares no expiry, so the freeze check passes for it
  and bites the moment a document declares one."* - `root-metadata.json` on disk has
  `"expires":1822271706369`.
- `RootMetadata.java:76`: `/** A document that declares no expiry (the shipped shape). */`

This is exactly the drift class commit `1734974` spent its whole message removing ("the comment
outlived the code, which is the drift this repository keeps paying for"), landing in the same round,
in the trust core.

---

### MED-10 - `core/src/main/java/net/marcloud/mcp/core/io/http/Json.java:56-70`
**The record branch allocates reflectively per component per write, and swallows access failures as
`null`.**

    java.lang.reflect.RecordComponent[] comps = o.getClass().getRecordComponents();
    for (int i = 0; i < comps.length; i++) {
        writeString(sb, comps[i].getName());
        sb.append(':');
        try {
            writeValue(sb, comps[i].getAccessor().invoke(o));
        } catch (ReflectiveOperationException e) {
            sb.append("null");
        }
    }

1. `getRecordComponents()` returns a fresh array copy per call and `getAccessor()` returns a fresh
   `Method` per call, so every record serialisation allocates 2 objects per component on a path
   `HttpFacade` uses for tool results. A `ClassValue<Map<String, Method>>` resolves them once per
   record class.
2. `Method.invoke` throws `IllegalAccessException` (a `ReflectiveOperationException`) when the record
   is not public and the writer is in another package. The catch turns that into a component written
   as `null` - valid JSON, all keys present, all values null, which reads as data. That is the same
   failure mode the commit's own test file calls "the dangerous kind" for the `toString` bug it
   replaced. The record the test uses (`ARecordIsWrittenAsAnObjectNotItsToStringTest.Report`) is
   package-private **in the same package as `Json`**, so the test cannot reach this branch. Eight
   package-private records exist outside `io.http` in `core/src/main` (`ActActuator.Target`,
   `NavController.Jam`, `RecipeLayoutReader.Read`, `GuiActions.Driven`, `RouteExecutor.Aim`,
   `EnchantTools.EnchantView`, `ToolRegistry.SlotView`, `ToolRegistry.RideState`). I traced the
   current call sites and found none reaching `Json.write` today, so this is an armed trap rather
   than a live defect - but the test that should catch it is in the wrong package.

---

### LOW-1 - `scripts/test_check_agent_jar.py:73, 81`
**Dead first `_fixture()` call in two tests.** Lines 73 and 80 build a fixture that line 77 / 83
immediately overwrites. Each discarded call runs `tempfile.mkdtemp()` and writes a two-class jar
that is never cleaned up.

---

### LOW-2 - `scripts/verify-protocol.py:127-133`
**The phantom-name scan is weaker than the claim attached to it.** Commit: *"No description may name a
tool or a field that does not exist."* Implementation: `if phantom in json.dumps(tool)` over three
case-sensitive literals. `held_slot`, `HeldSlot` and `read-inventory` all pass. The scan does cover
the whole tool record (strictly more than the docstring's "description"), which is fine; the
overstatement is the "no tool or field that does not exist" framing.

---

### LOW-3 - `dwm/src/main/java/net/marcloud/mcp/dwm/qml/QmlUiSurface.java:296-301`
**Comment overstates `close()`'s idempotence.** *"Cleared first, unconditionally, so a second call
while a deferred release is pending is a no-op rather than a second dispose."* True only on the
`dispatchDepth > 0` branch. From outside a dispatch, a second `close()` clears `open` again and
re-enters `closeQuietly()` -> `GlStateGuard.enter()/leave()`. Harmless today (`releaseNatives()`
nulls `view`/`backend`), but the comment is the only guard a reader has on a class whose
`GlStateGuard` is documented as non-reentrant.

---

### LOW-4 - `core/src/main/java/net/marcloud/mcp/core/se/SeToolRequirement.java:237`
**Dash convention inconsistent inside a single diff.** The new `press_key_binding` comment uses
`--` while the four adjacent new comments in the same change use an em dash. The file is already
mixed (15 em dashes, 2 double hyphens), so this is cosmetic, but a new block should not add a third
variant of the same sentence.

---

### LOW-5 - `dwm/src/main/java/net/marcloud/mcp/dwm/qml/QmlGuiScreen.java:122`
**Dead null check.** `if (hits != null && hits.equals(lastPublished))` - `QmlElementBridge.enumerate`
returns the `out` list on every path including the early return, so `hits` is never null.

---

## 2. Commit-message claims - re-derived

`HOLDS` = re-derived from the tree or a command I ran. `DOES NOT HOLD` = it does not.
`NOT RE-DERIVABLE` = needs a live client, a build, or a working tree that has since moved on.

| # | commit | claim | verdict | evidence |
|---|---|---|---|---|
| 1 | 2511910 | `.gitignore` rules cover `nul / .chatverify_archive / core/cp.txt / .tmp_audit.md` | HOLDS | `git check-ignore -v` names a rule for all four (`.gitignore:110`, `:101`, `:103`, `:104`); also `.agent-tmp-*`, `core/.agent-tmp-mut2`, `.tmp_report.md` (via `:106 .tmp_report.*`) |
| 2 | 2511910 | "`git status --porcelain \| wc -l` drops from 270 to 263" | NOT RE-DERIVABLE | working tree has since changed (now 204 entries); the pre-commit tree state is not reconstructible |
| 3 | 1734974 | `Board.java:106` now calls `Matrix.clear()` | HOLDS | committed file line 106 = `FEATURES.clear();`, line 107 = `TRACE.clear();` |
| 4 | 1734974 | "both lines are load-bearing" | HOLDS | both present; `Trace` is process-wide, subscribed outside the matrix; javadoc at `Board.java:93-99` matches the code |
| 5 | 1734974 | `Trace.Subscription.cancel()` clears the dispatch cache | HOLDS | `Trace.java:103` `dispatchCache.clear();` inside the `if (active)` block |
| 6 | 1734974 | DeathSignal / HealthChangeSignal / PlayerJoinSignal are WIRED from S42 / S06 / S38 | HOLDS | `HighValueSummarizers.java:325` `combat event=... death="..."`, `:294` `health hp=...`, `:369` `playerList action=... names=...`; `BoardWorldEventBridge.java:192` `emitHealth` parses `hp=`, `:225` `parseQuoted(summary,"death=")`, `:237-248` `emitPlayerJoins` splits `names=` |
| 7 | 1734974 | PlayerLeaveSignal unwired because REMOVE carries a UUID, never a name | HOLDS | matches `S38PacketPlayerListItem` shape; bridge emits nothing for non-ADD (`BoardWorldEventBridge.java:237`) |
| 8 | 1734974 | board 233/233 green; `restartingReinstallsAnEnabledRoster` / `restartingResubscribesToTheTickBus` are the 2 teeth tests | HOLDS (existence) | both methods exist at `BoardInitInstallsOfficialChipsTest.java:97` and `:157`. The **green count** is a build measurement I may not re-run |
| 9 | 26bf04a | `git grep -n "core (" .github/workflows/build.yml` exits non-zero | **DOES NOT HOLD** | exits **0**, matching `.github/workflows/build.yml:33` - see HIGH-3 |
| 10 | 26bf04a | "The step itself is unchanged" | HOLDS | diff is comment-only inside the steps block; `run: ./mvnw -B -ntp test` untouched |
| 11 | 1f7a5b1 | `skija-linux-x64` added at `runtime` scope next to windows/macos | HOLDS | `dwm/pom.xml:90-92`, `io.github.humbleui:skija-linux-x64:${skija.version}`, `runtime`; siblings `:78`, `:84` same groupId/version/scope |
| 12 | 1f7a5b1 | "one artifact runs everywhere; loader picks by os.name/os.arch", not os-activated profiles | HOLDS (structurally) | no os-activated profiles added; the arrangement is the project's established one |
| 13 | 1f7a5b1 | "dwm 84/84 green on Windows; Linux NOT verified here" | NOT RE-DERIVABLE | build measurement; the honest-scope sentence is itself accurate |
| 14 | 39d599a | "relative to `5eec5b9`, 22 vanilla source files are modified" | HOLDS | 22 files under `client/src/main/java` |
| 15 | 39d599a | "`git diff --stat 5eec5b9..HEAD -- client/src` ... prints those 22 files" | **DOES NOT HOLD** | prints **26** (22 main + 4 test) - see HIGH-2 |
| 16 | 39d599a | "1 real behaviour change: TextureUtil, `:58` `[0].length`, `:245-248` GL_CLAMP->GL_CLAMP_TO_EDGE, commit `aa3f776`, covered by no compat patch" | HOLDS (spot-checked) | `TextureUtil.java` is in the 22-file list; `aa3f776` is on this line; no compat patch names `TextureUtil` under `core/.../compat/patches/` |
| 17 | 39d599a | the old command `git diff origin/mcp-core..HEAD -- client/src` "CANNOT fail: both refs are the same commit" | HOLDS | the doc itself records `origin/mcp-core == HEAD == 46249ca` |
| 18 | 48cfe84 | "six tests over a synthetic jar" | HOLDS | `scripts/test_check_agent_jar.py` has exactly 6 `def test` |
| 19 | 48cfe84 | "scripts 52/52 green ... measured: Ran 52 tests, OK" | HOLDS (count) | 6 + 41 + 5 = 52 `def test` at that commit; the OK is a run I may not repeat |
| 20 | 48cfe84 | "Measured on the real build: 482 classes, 10289 jar entries, all matching" | HOLDS | `find core/target/classes -name '*.class' \| wc -l` -> **482**; `core-1.8.9-all.jar` -> **10289** entries |
| 21 | 48cfe84 | "the four signed compat resources must be present" | HOLDS | `SIGNED_RESOURCES` has exactly 4, and all four exist under `core/src/main/resources/net/marcloud/mcp/core/compat/` |
| 22 | 48cfe84 | "The jar path is parsed out of `run-mcp.bat`" | HOLDS | `scripts/run-mcp.bat:50` `set "CORE_JAR=%ROOT%\core\target\core-1.8.9-all.jar"`; the checker's regex matches it |
| 23 | 48cfe84 | "An empty classes directory is REFUSED, not reported as 0 differences" | HOLDS (for `main`) | `check-agent-jar.py:100-103`; test `test_check_agent_jar.py:121-135` drives `main`. Note `check()` alone still returns `[]` for an empty dir - the guard lives only in `main` |
| 24 | d378c7f | "scripts 52/52 green" | HOLDS (count) | 52 `def test` across the three files at this commit |
| 25 | d378c7f | "test_probe_framing.py: **+542 lines**" | **DOES NOT HOLD** | 534 insertions / 8 deletions; net +526; 318 -> 844 - see MED-5 |
| 26 | d378c7f | "the probes themselves have NOT been run against a live client in this commit" | HOLDS | consistent with the diff (harness + liveness check only) |
| 27 | cd37d57 | "Comment only; no code and no test change in this commit" | HOLDS | the entire diff is inside the `HardenEngine` class javadoc block |
| 28 | cd37d57 | "`-pl pg-engine` is NOT a valid selector for this reactor ... both the module path and `-pl :pg-engine` work" | HOLDS | root `pom.xml` modules are lwjgl2-shim/client/core/board/pg/dwm; `pg/pom.xml:35` declares `pg-engine`, so the reactor path is `pg/pg-engine` and bare `pg-engine` is not a path |
| 29 | cd37d57 | "pg-engine 5/5 green" | NOT RE-DERIVABLE | build measurement |
| 30 | b67b28d | "Three new guard tests: `EverySkijaEntryIsGuardedTest`, `DismissReleasesTheSurfaceTest`, `NoWrapIsReportedAndRetriedTest`" | HOLDS | all three added in the commit's file list |
| 31 | b67b28d | "dropping the geometry fields from `Hit.equals` kills `QmlElementNamesAreReadableTest.aMovedOrRestatedControlIsNotEqual`" | HOLDS (exists, non-vacuous) | method at `QmlElementNamesAreReadableTest.java:363` asserts `before.equals(bridge(root))` is **false** after mutating `hit.x` - it cannot pass without geometry in `equals` |
| 32 | b67b28d | "Two of the new tests ... were flagged as locally vacuous by review and are NOT claimed here as evidence" | HOLDS | honest disclosure; both files are in the diff |
| 33 | b67b28d | "dwm 84/84 green" | NOT RE-DERIVABLE | build measurement |
| 34 | ea891e0 | "Documentation only; no code and no test in this commit" | HOLDS | diffstat is `dwm/README.md` only |
| 35 | f42b1fc | "Every tool the audit names as delivered must be in the live `tools/list`" | HOLDS | `EXPECTED_TOOLS` = 8 names, checked against live `tools/list` |
| 36 | f42b1fc | "All three of the names checked here (netty-tap, read_inventory, heldSlot)" | HOLDS | `PHANTOM_NAMES` has exactly those three, and none appears in `ChatTools.java` today, so assertion 2 is currently satisfiable |
| 37 | f42b1fc | "netty-tap was still in `chat_read`'s own description until 2026-10-01" | HOLDS (present tense) | `ChatTools.java:91` now reads `[requires: seam_netty_install]`; no `netty-tap` in the file |
| 38 | f42b1fc | "`chat_read` with no packet tap must REFUSE ... and named `seam_netty_install`" | HOLDS | `ChatTools.java:177-179`: `"packet tap not installed - this is NOT an authoritative 'nobody spoke'. Install it with seam_netty_install first."` - contains both the substring the script asserts and the tool it names |
| 39 | f42b1fc | "`act_set` must DECLARE its channels ... used to be `{"type":"object"}` with zero properties" | HOLDS | `ActTools.java:193` now builds `properties` for `act_set` (`:101`, `:128`, `:200` also use `properties`/`oneOf`) |
| 40 | f42b1fc | "server mcp-core 1.8.9, 84 tools, 92995 chars ... largest five act_set 13496, world_view 6944, gui_click_element 4910, do_click_slot 3443, gui_snapshot 3248 ... All assertions held, exit 0" | NOT RE-DERIVABLE | requires a running client on the main menu; the script's structure is consistent with every number but I cannot confirm any of them read-only |
| 41 | f42b1fc | "Run `scripts\check-agent-jar.py` first" | HOLDS | the script exists and `main()` parses `CORE_JAR` out of `run-mcp.bat` |

---

## 3. What I did NOT cover

**Out of my axis (the other reviewer's job):** whether the right tools, gate rows, rings and
integrity levels were chosen; whether every requirement in the deferred 208-entry working tree was
actually implemented; whether the frozen-contract and vanilla-mapping rules were respected in substance.

**Not examined at all:**

- `client/src/main/java/net/minecraft/client/renderer/texture/TextureUtil.java` - the single
  uncommitted `client/src` entry. Deferred by Owner decision; I read the topology claim about it,
  not the diff.
- **Roughly 90% of the 11,319-line working-tree diff.** Read in full: `ob/ObManager.java`,
  `se/Ring.java`, `se/SeToolRequirement.java`, `se/SeProtectedObjects.java` (the named
  access-control surface); `compat/RootTrust.java`, `compat/RootMetadata.java`,
  `compat/tools/RootCeremonyCli.java` (the trust core); `io/http/Json.java`; `se/BuiltinGateAudit.java`;
  `ke/KeGameDispatcher.java` (diff only); the six board Signal classes.
  **Not read:** `drivers/gui/GuiActions.java` (+1807), `io/transport/ToolRegistry.java` (+1759),
  `drivers/act/NavController.java` (+1227), `drivers/action/ActTools.java`, `drivers/act/LookController.java`,
  `drivers/plan/RouteExecutor.java`, `ActIntentParser`, `Move.java`, `NeighborGen`, `LiveBlockView`,
  `Stance`, `BlockView`, `InteractController` / `InteractIntent` / `MoveApplier`, `drivers/world/*`
  (EntityView, EnvView, WorldViewCapture, WorldViewDiff, WorldViewJson, InventoryView, LocalGrid,
  WorldScanner, SelfView), `flt/seam/summarize/*`, `mm/ValueCodec`, `compat/Compat*`, `McpCore.java`,
  `kd/DebugTools.java`, `CapabilityCatalog`, and every untracked new file under `core/src/main`
  (EscMenu, GlClampToEdgePatch, ClimbSteering, CritWindow, NavHazard, Standable, SwimSteering,
  ClickVerdict, GuiListReflect, GuiPanelReflect, GuiPanelState, GuiStack, ChatTools, ArmourSlots,
  BlockInspector, DrowningDamage, EntityCombat, EnvironmentWeather, FallDamage, FireDamage,
  SlotClickMode, WorldViewLegend, EnchantTools, EscPanelTools, ChatLog).
  Those drivers are exactly where tick-to-tick state corruption and boundary errors would live;
  **this report has no opinion on them.**
- `core/src/test/**` - the working-tree test additions (about 70 files). I checked existence and
  non-vacuousness only for the two board teeth tests and `aMovedOrRestatedControlIsNotEqual`. I did
  not review new tests for vacuity, wrong assertions, or tests pinning current buggy behaviour.
  (Exception: `ARecordIsWrittenAsAnObjectNotItsToStringTest`, read in full for MED-10.)
- `scripts/` beyond the two files named in my brief. Not reviewed: `mutate.py`, `mcp_probe.py`,
  `live-*-probe.py`, `test_probe_framing.py` (41 tests), `test_probe_liveness.py`,
  `jvm-args-select.bat`, `jvm-args-mcp-stock.txt`, `bisect-overlay.bat`, `build-jars.bat`,
  `run-mcp.bat`, `run-mcp-overlay.bat` - 1,617 changed lines in `d378c7f` plus siblings.
- `pg/` beyond `HardenEngine.java`'s javadoc.
- `dwm` beyond `QmlElementBridge`, `QmlGuiScreen`, `QmlProxyButton`, `QmlUiSurface`,
  `McpFboSurfaceBackend` (diff), `WindowCommands` / `UiWindowHost` (diff), and the eight `.qml`
  resources touched by `b67b28d`. `GlStateGuard` itself was not read.
- **Every green test count** in every commit message (board 233/233, dwm 84/84 twice, pg-engine 5/5,
  core 1331, scripts 52/52 twice). Running builds was out of contract. Where a count was
  *statically derivable* (52 python tests; 482 classes; 10289 jar entries) I re-derived it.
- **The live client.** No game was started, so nothing in `f42b1fc` or `1f7a5b1` that needs a running
  surface is confirmed.
- **Concurrency beyond** `board/Trace.java` (read in full) and the dwm render-thread dispatch path
  (`QmlUiSurface.dispatch` / `close`, `QmlElementBridge.REPORTED_AMBIGUOUS`). I did not audit the
  `IoSupervisor` thread pool, the tick injector, or the 8-thread executor for races.
- **Constant-time comparison:** I grepped every `equals`-based comparison in `core/src/main` and
  checked each. All three *secret* comparisons use `MessageDigest.isEqual` (`AlpcServer.java:194`,
  `HttpFacade.java:178`, `SeClearancePolicy.java:79`). The three that do not - `ContentHash.java:71`
  (canary), `TufTrust.java:98` (public keys), `MmAccess.java:326` (parameter types) - compare no
  secret. **No non-constant-time secret comparison found.**

---

## 4. Summary

- The three HIGH findings are all **fail-closed or documentary, not fail-open**. HIGH-1 breaks two
  R1 tools in the handles-enabled posture; HIGH-2 and HIGH-3 are commit messages whose stated
  verification does not behave as stated.
- HIGH-2 and HIGH-3 are, precisely, the shapes commit `cd37d57` apologises for ("a check whose
  failure is swallowed by a pipe is not a check"). The rule was applied to a pipe and not to a grep
  exit code or a diff count. That is the same recurrence `48cfe84` and `d378c7f` also carry
  (MED-5 is a third wrong number).
- **No gate bypass, no permissive-default fall-through, and no non-constant-time secret comparison**
  in the access-control surface. The `ObManager` change is a genuine hardening in the right
  direction; its defect is over-reach (keying on argument presence), not under-reach. The
  `io.http` whole-package protection is a strict improvement: `normalize` strips `$` and array
  descriptors first, so inner classes are covered, and no production code redefines an `io.http` class.
- The dwm element-table reasoning the brief asked me to check is sound in its central claim
  (`Hit.equals` over every field, republish on content change, publish after `surface.frame()`, the
  Flickable `contentOffset` correction). Two real defects sit around it: a double walk whose cached
  table is not the published one (MED-1), and an unreachable branch left by the same refactor (MED-2).
- Emoji scan over all 71 changed/new files under `core/src`, `board/src`, `dwm/src`, `scripts`,
  `pg`: **no emoji in any comment or identifier.** The only matches are pre-existing UI glyph strings
  in QML (`"X"`, `"+"`, gear, checkmark) and commit `b67b28d` added none of them.
