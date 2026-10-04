# ARCHIVED — 2026-10-04

**This project is closed. There is no further operations work on it.**

This file is the closing record: what the project was, what is actually in the tree, what was
never finished, and what will still be true — or still break — after the archive. It is written
to be checked, not believed. Every number carries the command or the file that produced it, and
where a figure could not be re-derived today it says so instead of being repeated.

---

## 1. What this project was

Put a real, running Minecraft 1.8.9 client (LWJGL3, JDK 25) behind an MCP server so a language
model can observe it, act on it, hot-patch its bytecode and debug its JVM — and then let that
model **play a match to completion**.

The first half was built. The second half was approached and not reached, and §6 says exactly
where it stopped.

The design constraint that shaped everything: **the vendored Minecraft tree under `client/` is
never edited.** Every fix to vanilla behaviour is a signed ASM patch applied by the agent at
startup. That is why the compat layer exists at all, and why it carries a TUF-style trust chain.

---

## 2. What is in the tree, measured

```bash
python scripts/test-census.py --run        # exits 0
```

```
surefire: 2542 green / 2543 tests, 0 failures, 0 errors, 1 skipped, 0 ghosts, 0 uncollected
failsafe:    0 green /    0 tests, 0 failures, 0 errors, 0 skipped, 0 ghosts, 22 uncollected
```

**2542 ran and passed. One did not** — `LevelSchemePathGuardPatchTest`'s Windows-symlink
assumption, which asserts a property of vanilla 1.8.9 the vendored source does not settle. The
other 22 are `*IT` integration classes that `mvn test` structurally cannot reach; they are a
different population behind different flags and are never added to the number above. The
convention is `docs/agency/test-census.md`; the command is `scripts/test-census.py`.

| | |
|---|---|
| commits | **341**, first `5eec5b9` 2026-07-10 "Minecraft 1.8.9 on LWJGL 3 + JDK 25 — clean base (v1.0.0)" |
| lines ever written | **+544,218 / −25,312** across all history |
| tracked files | **5,706** |
| commit cadence | July 192 · August 110 · September 1 · October 38 |
| reactor modules | **10** (6 with tests; `pg/` is an aggregator over three) |
| MCP tool surface | **54 model-facing**, 45 kernel-layered declared, **87 registered built-ins** (88 with `-Dmcp.core.handles=true`) |
| compat patches registered by default | **7**, every one `VERIFIED` and signed |

Per module, main / test `.java`: `lwjgl2-shim` 25/9 · `client` 1616/8 · `core` 313/374 ·
`board` 39/31 · `pg-api` 1/0 · `pg-engine` 6/1 · `pg-maven-plugin` 1/0 · `dwm` 20/34.
`client` is 1,613 vendored Mojang files plus three audio-library files.

> **A note on how this number was nearly wrong.** During the closing round an independent reading
> of the report directory, taken while several builds were interleaved, gave **2534**. Re-running
> with `clean` gave **2543** again. That gap is not arithmetic — it is stale reports summing
> alongside fresh ones, which is the defect `clean` and the de-ghost join exist to prevent. It is
> written here because it happened during this very session, not because it is interesting.

---

## 3. The parts that were genuinely finished

### 3.1 The compat patch layer — 7 patches, each pinned

The idea that makes the whole project legal: `client/` stays vanilla, and every vanilla defect is
corrected by a signed bytecode patch the agent applies at startup. `Compat.defaultDatabase()`
registers seven, all `VERIFIED`, all carrying `KERNEL_SIGNATURE`, so all arm by default against
the shipped anchors.

| id | target | fixes | pinned by |
|---|---|---|---|
| `MCP-KI0004` | `NetworkSystem` | `LocalServerChannel` on the wrong Netty 4.2 event-loop group | 17 tests |
| `MCP-KI0001` | `TextureUtil` | uninitialised mip levels sample as garbage under LWJGL3 | 18 tests |
| `MCP-KI0011` | `Minecraft` | no way to open the DWM screen without a hotkey edge | 21 tests |
| `MCP-GL0001` | `TextureUtil` | vanilla `GL_CLAMP` rejected by modern GL, so clamping wraps | 10 tests |
| `MCP-SEC0001` | `NetHandlerPlayClient` | `level://` server URL into a local path, no containment | 23 tests |
| `MCP-SEC0002` | `ResourcePackRepository` | first resource pack any server sends throws | 15 tests |
| `MCP-SEC0003` | `Scoreboard` | server names an objective the client never had → NPE | 16 tests |

