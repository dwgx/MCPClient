# Live client + GL smoke test

`smoke-live-gl.sh` (with `smoke-live-gl.bat` as a Windows wrapper) is the
standing **"is the live Minecraft client healthy and is its GL context
readable"** gate. Headless unit tests prove logic; this proves the *running*
game actually booted a real OpenGL context on a real GPU and that the MCP Core
Kernel can read it back over the HTTP facade.

## What it does

1. Launches MC 1.8.9 with the Kernel attached — the same invocation as
   `scripts/run-mcp.bat` (fat agent jar as `-javaagent` **and** on `-cp`),
   cwd = `test_run/`.
2. Waits for the MCP Core HTTP facade to bind `127.0.0.1:1337`
   (`HttpFacade.DEFAULT_PORT`).
3. `POST /v1/tools/dev_probe` and reads the returned live GL section.
4. Asserts `game.up == true` **and** `gl.present == true`.
5. Tears the client down (kills the real `java.exe` that owns the port) unless
   `--keep`.

If a facade is *already* serving the port, it probes that instance instead of
launching a second one.

## Requirements (a REAL machine with a display — cannot pass headless)

- JetBrains Runtime 25 at `_tools/jbrsdk-25.0.3-windows-x64-b508.16`
  (or `export JBR_HOME=...`).
- Built jars: `client/target/MCP-1.8.9.jar` and `core/target/core-1.8.9-all.jar`.
  Build the agent jar with `./mvnw -q -pl core -am package -DskipTests`.
- `curl` on PATH (Git Bash / MSYS ships it).

## Run it

```bash
# Git Bash / MSYS (from repo root)
scripts/smoke-live-gl.sh                 # launch, probe, assert, teardown
scripts/smoke-live-gl.sh --keep          # leave the client running afterwards
scripts/smoke-live-gl.sh --timeout 200   # wait longer for the facade
scripts/smoke-live-gl.sh --port 1337     # facade port (default 1337)
```

```bat
REM cmd.exe (from repo root) — same flags, via Git Bash under the hood
scripts\smoke-live-gl.bat
scripts\smoke-live-gl.bat --keep
```

### Path overrides (env)

Each jar / dir defaults to the layout under the repo root but can be overridden,
so the harness runs from a checkout that lacks the built artifacts:

```bash
JBR_HOME=... GAME_JAR=... CORE_JAR=... ARGS_FILE=... GAME_DIR=... \
  scripts/smoke-live-gl.sh
```

## Exit codes

| code | meaning |
|------|---------|
| 0 | PASS — client alive and GL context readable |
| 1 | FAIL — facade up but `game.up`/`gl.present` assertion failed |
| 2 | TIMEOUT — facade never bound (launch or display failure) |
| 3 | SETUP — missing jar / JBR / curl |

## Verified result (2026-07-13, dwgx's machine)

Cold launch → PASS (exit 0). `dev_probe` returned a real context:

```
gl.version  : 4.6.0 NVIDIA 596.13
gl.vendor   : NVIDIA Corporation
gl.renderer : NVIDIA GeForce RTX 5070 Ti Laptop GPU/PCIe/SSE2
gl.note     : compatibility profile (expected for 1.8.9 fixed-function)
```

Teardown killed the port-owning `java.exe`; no orphans left.

## Note for automation / CI agents

This is **not** a headless test. GLFW window + GL context creation needs a real
display and GPU. On a headless box `GlContextProbe.capture()` degrades to
`present:false` and the assertion fails by design — that is the honest signal
that live GL testing needs a real display, not a false green.
