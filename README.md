# MCPClient (the Kernel)

A live [Minecraft](https://www.minecraft.net/) 1.8.9 client (LWJGL3, JDK 25) exposed to an LLM over [MCP](https://modelcontextprotocol.io/). The model can observe, act on, hot-swap, and debug the running JVM. Every tool call goes through a 7-layer NT-style privilege kernel.

> ### ARCHIVED — 2026-10-04
>
> **This project is closed and receives no further operations work.** The tree builds, the
> suite is green and every number in it is reproducible with one command. The closing record —
> what was finished, **what was not**, and **what will still be true after the archive** (the
> trust chain expires 2027-09-30, and the root-rotation ceremony cannot currently be re-run) —
> is [`docs/ARCHIVE.md`](docs/ARCHIVE.md).
>
> The code remains, and remains checkable:
>
> ```bash
> python scripts/test-census.py --run    # the test count, and why it is that number
> ```

Site: <https://dwgx.github.io/MCPClient/>

This is a research project. It is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang.

## License

Original work in this repository is licensed under
[Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International](https://creativecommons.org/licenses/by-nc-nd/4.0/)
(`CC BY-NC-ND 4.0`). The legal code is in [`LICENSE`](LICENSE).

In plain terms, for the original work:

- You may copy and share it **verbatim**, for **non-commercial** purposes, with attribution.
- You may **not** use it commercially.
- You may **not** distribute modified versions, including open-source forks or "I rewrote it and published it" derivatives.
- Other uses (commercial, derivatives, dual-license, exception): open a [GitHub issue](https://github.com/dwgx/MCPClient/issues) or contact [@dwgx](https://github.com/dwgx).

This license does **not** cover:

- Vanilla Minecraft 1.8.9 sources, mappings, and assets under `client/` (Mojang). You need a lawful copy of the game.
- Third-party libraries pulled by Maven (LWJGL, qml4j, Skija, MCP SDK, and so on). Those keep their own licenses.

## Modules

| Layer | Module | Role |
|---|---|---|
| Platform | `lwjgl2-shim/` | LWJGL2 to LWJGL3 ABI shim |
| Spine | `core/` | MCP server, 7-layer kernel, capability packs, JVMTI |
| Spine | `board/` | Client feature framework (PCB). No hard dependency on core |
| Spine | `client/` | Minecraft 1.8.9 vanilla mapping |
| Aux | `pg/` `dwm/` | Detachable. `dwm` is qml4j painted into a real `GuiScreen`. GL / ImGui / Skiko backends were removed |

## Build and run

Requires JDK 25. Fat agent jar:

```bash
./mvnw -q -pl core -am package -DskipTests
```

Core tests:

```bash
./mvnw -pl core test
```

Windows, game + MCP:

```bat
scripts\run-mcp.bat
scripts\run-mcp-overlay.bat
```

`run-mcp-overlay.bat` arms the KI-11 DWM overlay (Right Shift). UI substrate is Maven Central qml4j (`dwm/pom.xml`). Live map: [`dwm/README.md`](dwm/README.md).

## Docs

Tracked design notes live under [`docs/`](docs/). Start at [`docs/README.md`](docs/README.md).
The agency path (one command to an in-game action) is [`docs/agency/command-to-action.md`](docs/agency/command-to-action.md).

Project status and closing record: [`docs/ARCHIVE.md`](docs/ARCHIVE.md).
