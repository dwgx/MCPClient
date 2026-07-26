#!/usr/bin/env python3
"""Query the code graph produced by build_codegraph.py.

    python3 tools/codegraph/cg.py class GuiScreen
    python3 tools/codegraph/cg.py methods net.minecraft.client.Minecraft
    python3 tools/codegraph/cg.py callers 'Minecraft#runTick'
    python3 tools/codegraph/cg.py calls 'Minecraft#runTick'
    python3 tools/codegraph/cg.py subs net.minecraft.client.gui.GuiScreen
    python3 tools/codegraph/cg.py path 'Minecraft#runGameLoop' 'NetworkManager#sendPacket'

Method arguments match on a `Class#method` substring, so `#drawScreen` finds
every override and `GuiScreen#` every method on the class.
"""

import json
import os
import re
import sys
from collections import defaultdict, deque

GRAPH = os.environ.get("CODEGRAPH_DIR", ".codegraph")


def load():
    classes, out_edges, in_edges = {}, defaultdict(set), defaultdict(set)
    with open(os.path.join(GRAPH, "classes.jsonl")) as fh:
        for line in fh:
            c = json.loads(line)
            classes[c["name"]] = c
    with open(os.path.join(GRAPH, "edges.jsonl")) as fh:
        for line in fh:
            e = json.loads(line)
            out_edges[e["from"]].add(e["to"])
            in_edges[e["to"]].add(e["from"])
    return classes, out_edges, in_edges


def short(sig):
    cls, _, meth = sig.partition("#")
    return f"{cls.rsplit('.', 1)[-1]}#{meth}"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd, arg = sys.argv[1], sys.argv[2]
    classes, out_edges, in_edges = load()

    if cmd == "class":
        pat = re.compile(arg, re.I)
        for name in sorted(classes):
            if pat.search(name):
                c = classes[name]
                extra = f" extends {c['super']}" if c["super"] else ""
                if c["interfaces"]:
                    extra += " implements " + ", ".join(c["interfaces"])
                print(f"{name}{extra}")
    elif cmd == "methods":
        for name in sorted(classes):
            if arg in name:
                for m in classes[name]["methods"]:
                    print(f"{name}#{m}")
    elif cmd in ("callers", "calls"):
        edges = in_edges if cmd == "callers" else out_edges
        keys = [k for k in edges if arg in k]
        if not keys:
            print(f"no node matching {arg!r}", file=sys.stderr)
            return 1
        for k in sorted(keys):
            for other in sorted(edges[k]):
                print(f"{short(k)}  {'<-' if cmd == 'callers' else '->'}  {other}")
    elif cmd == "subs":
        for name in sorted(classes):
            c = classes[name]
            if c["super"] == arg or arg in c["interfaces"]:
                print(name)
    elif cmd == "path":
        if len(sys.argv) < 4:
            print("path needs two arguments", file=sys.stderr)
            return 1
        target = sys.argv[3]
        starts = [k for k in out_edges if arg in k]
        seen, queue = set(starts), deque((s, [s]) for s in starts)
        while queue:
            node, trail = queue.popleft()
            if target in node and len(trail) > 1:
                print(" -> ".join(short(t) for t in trail))
                return 0
            for nxt in out_edges.get(node, ()):
                if nxt not in seen:
                    seen.add(nxt)
                    queue.append((nxt, trail + [nxt]))
        print("no path found", file=sys.stderr)
        return 1
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
