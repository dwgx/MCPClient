# pg 模块 — macOS arm64 移植调研

分支 `origin/mcp-core`(未 checkout,全部用 `git show origin/mcp-core:<path>` 只读方式阅读)。

## 这个模块是什么

pg = **PatchGuard**,一个注解驱动的 **构建期(build-time)字节码加固库**。不是运行时组件。事实依据:

- `pg/pom.xml:14` `<packaging>pom</packaging>`,聚合三个子模块 `pg-api` / `pg-engine` / `pg-maven-plugin`(`pg/pom.xml:33-37`)。
- 加固发生在编译之后、打包之前:Mojo 绑定 `PROCESS_CLASSES` 生命周期阶段(`HardenMojo.java:31` `@Mojo(name = "harden", defaultPhase = LifecyclePhase.PROCESS_CLASSES)`),扫描 `target/classes` 下的 `.class`,原地替换为加固后的字节码。
- 三个 artifact,职责分离:
  - **pg-api**:仅一个 `@Guarded` 注解(`Guarded.java:35`),`RetentionPolicy.CLASS`(`Guarded.java:34`),零依赖,Java 8 字节码(`pg-api/pom.xml:24` `<maven.compiler.release>8</maven.compiler.release>`)。业务代码唯一 import 的东西,运行时无行为(`Guarded.java:13-14`)。
  - **pg-engine**:变换引擎。`HardenPass` SPI(`HardenPass.java:26`)+ 基于 **ASM 9.8**(`pg/pom.xml:40`)的具体 pass。fail-safe:每个输出用 `CheckClassAdapter` 复验,任何失败都保留原始类(`HardenEngine.java:58-104`)。只依赖 pg-api + ASM(`pg-engine/pom.xml:30-50`)。构建期 JDK 下限 17(`pg-engine/pom.xml:27`)。
  - **pg-maven-plugin**:构建期 Mojo,扫描 `@Guarded`,跑引擎,原子替换 `.class`(`HardenMojo.java`)。构建期 JDK 下限 17(`pg-maven-plugin/pom.xml:25`)。
- 目前唯一消费者是 `board` 模块:`board/pom.xml:44` 以 `provided` scope 依赖 pg-api,`board/pom.xml:68` 绑定 pg-maven-plugin,`<seed>1337</seed>`(`board/pom.xml:71`)固定种子做可复现构建。

当前唯一实装的 pass 是 `StringConstantPass`(`HardenEngine.java:43`),STANDARD 级:把 `LDC "字面量"` 换成 per-class XOR 密文 + 注入的 `pg$dec` 静态解码器(`StringConstantPass.java:52-115`)。`FLOW` / `VIRTUALIZE` 级只是注解里的枚举占位(`Guarded.java:52-72`),引擎里尚未注册对应 pass(`HardenEngine.java:44-45` 注释「Future passes ... register here」)。

**结论:纯 JVM 字节码处理 + Maven 插件,全部在构建期跑,与操作系统、CPU 架构、原生库完全无关。macOS arm64 无需任何改动。**

## 文件清单