Each has an arming test that iterates the shipped database and asserts it is registered, and a
signed-arming test that asserts the **fail-closed** direction: empty anchors, a broken root
chain, or an unsigned signer arm nothing.

### 3.2 The security kernel — 7 layers, deny by default

`ToolRegistry.LAYERS` is the single declaration of the game/kernel split, and `layerOf` returns
`KERNEL` for any name not declared there. An unclassified provider therefore lands hidden rather
than exposed. `ProtectedClassesTest`, `SeProtectedCompatPackageTest` and the `redefine_class`
guards pin the hot-redefine surface.

### 3.3 The evaluation seam

The single most important structural change in the last three days: the policy interface
(`Policy`, `PolicyRun`, `BrokenPolicies`) was moved out of the test tree into `core/src/main`.
Before that, the survival evaluation ran on a decision surface **no shipped artifact could
reach** — the file's own javadoc had said so for months. The seam is now in the product, and
`TheSuiteGoesRedThroughTheSeamTest` proves the suite can go red through it rather than around it.

### 3.4 An automated proof that the client reaches a world

`client/SmokeIT` forks a headless client and asserts it entered an in-game world. On the closing
day it was made to work for the first time: the fork had never passed `-javaagent`, so KI-4's
patch was never applied, and it looked for its JVM argfile in the wrong directory. With both
fixed it runs **green in about eighteen seconds**, and because the driver halts 0 only when it is
in a world, that is a real end-to-end assertion.

**Before this, nothing in the repository could demonstrate that it boots Minecraft and gets
into a game.** That is the single most valuable thing the closing round produced.

---

## 4. How the work was actually done, and what that cost

The audit corpus under `.ai-notes/docs/` is **gitignored** (`.gitignore:56`) and is therefore the
*only* record of the agent sessions — 141 files, ≈3.0 MB, 2026-07-11 to 2026-10-03. Nothing of
it travelled to git except the distilled survivors under `docs/agency/`.

What the corpus shows, and it is worth stating plainly because it is unusual:

- **At least seventeen audit findings were adopted**, each traceable from the audit file that
  raised it to the commit that settled it. Examples: `Json` missing from the protected set
  (`SeProtectedObjects` now pins `io.http`); the CI comment carrying test counts off by 4–5×
  (removed); a QML panel invisible to the agent (`QmlElementBridge`); `Board.shutdown()` not
  clearing its roster; the layer filter that was not filtering anything.
- **At least fifteen findings were refuted or rejected**, and the corpus kept the refutations
  with the same care. The north star was declared unreachable for lack of a crafting tool —
  refuted the same day. A diagnosis blaming `JAVA_HOME` for a 30-minute hang — refuted, and the
  file kept its false title for searchability. An attribution of a file to the wrong commit —
  corrected in place. Two proposals for a seam were rejected in favour of a third, on stated
  grounds.
- **The corpus audits itself.** `2026-10-01-review-standard-axis.md` re-derives 41 commit-message
  claims and finds several that do not hold. `docs/agency/failure-shapes.md` catalogues sixteen
  instances of one recurring shape — *a capability documented as doing something whose producer
  is absent* — because that shape is what this codebase produced most often.

The honest summary: **the process worked.** Findings were traced to commits, false ones were
refuted and recorded, and the artefacts that were worthless were labelled worthless rather than
quietly dropped. The cost was that the record lives on one machine and dies with it — which is
why §7 exists.

---

## 5. What is finished but fragile

- **`guarantees.md` §0 still prints `2025`.** It is annotated in place and points at the census.
  It was not overwritten because §6.16 of that same file forbids destroying the evidence of what
  a number used to be.
- **Two instruction documents disagree about where a test count lives.** `CLAUDE.md:67` routes
  it to `.ai-notes/STATUS.md`, a gitignored file that disclaims its own numbers. `CLAUDE.md` is
  frozen and was not touched; the pointer's *target* was corrected instead.
