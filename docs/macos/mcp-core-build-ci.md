# mcp-core 分支:reactor pom 与 CI(macOS arm64 移植调查)

调查对象:`origin/mcp-core` 分支上的 `pom.xml`、各模块 pom、`.github/workflows/build.yml`、`.mvn/`、`.gitignore`、`CLAUDE.md`。全部通过 `git show origin/mcp-core:<path>` 只读读取,未检出分支。

## 这个模块是什么

这是整个仓库的 Maven reactor 聚合根 + CI 定义。事实如下(均来自实际读到的文件):

**Reactor 模块清单**(`pom.xml:14-21`,声明顺序):

```
lwjgl2-shim, client, core, board, pg, dwm
```

其中 `pg` 本身是 pom 聚合器,再含三个子模块(`pg/pom.xml:36-40`):`pg-api`、`pg-engine`、`pg-maven-plugin`。与 `origin/main` 相比,mcp-core 的根 pom 只多了 `core`、`board`、`pg`、`dwm` 四行 module(`git diff origin/main origin/mcp-core -- pom.xml` 确认),其余属性完全一致。

**实际构建顺序**(由依赖图决定,非声明顺序):

1. `lwjgl2-shim`(无内部依赖)
2. `client`(依赖 `lwjgl2-shim`,`client/pom.xml:25-28`)
3. `pg-api` → `pg-engine`(依赖 pg-api)→ `pg-maven-plugin`(依赖 pg-engine)
4. `board`(provided 依赖 `client` 与 `pg-api`,且构建期绑定 `pg-maven-plugin:harden` 于 process-classes,`board/pom.xml:31-37, 60-84`)
5. `core`(provided 依赖 `client`;**test scope 依赖 `board`**,`core/pom.xml:44-50, 97-103`,所以 core 必须排在 board 之后)
6. `dwm`(零依赖,空概念模块,顺序无关)

**各模块产物**:

| 模块 | 产物 | 依据 |
|---|---|---|
| `lwjgl2-shim` | `lwjgl2-shim-1.8.9.jar` 普通库 jar;另配了 GitHub Packages 的 `distributionManagement`(deploy 用,URL 还是 `OWNER/REPO` 占位符) | `lwjgl2-shim/pom.xml:63-73` |
| `client` | shaded fat jar `client/target/MCP-1.8.9.jar`(`finalName`,shade 掉 shim + LWJGL3 + 全部依赖,Multi-Release:true) | `client/pom.xml:191, 195-249` |
| `core` | 两个 jar:普通 `core-1.8.9.jar`(带 Premain-Class/Agent-Class agent manifest)+ shaded `core-1.8.9-all.jar`(classifier `all` 的 fat agent jar,含 ByteBuddy + MCP SDK + Jackson/Reactor;client 为 provided 不打包) | `core/pom.xml:108-124, 130-177` |
| `board` | 普通 jar,但 process-classes 阶段被 `pg-maven-plugin` 原地改写 @Guarded 类字节码(seed 固定 1337) | `board/pom.xml:60-84` |
| `pg-api` / `pg-engine` / `pg-maven-plugin` | 普通 jar(J8 / J17)/ maven-plugin(J17) | `pg/*/pom.xml` |
| `dwm` | 空 jar(模块刻意无代码,仅保留架构意图) | `dwm/pom.xml:16-31` |

**native 模块与 Maven 的关系:完全没有接线。** `core/src/main/native/core-jvmti/` 下只有 `core-jvmti.c/.h`、`CMakeLists.txt`、`build-clang.sh`、`build.bat`——core 的 pom 里没有任何 native 相关的 plugin/profile/execution(grep `jvmti|native` 零命中)。JVMTI agent 是纯手动、Windows 专用的旁路构建:`build-clang.sh:21` 硬编码 `_tools/jbrsdk-25.0.3-windows-x64-b508.16/include`(可用 `JBRINC` 环境变量覆盖),`build-clang.sh:23` 默认 `clang.exe`,产物 `core-jvmti.dll`;`CMakeLists.txt:6` 同样硬编码 Windows JBR 头文件路径。因此 **reactor 构建本身不会碰 native,macOS 上 `mvnw package` 不会因它失败**;缺的只是运行时的 C6 JVMTI 能力(需要另做 `.dylib` 构建路径,详见 `docs/macos/mcp-core-core-native-jvmti.md`)。