- `pg/README.md` — 模块说明:PatchGuard 定位、三 artifact、Level 语义、构建命令。
- `pg/pom.xml` — 聚合 POM,声明三子模块,定义 `asm.version=9.8`。
- `pg/pg-api/pom.xml` — pg-api POM,Java 8 release,零依赖(仅 junit test)。
- `pg/pg-api/src/main/java/net/marcloud/pg/Guarded.java` — `@Guarded` 注解,CLASS retention,`Level` 枚举(STANDARD/FLOW/VIRTUALIZE)。
- `pg/pg-engine/pom.xml` — pg-engine POM,依赖 pg-api + asm/asm-tree/asm-util,release 17。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/FrameSafeClassWriter.java` — `ClassWriter` 子类,`getCommonSuperClass` 恒返回 `java/lang/Object`,避免 COMPUTE_FRAMES 触发 `Class.forName`。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/GuardedScanner.java` — 从原始字节码读 `@Guarded` 标记(不加载类),取最强 Level。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/HardenContext.java` — 每类上下文:className、level、确定性 seed、日志 sink。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/HardenEngine.java` — 引擎主体:scan → 跑 pass → verify,失败回退原字节;`defaults()` 注册内置 pass。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/HardenPass.java` — pass SPI 接口:`id()` / `minLevel()` / `apply()`。
- `pg/pg-engine/src/main/java/net/marcloud/pg/engine/pass/StringConstantPass.java` — STANDARD 级字符串常量加固,XOR 密文 + 注入 `pg$dec` 解码器,幂等(KI-6)。
- `pg/pg-engine/src/test/java/net/marcloud/pg/engine/HardenEngineTest.java` — teeth 测试:去明文 + 保行为 + 幂等(KI-6)+ 帧合并(KI-7)+ 未标记类零改动。
- `pg/pg-maven-plugin/pom.xml` — 插件 POM,maven-plugin 打包,依赖 pg-engine + maven-plugin-api/core(provided),release 17。
- `pg/pg-maven-plugin/src/main/java/net/marcloud/pg/plugin/HardenMojo.java` — `harden` Mojo,遍历 `.class`,调引擎,原子替换文件。

## macOS 移植阻碍

**无。**

pg 全家桶是纯 JVM 字节码处理,仅在构建期运行,与 OS/架构/原生库无耦合。逐项排查依据:

| 排查项 | 结论 | 依据(file:line) |
| --- | --- | --- |
| 字节码/classfile 处理 | 纯 ASM 字节数组操作,平台无关 | `HardenEngine.java:112-142`、`StringConstantPass.java:52-115` |
| 文件路径处理 | 用 `java.nio.file` (`Paths`/`Files.walk`/`Files.move`);类名转换同时替换 `/` 和 `\\`,两种分隔符都兼容 | `HardenMojo.java:52,61,106-108` |
| 原子替换 | `ATOMIC_MOVE` 失败自动降级为普通 replace,不假设任何文件系统语义 | `HardenMojo.java:115-120` |
| OS 分支 / `os.name` 判断 | 无。全模块无任何操作系统条件分支 | 全部 14 个文件 |
| 原生代码 / JNI / 原生库 | 无。零 native,仅 ASM + JDK 标准库 | `pg-engine/pom.xml:30-50`、`pg-api/pom.xml`(零依赖) |
| Windows 工具链依赖 | 无。名字里的「PatchGuard / NT motif」纯属命名主题(`README.md:5-6`),非技术依赖 | `README.md:1-6`、`pg/pom.xml:16-18` |
| 类加载器假设 | 反而是 macOS 友好的:`FrameSafeClassWriter` 刻意不经 classloader 解析类型,避免任何环境相关的 `Class.forName` | `FrameSafeClassWriter.java:34-38`、`HardenEngine.java:115-133` |

补充:目标运行时 Temurin JDK 25 / Apple M2 对本模块不产生约束——加固后的字节码目标是 Java 8(`Guarded.java` 注解 CLASS 保留;board 输出 Java 8),pg 自身编译到 JDK 17 下限并在构建 JVM 上运行,产物是普通 `.class`,运行时不再需要 pg 任何东西(解码器 `pg$dec` 以 ASM 直接注入,无运行时 pg 依赖,见 `StringConstantPass.java:134`「needs no runtime pg dependency」)。

## 不确定的地方

- **ASM 9.8 + 构建 JVM 为 JDK 25 时的兼容性**:若开发者用 JDK 25 直接跑 `mvn`(而非 JDK 17),ASM 9.8 读取/写入 JDK 25 自身产生的高版本 class 文件时可能遇到「Unsupported class file major version」。但这与 macOS 无关(在任何 OS 上同样表现),且被加固的 board 类是 Java 8 字节码,通常无碍。需在真机上跑一次 `./mvnw -pl pg/pg-engine test` 确认。依据:`pg/pom.xml:40`(asm 9.8)、`pg-engine/pom.xml:27`(release 17)。
- **`java.security.SecureRandom` 的默认种子源在 macOS 上的行为**:`HardenMojo.java:57` 在 `seed == -1` 时用 `SecureRandom().nextLong()`。macOS 上 SecureRandom 走 `/dev/urandom`,预期正常、非阻塞;但 board 已固定 `seed=1337`(`board/pom.xml:71`)不走这条路。仅当有人用随机种子构建时才涉及,属常规 JDK 行为,列此仅为完整性。
