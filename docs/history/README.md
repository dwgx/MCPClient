# docs/history/ — what was kept, what was dropped, and why

**2026-10-04.** Everything in this directory was living under `.ai-notes/docs/`, which is
**gitignored** (`.gitignore:56`). It was the only record of how this project was actually built,
and it would have died with the machine. This is the audited subset that travelled into git.

**Nothing was deleted.** The source tree is untouched; "dropped" below means *not committed*, and
any file listed as dropped is still readable under `.ai-notes/docs/…` on this machine. Paths
**inside** the files that came across were rewritten from `.ai-notes/docs/` to `docs/history/`,
so a reader following a citation lands in this repository rather than in a directory that will
not be there.

---

## 1. Why this was necessary at all

`docs/README.md` indexed an architecture tree — `00-META`, `00-OVERVIEW`, `01-SECURITY-KERNEL`,
`ARCHITECTURE-LOCK`, the ADRs — that **was not in the repository**. A reader following the index
would have found nothing. That is the same defect class as `CLAUDE.md:67` pointing the test count
at a gitignored file that disclaims its own numbers: **a pointer into a tree that does not
travel.** The index is now correct because the tree is here.

## 2. The rule used to sort

Three questions, asked of every file:

1. **Is it durable?** Does it describe a decision, a convention, or a structure that still holds —
   or does it describe one day's work?
2. **Is it already distilled?** If `docs/agency/` already carries the conclusion, the working note
   behind it is a duplicate of a better document.
3. **Would a reader be worse off without it?** A raw transcript is worse than nothing: it is
   large, it is unedited, and it contains the mistakes.

A file that fails (1) is dropped. A file that fails only (2) is dropped **with a pointer** to
where its conclusion now lives, so the chain is not broken — only the working note is left behind.

---

## 3. Kept, and what each thing is for

| directory | files | size | what it is |
|---|---|---|---|
| `architecture/` | 12 | 256 KB | **The design core.** `00-META`, `00-OVERVIEW`, `01-SECURITY-KERNEL`, `02-CAPABILITIES`, `03-NATIVE-C6-AND-BUILD`, `04-NT-EXECUTIVE-RENAME-MAP`, `05-TEST-MAP`, `06-PLATFORM-SPI`, `07-COMPAT-SHIM`, `08-TIMELINE-SPINE`, `ARCHITECTURE-LOCK`, and `adr/` (5 ADRs) |
| `reference/` | 6 | 64 KB | **The customised development process** — commit convention, doc style guide, external doctrine, tool-annotation convention, the cc workflow guide, codegraph usage. This is the "定制好的开发流程" |
| `design-briefs/` | 15 | 416 KB | Design documents: the C6 JVMTI agent, L6 object handles, GUI interaction, the pg hardening library, the perception-control roadmap, first-night plan |
| `study/` | 3 | 80 KB | External research: how other modding clients are built, and the porting backlog |
| `compat/` | 1 | 4 KB | The compat patch layer's own README |
| `project/` | 3 + log | 100 KB | `known-issues.md`, `owner-profile.md`, and `governance-log/` — durable project facts and the record of governance changes |
| `audits/` | 18 | 468 KB | **The decision-grade audit subset** — see §4 |

## 4. The audits kept, one line each

These are the documents a reader cannot reconstruct from the code, because they record *what was
decided and why it was rejected*, not what exists.

