#!/usr/bin/env python3
"""Check every hook-point signature in docs/mc189 against the compiled bytecode.

The documents are model-written, so their method signatures are the part most
likely to be subtly wrong. This resolves each one against .codegraph — which is
derived from javap, not from prose — and reports the ones that do not exist.

    python3 tools/codegraph/build_codegraph.py     # refresh the graph first
    python3 tools/codegraph/validate_docs.py

Exit status is 1 when any signature fails to resolve, so this can gate CI.

Unresolved does not always mean wrong: a hook-point row may cite the call site
rather than the declaring class, which is often the more useful reference. Read
the reported lines before editing them.
"""

import collections
import glob
import json
import os
import re
import sys

GRAPH = os.environ.get("CODEGRAPH_DIR", ".codegraph")
DOCS = os.environ.get("MC189_DOCS", "docs/mc189")
HOOK_HEADING = "挂钩点"

SIGNATURE = re.compile(r"`([^`]+)`")
FILE_REF = re.compile(r"(\w+)\.java:\d+")
METHOD_NAME = re.compile(r"([A-Za-z_$][\w$]*)\s*\(")


def load_methods():
    """Simple class name -> declared methods, with inner-class members also
    registered under the outer class (javap reports Outer$Inner separately, but
    documents cite the file, which is the outer name)."""
    by_name = collections.defaultdict(set)
    path = os.path.join(GRAPH, "classes.jsonl")
    if not os.path.exists(path):
        sys.exit(f"{path} missing — run tools/codegraph/build_codegraph.py first")
    with open(path) as fh:
        for line in fh:
            cls = json.loads(line)
            simple = cls["name"].rsplit(".", 1)[-1]
            by_name[simple] |= set(cls["methods"])
            by_name[simple.split("$")[0]] |= set(cls["methods"])
    return by_name


def hook_rows(path):
    """Yield (line_no, signature, [cited classes]) for the Hook Points table."""
    in_hooks = False
    with open(path) as fh:
        for n, line in enumerate(fh, 1):
            if line.startswith("## "):
                in_hooks = HOOK_HEADING in line
                continue
            if not in_hooks or not line.startswith("|"):
                continue
            sig = SIGNATURE.search(line)
            if not sig:
                continue
            yield n, sig.group(1), FILE_REF.findall(line)


def main():
    methods = load_methods()
    checked = 0
    unresolved = []

    for path in sorted(glob.glob(os.path.join(DOCS, "*.md"))):
        if os.path.basename(path) == "README.md":
            continue
        for line_no, sig, classes in hook_rows(path):
            name = METHOD_NAME.search(sig)
            if not name or not classes:
                continue
            method = name.group(1)
            checked += 1
            found = any(
                method in methods.get(c, ()) or (method == c and "<init>" in methods.get(c, ()))
                for c in classes
            )
            if not found:
                unresolved.append((path, line_no, method, classes[0]))

    ok = checked - len(unresolved)
    pct = 100.0 * ok / checked if checked else 0.0
    print(f"{ok}/{checked} hook-point signatures resolve against the bytecode ({pct:.1f}%)")

    for path, line_no, method, cls in unresolved:
        print(f"  {path}:{line_no}  {method}() not declared by {cls}")

    # Checking nothing is not passing. On this branch DOCS does not exist at all -- docs/mc189 lives
    # on docs/mc189-source-map -- so this printed "0/0 ... (0.0%)" and exited 0, a gate whose own
    # docstring offers it to CI reporting success for having validated zero signatures. That is the
    # failure shape this repository keeps hitting, so the empty case is now loud.
    if not checked:
        where = DOCS if os.path.isdir(DOCS) else f"{DOCS} (no such directory)"
        print(f"no hook-point signatures found under {where} -- nothing was validated.")
        print("  docs/mc189 lives on the docs/mc189-source-map branch; point MC189_DOCS at a")
        print("  checkout of it, or run this from that branch.")
        return 1

    return 1 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main())