**Java 版本布局**:根 pom `maven.compiler.release=8`(`pom.xml:25`);`core` 与 `dwm` 覆写为 25(`core/pom.xml:29`、`dwm/pom.xml:17`),`pg-engine`/`pg-maven-plugin` 为 17。全 reactor 必须用 JDK 25 构建——与目标环境 Temurin JDK 25 相符。

**CI 工作流**(`.github/workflows/build.yml`,与 main 分支**逐字节相同**,`git diff` 为空):

- 触发:push 到 `main`/`mcp-core`/`rank1-encryption-test`、所有 PR、手动 dispatch(build.yml:6-12)。注意这与 main 上最近一条提交注释"manual-only"不符——两个分支上的这份文件实际都是 push 自动触发的版本。
- 单一 job,`ubuntu-latest` + Temurin JDK 25 + Maven 缓存(build.yml:15-24)。
- 步骤:`./mvnw test`(reactor 全量单测)→ `clean package -DskipTests` → 断言产物:shim jar 和 `client/target/MCP-1.8.9.jar` 必须存在;**`core-*.jar` 只在 `core/` 目录存在时才断言**(build.yml:39-49)——这就是"同一份 workflow 适配两个分支"的机制:差异全部来自各分支自己 pom 的 module 列表,workflow 零特判。
- 再跑 `verify -Dsmoke.skip=false`:failsafe 冒烟 IT 只在 client 模块绑定(`client/pom.xml:253-268`,默认 `smoke.skip=true`),CI 上无游戏资产时 IT 通过 Assume 自跳过。
- 上传产物:仅 `lwjgl2-shim` jar 和 `MCP-1.8.9.jar`(build.yml:55-65);core 的 fat agent jar 被断言但**不上传**。
- 没有任何 macOS runner、没有 matrix——CI 产出的 fat jar 永远是 windows-natives 版。

**其余文件**:`.mvn/wrapper/maven-wrapper.properties` 用 wrapper 3.3.4、`only-script` 模式、从 Maven Central 拉 Maven 3.9.9,macOS 无障碍;`mvnw` 在分支上是 `100755` 可执行。`.gitignore` 与 CLAUDE.md 相对 main 各有新增(gitignore 多了 `core-jvmti.dll`、`.ai-notes/`、脚本产物等条目),纯工作区管理,与构建无关。根 pom 声明了一个自建仓库 `https://repo.marcloud.net/`(`pom.xml:69-75`,HTTPS),Mojang 系依赖(authlib/patchy/icu4j-core-mojang/twitch/paulscode)应从此处或 Central 解析。

## 文件清单

- `pom.xml` — reactor 聚合根:6 个 module、release=8、LWJGL 3.3.6 BOM、marcloud 仓库。
- `lwjgl2-shim/pom.xml` — LWJGL2 兼容垫片库 jar;LWJGL3 核心依赖(无 natives);GitHub Packages 发布占位。
- `client/pom.xml` — MC 1.8.9 客户端;**5 个 `natives-windows` classifier 依赖**;shade 出 `MCP-1.8.9.jar`;failsafe 冒烟 IT。
- `core/pom.xml` — MCP Core(J25):agent manifest + `core-1.8.9-all.jar` fat agent;client provided;board 仅 test scope;无 native 接线。
- `board/pom.xml` — 客户端功能框架(J8);绑定 pg-maven-plugin harden(seed=1337)。
- `pg/pom.xml` — PatchGuard 聚合器(pg-api/pg-engine/pg-maven-plugin)。
- `pg/pg-api/pom.xml` — @Guarded 注解契约,J8,零依赖。
- `pg/pg-engine/pom.xml` — ASM 9.8 硬化引擎,J17。
- `pg/pg-maven-plugin/pom.xml` — 构建期 Mojo(maven-plugin 打包),Maven 3.9.9 API,J17。
- `dwm/pom.xml` — 空概念模块,J25,产出空 jar。
- `.github/workflows/build.yml` — ubuntu + JDK25 的 reactor CI(test → package → 产物断言 → verify 冒烟 → 上传 shim/client jar);与 main 相同。
- `.mvn/wrapper/maven-wrapper.properties` — wrapper 3.3.4,only-script,Maven 3.9.9。
- `.gitignore` — 构建/运行时/参考区/AI 笔记忽略项;含手工编译的 `core-jvmti.dll`。
- `CLAUDE.md`(分支根)— AI 接手路由文档:模块分层表、命令、铁律;非构建输入。
- `core/src/main/native/core-jvmti/build-clang.sh`、`CMakeLists.txt` — 旁证 native 是 Maven 之外的 Windows 手动构建。
- 对照读取:`origin/main:pom.xml`、`origin/main:client/pom.xml`、`origin/main:.github/workflows/build.yml`(用于 diff)。