| file | why it stays |
|---|---|
| `2026-10-01-README.md` | the corpus's own index |
| `2026-09-30-northstar-gap.md` | **the original gap analysis** (94 KB, the largest single document) — where the project stood against the north star |
| `2026-09-30-base-to-head.md` | the adjudication table: 13 fixes mutation-verified, 2 findings explicitly rejected |
| `2026-09-30-shape-review.md` | the first shape review, including the hypothesis it killed |
| `2026-10-01-northstar-intent.md` | the north star decoded from the original 27 prompts |
| `2026-10-01-review-standard-axis.md` | **claims-versus-reality verdict sheet** — re-derives 41 commit-message claims |
| `2026-10-01-review-spec-axis.md` | the second axis, and the list of claims that are not re-derivable |
| `2026-10-01-test-quality.md` | per-file verdict on every test the final rounds touched (71 KB) |
| `2026-10-01-selfplay-gate.md` | where the self-play evaluation substrate lies |
| `2026-10-03-handover-archaeology.md` | how this corpus was made, and what never got written |
| `2026-10-03-decision-brief.md` | **the questions put to the Owner, verbatim** — two of them never answered |
| `2026-10-03-one-night-runbook.md` | the operational runbook for playing one night end to end |
| `2026-10-03-contribution-audit.md` | which commits actually advanced the north star and which were indirect |
| `2026-10-03-model-backend-options.md` | the backend blocker, and the recommendation |
| `2026-10-03-northstar-state.md` | the last state summary, and its three Owner-only questions |
| `2026-10-03-northstar-chain-broken.md` | why the chain was never connected once |
| `2026-10-03-northstar-instruments.md` | the three measuring instruments, and what each is blind to |
| `2026-10-03-survival-production-wiring.md` | the design report, and the two acceptance items explicitly **not** met |

## 5. Dropped, and where the conclusion lives

| dropped | count / size | why | the conclusion lives in |
|---|---|---|---|
| raw model-round transcripts | 2 files, 317 KB | raw conversation dumps, unedited, containing the model's own mistakes | nothing — they were evidence that the rounds were **invalidated**, which `2026-10-03-northstar-state.md` records |
| `prompt-surface-measured/` dump | 1 dir, 253 KB | a JSON token dump and a throwaway probe | `docs/agency/test-census.md` for the counting rule |
| wave / peripheral working notes | ~110 files, ~2.5 MB | one dispatched slice's notes; their findings were distilled into `docs/agency/` | `docs/agency/guarantees.md`, `failure-shapes.md`, `command-to-action.md`, `vendored-tree.md`, `test-census.md` |
| single-finding notes from 10-03 (28 files) | ~250 KB | each is one worker's finding on one file; the findings are in the agency docs and in the commits | same |
| `project/handoff/archive/` | 17 files | per-session handoffs; superseded by `docs/history/audits/` | — |
| `project/ai-session-notes.md`, `session-history.md`, `tasks/` | 3 files | session bookkeeping, not decisions | — |
| `.ai-notes/sandbox/`, `.ai-notes/tmp/`, `.ai-notes/_scratch/` | — | other models' sandboxes and probe dumps; evidence only for their own windows | — |

**Two files that were byte-identical duplicates of each other** — `.tmp_audit.md` and
`.tmp_report.md`, 37 KB apiece, the same 233-line document under two names — were left where they
were. That is the kind of thing this audit exists to catch, and it is noted here rather than
silently tidied.

## 6. What a reader should do with this

Start at [`../ARCHIVE.md`](../ARCHIVE.md). Then:

- **What is built** → `../agency/guarantees.md` (each row has the `file:line` and the test that
  pins it), `../agency/vendored-tree.md` (the embedded Minecraft tree's extent and its two
  registry gaps).
- **How to work in this repo** → `reference/commit-convention.md`, `reference/doc-style-guide.md`,
  `reference/cc-workflow-guide.md`, and the root `CLAUDE.md` / `AGENTS.md`.
- **Why the code looks the way it does** → `architecture/`, and the five ADRs in
  `architecture/adr/`.
- **What was tried and rejected** → `audits/`, especially the two `review-*-axis` files. They are
  the most valuable documents here and the least likely to be re-derived.
- **The recurring defect** → `../agency/failure-shapes.md`, sixteen worked instances.

## 7. Licence

Inherited from the repository: CC BY-NC-ND 4.0 for the original work. These files describe and
quote the vendored Minecraft 1.8.9 sources; **Mojang's sources, mappings and assets are not
covered by that licence.**