# pg — PatchGuard: annotation-driven code hardening

A standalone, annotation-driven bytecode-hardening library. Mark a class with
`@Guarded` and the build applies hardening passes automatically, emitting a
hardened jar with zero source pollution. Named after Windows kernel PatchGuard
(anti-tamper of kernel code), matching this project's NT-architecture motif.

## Modules

- **pg-api** — the `@Guarded` annotation. The only artifact business code imports.
  Zero dependencies, Java 8 bytecode so every module can use it.
- **pg-engine** — the transform engine: a `HardenPass` SPI plus ASM-based passes,
  fail-safe (re-verifies output, keeps the original class on any failure).
- **pg-maven-plugin** — build-time Mojo (`pg:harden`, bound to `process-classes`):
  scans `@Guarded`, runs the engine, atomically replaces the compiled `.class`.

## Use

```java
import net.marcloud.pg.Guarded;

@Guarded                                  // default STANDARD level
public final class Secretish { ... }
```

In the consuming module's pom, depend on `pg-api` and bind the plugin:

```xml
<dependency>
  <groupId>net.marcloud.mcp.189</groupId>
  <artifactId>pg-api</artifactId>
  <version>${project.version}</version>
</dependency>
...
<plugin>
  <groupId>net.marcloud.mcp.189</groupId>
  <artifactId>pg-maven-plugin</artifactId>
  <version>${project.version}</version>
  <executions>
    <execution><goals><goal>harden</goal></goals></execution>
  </executions>
</plugin>
```

`mvn package` then hardens every `@Guarded` class in the module.

## Levels

`STANDARD` (metadata + constant hardening, zero runtime overhead) ⊂ `FLOW`
(+ control-flow hardening) ⊂ `VIRTUALIZE` (+ ISA virtualization). Each level is a
superset of the one below.

## Extending

Add a hardening technique = implement `HardenPass` and register it in
`HardenEngine.defaults`. Nothing in pg-api or business code changes.

## Honest boundary

Hardening buys time against reverse engineering on a machine the attacker
controls; it is not an information-theoretic wall. A runtime memory dump still
recovers decoded values. The value is raising static/automated analysis cost,
paired with a fast release cadence. Design detail and the full hardening stack
live in the AI design notes (gitignored).

## Build

```bash
./mvnw -pl pg/pg-api,pg/pg-engine,pg/pg-maven-plugin -am install
./mvnw -pl pg/pg-engine test        # teeth-verified hardening tests
```