## macOS 移植阻碍

| 问题 | 位置 | 严重度 | 具体怎么改 | 工作量 |
|---|---|---|---|---|
| client 的 5 个 LWJGL natives 依赖硬编码 `natives-windows`,fat jar 里没有 macos-arm64 natives。**构建在 macOS 上不会失败**(natives jar 是普通 Maven 依赖,任何 OS 都能下载并 shade),但产出的 `MCP-1.8.9.jar` 在 M2 上无法启动(UnsatisfiedLinkError) | `client/pom.xml:33,39,45,51,57` | 高(运行阻断,非编译阻断) | 最小改法:给 lwjgl/lwjgl-glfw/lwjgl-opengl/lwjgl-openal/lwjgl-stb 各加一份 `<classifier>natives-macos-arm64</classifier>` runtime 依赖(与 windows 并存,fat jar 同时携带两平台,LWJGL 自选加载);版本由根 pom 的 lwjgl-bom 3.3.6 管理,不用写版本号。不建议用 os-activated profile——那会让产物依构建机而变,违背"最小改动" | 小(10 行 XML) |
| 无 macOS CI 覆盖:workflow 只有 `ubuntu-latest` 单 job,断言与上传的都是 windows-natives fat jar | `.github/workflows/build.yml:15` | 低(不阻断本地移植) | 若要 CI 验证 arm64:加一个 `runs-on: macos-14`(或 macos-15)job 跑 `./mvnw -B -ntp test package`;GitHub 的 macos-14+ runner 即 arm64 | 小 |
| core-jvmti 原生 agent 只有 Windows 构建路径(clang.exe、win32 头、.dll 产物),macOS 上 C6 调试能力缺失。不在 Maven build 内,所以**不阻碍 reactor 构建** | `core/src/main/native/core-jvmti/build-clang.sh:21-23`、`CMakeLists.txt:6` | 中(仅该运行时能力) | 在 build-clang.sh 增加 Darwin 分支:`-I$JAVA_HOME/include -I$JAVA_HOME/include/darwin`,输出 `libcore-jvmti.dylib`;脚本已支持 `JBRINC`/`CLANG` 环境变量覆盖,改动可以很小。属 native 模块的活,此处只记录"pom 不用动" | 中 |

除上述外,**没有其他阻碍**:全部 10 个 pom 中不存在任何 `<profile>` 或 `<os>` 激活(grep 零命中);`mvnw` 可执行位正确;wrapper 从 Central 取 Maven 3.9.9;JDK 25 满足 core/dwm 的 release=25;board 的 pg-maven-plugin 硬化是纯 Java ASM 改写,无平台代码。

## 不确定的地方

- `https://repo.marcloud.net/` 是否从这台 mac 可达、且确实托管 authlib/patchy/icu4j-core-mojang/twitch/paulscode 这些非 Central 构件——首次 `mvnw package` 在真机上跑一遍才知道(本地 `~/.m2` 若已有缓存则感知不到)。
- 任务背景说"另一分支的 fat jar 已带 macos-arm64 natives",但我在 `origin/main` 和 `origin/mcp-core` 的 client/pom.xml 里都只看到 `natives-windows`(其余远程分支未逐一排查);该改动可能在本地未推送的分支/工作区。合并时需确认以谁为准。
- CI 的 `verify -Dsmoke.skip=false` 会把整个生命周期重跑一遍(单测第二次执行);冒烟 IT 在 macOS 本地带资产运行时是否需要 `-XstartOnFirstThread`(failsafe fork 的 JVM 参数里目前没有配置 argLine),要真机验证。
- board 模块 seed=1337 的字节码硬化在 JDK 25/macOS 下产物是否逐字节一致(影响可复现构建的断言),需真机对比。
