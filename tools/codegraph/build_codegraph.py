#!/usr/bin/env python3
"""Build a call graph of the compiled client + shim from javap output.

Ground truth for architecture work: unlike source greps this sees the real
resolved call sites, including ones the decompiled MCP names make hard to grep
for. Output is two JSONL files that cg.py queries.

Usage:
    python3 tools/codegraph/build_codegraph.py [--out .codegraph]

Requires the modules to have been compiled first (./mvnw -q package -DskipTests).
"""

import argparse
import json
import os
import re
import subprocess
import sys

CLASS_ROOTS = ["client/target/classes", "lwjgl2-shim/target/classes"]
BATCH = 60

CLASS_DECL = re.compile(
    r"^(?:public |final |abstract |static |private |protected )*"
    r"(?:class|interface|enum) ([\w.$]+)"
    r"(?: extends ([\w.$]+))?"
    r"(?: implements ([\w.$, ]+))?"
)
def method_name(line, cur_class):
    """Name of the member declared on this javap line, or None.

    Member declarations are the only ones indented exactly two spaces; the Code
    section is indented further. A regex anchored on `);` misses every method
    with a `throws` clause, which then silently donates its call sites to the
    previously declared method — so parse positionally instead.
    """
    if not line.startswith("  ") or line[2:3] in (" ", ""):
        return None
    s = line.strip()
    if s.startswith("static {"):
        return "<clinit>"
    if "(" not in s:
        return None  # field
    head = s[: s.index("(")].split()
    if not head:
        return None
    name = head[-1]
    if "." in name or name == cur_class.rsplit(".", 1)[-1]:
        return "<init>"
    return name
INVOKE = re.compile(
    r"invoke(virtual|static|special|interface)\s+#\d+[,\s\d]*//\s*(?:Interface)?Method\s+(\S.*)$"
)
FIELD_REF = re.compile(r"(?:get|put)(?:field|static)\s+#\d+[,\s\d]*//\s*Field\s+(\S.*)$")


def split_ref(ref, cur_class):
    """Resolve a javap constant-pool reference to (owner, member).

    javap omits the owner for members of the class being disassembled
    (`// Method getSystemTime:()J`) and slash-separates it otherwise
    (`// Method net/minecraft/util/Timer."<init>":(F)V`). Missing that first
    case silently drops every intra-class edge, which is most of the graph.
    """
    ref = ref.strip().split(":", 1)[0]  # drop the descriptor
    if "." in ref:
        owner, _, member = ref.rpartition(".")
        owner = owner.replace("/", ".")
    else:
        owner, member = cur_class, ref
    return owner, member.strip('"')


def class_files(roots):
    for root in roots:
        if not os.path.isdir(root):
            print(f"warn: {root} missing — build first", file=sys.stderr)
            continue
        for dirpath, _, names in os.walk(root):
            for n in names:
                if n.endswith(".class"):
                    yield root, os.path.join(dirpath, n)


def to_binary_name(root, path):
    rel = os.path.relpath(path, root)
    return rel[: -len(".class")].replace(os.sep, ".")


def run_javap(root, names):
    cmd = ["javap", "-p", "-c", "-classpath", root] + names
    proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    return proc.stdout


def parse(text, classes, edges):
    cur_class = None
    cur_method = None
    for line in text.splitlines():
        stripped = line.strip()
        if not line.startswith(" ") and ("class " in line or "interface " in line or "enum " in line):
            m = CLASS_DECL.match(line.strip())
            if m:
                cur_class = m.group(1)
                classes[cur_class] = {
                    "name": cur_class,
                    "super": m.group(2),
                    "interfaces": [s.strip() for s in (m.group(3) or "").split(",") if s.strip()],
                    "methods": [],
                }
                cur_method = None
            continue
        if cur_class is None:
            continue
        name = method_name(line.rstrip(), cur_class)
        if name is not None:
            cur_method = name
            classes[cur_class]["methods"].append(cur_method)
            continue
        if "invoke" in stripped:
            mi = INVOKE.search(stripped)
            if mi and cur_method:
                owner, callee = split_ref(mi.group(2), cur_class)
                edges.append((f"{cur_class}#{cur_method}", f"{owner}#{callee}", mi.group(1)))
        elif "Field" in stripped:
            mf = FIELD_REF.search(stripped)
            if mf and cur_method:
                owner, field = split_ref(mf.group(1), cur_class)
                edges.append((f"{cur_class}#{cur_method}", f"{owner}#{field}", "field"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".codegraph")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    by_root = {}
    for root, path in class_files(CLASS_ROOTS):
        by_root.setdefault(root, []).append(to_binary_name(root, path))

    classes, edges = {}, []
    total = sum(len(v) for v in by_root.values())
    done = 0
    for root, names in by_root.items():
        names.sort()
        for i in range(0, len(names), BATCH):
            parse(run_javap(root, names[i : i + BATCH]), classes, edges)
            done += len(names[i : i + BATCH])
            print(f"\r{done}/{total} classes", end="", file=sys.stderr, flush=True)
    print(file=sys.stderr)

    with open(os.path.join(args.out, "classes.jsonl"), "w") as fh:
        for c in sorted(classes.values(), key=lambda c: c["name"]):
            fh.write(json.dumps(c) + "\n")
    seen = set()
    with open(os.path.join(args.out, "edges.jsonl"), "w") as fh:
        for src, dst, kind in edges:
            key = (src, dst)
            if key in seen:
                continue
            seen.add(key)
            fh.write(json.dumps({"from": src, "to": dst, "kind": kind}) + "\n")

    print(f"{len(classes)} classes, {len(seen)} unique edges -> {args.out}/")


if __name__ == "__main__":
    main()