- **CI reaches no integration test.** The census step judges surefire by design; the `*IT`
  classes need assets a runner does not have.
- **`NightShelter.ledger()`** hands a game-thread-owned mutable object across threads and has no
  consumers. Parked, not fixed.

---

## 6. What was never finished

The north star was **reach a match through a night**, with a model deciding. It was not reached.
The corpus's last word (`2026-10-03-northstar-chain-broken.md`) is that the chain — launch,
relay, isolate, model — **had never been connected once**, and the model backend itself was
unavailable: the local RWKV was not running and the hosted one returned `402`.

Three questions were reserved for the Owner and never answered:

1. Whether to authorise launching Minecraft.
2. Which model backend to use.
3. Whether the model relay belongs in this repository at all — it changes what the repository is.

Two architectural questions were left open on purpose rather than guessed:

- **Whether the model seam belongs in the production tree.** Main refused to decide alone: if the
  north star only requires "reproducible on the evaluation rig", the test tree is the right home;
  if it requires a production-initiated round, the seam must ship.
- **A circuit breaker conflates two different failures.** `IoSupervisor` removes a weak model's
  only action verb for 30 s after three wrong calls, and the model cannot read that it has been
  isolated. "The tool broke" and "the model was wrong" are two failure classes sharing one door.

Also unfinished, and stated so rather than implied:

- **Per-cause damage attribution is unavailable on a live client.** The instrument must report a
  single source, `SERVER`. The design report exists; no production file was created.
- **`DawnChest.fact()`'s three disclaimer sentences do not hold live.** In 1.8.9 a closed chest's
  contents are client-unreadable, so they must be rewritten at wiring time.

---

## 7. Two things that will still be true after the archive

**These outlive the project and are the reason this file exists beyond a changelog.**

### 7.1 The trust chain expires on 2027-09-30

`core/src/main/resources/net/marcloud/mcp/core/compat/root-metadata.json` declares
`"expires": 1822271706369` = **2027-09-30 02:35 UTC**. After that date **every compat patch stops
arming** and every tool that depends on one degrades silently — which is exactly the shape of
defect this project spent its life hunting.

### 7.2 The root ceremony cannot be re-run as written

`RootTrust.java:154` requires a candidate to be exactly `trusted.version() + 1`, and the shipped
metadata is `version: 2`. But `RootCeremonyCli.java:137` hardcodes `new RootMetadata(2, ...)`.
**Running the ceremony now mints version 2 against a trusted version 2 and is rejected as a
rollback.** The rotation tool is a one-way door, and nobody found this until the closing round.

### 7.3 Key custody

The private keys for the shipped chain live outside the repository in `~/.mcp-keys/`
(`kernel-ed25519.key.b64`, `root-ed25519.key.b64`). The copies next to them, marked
`*.stale-2026-07-14`, are from an earlier chain and do not match the shipped one — verified by
hash today. **The corpus records that at one point no copy of the live private key existed on the
machine at all.** If these keys are lost, the patches cannot be re-signed and the layer is dead
regardless of §7.1.

---

## 8. How to check any of this

```bash
export JAVA_HOME='D:\Software\Developer\jdk\25'
python scripts/test-census.py --run          # the test number, and why it is that number
python scripts/test-census.py --run-its      # the 22 integration tests, with every flag on
cd scripts && python -m unittest discover -s . -p 'test_*.py'   # 93 python harness tests
git log --oneline -1 && git status --short   # what state this tree is in
```

Read next, in this order: `docs/agency/test-census.md` (what is counted and what "green" means) ·
`docs/agency/failure-shapes.md` (the one defect shape this project kept producing) ·
`docs/agency/guarantees.md` (53 guarantees, each with the `file:line` and the test that pins it) ·
`docs/agency/vendored-tree.md` (the embedded Minecraft tree's extent and its two registry gaps).

## 9. Licence

Original work: CC BY-NC-ND 4.0 — see [`LICENSE`](../LICENSE). **The vendored Minecraft 1.8.9
sources, mappings and assets under `client/` are Mojang's and are not covered by it.** A lawful
copy of the game is required to build or run this.