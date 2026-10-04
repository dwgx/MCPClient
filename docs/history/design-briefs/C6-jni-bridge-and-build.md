---
doc: brief-c6-jni
title: Phase 2 C6 — native JVMTI debugger bridge + build chain + 7-layer gate wiring
layer: archive
status: archived
updated: 2026-07-11
parent: ../README.md
next:
  - path: ../architecture/03-NATIVE-C6-AND-BUILD.md
    when: 需要 C6 JNI 桥/构建的当前权威状态(clang 构建链)
read_if: 历史设计 brief;仅当你在改 C6 JNI 桥或构建链且想看原始设计意图时才读(MSVC 部分已弃,现用 LLVM/clang)。
---
> **归档设计 brief** — 构建链已从本文的 MSVC 改为 **LLVM/clang**(`build-clang.sh`,无需 Visual Studio/Windows SDK);已 live 验证。当前状态见 `../architecture/03-NATIVE-C6-AND-BUILD.md`。原样保留供参考。

AREA: Phase 2 C6 — native JVMTI debugger bridge (net.marcloud.mcp.core.debug) + MSVC build chain + 7-layer gate wiring

DESIGN:
PACKAGE net.marcloud.mcp.core.debug (all new). Mirrors seam/ (SeamController+SeamTools) and hook/ (DynamicHookManager+HookTools) split.

=== (A) DebuggerBridge — the ONE choke point + graceful fallback ===
`public final class DebuggerBridge` (make it a ProtectedClasses entry — see gate section).
Static gate computed once:
```
private static final boolean AVAILABLE;
private static final String  UNAVAILABLE_REASON;
private static final String DEFAULT_MSG =
    "native debugger not loaded — launch with -agentpath:core-jvmti.dll";
static {
    boolean ok = false; String why = DEFAULT_MSG;
    try {
        String p = System.getProperty("mcp.core.jvmtiLib");   // absolute path (same file as -agentpath)
        if (p != null && !p.isBlank()) System.load(java.nio.file.Path.of(p).toAbsolutePath().toString());
        else System.loadLibrary("core-jvmti");                // else java.library.path
        ok = nAgentReady();                                   // native probe: Agent_OnLoad ran AND AddCapabilities succeeded
        if (!ok) why = DEFAULT_MSG + " (library bound but JVMTI onload caps absent — dynamic attach cannot gain them)";
    } catch (Throwable t) {                                   // UnsatisfiedLinkError / SecurityException / anything
        ok = false; why = DEFAULT_MSG + " (" + t.getClass().getSimpleName() + ")";
    }
    AVAILABLE = ok; UNAVAILABLE_REASON = ok ? null : why;
}
public static boolean isAvailable()        { return AVAILABLE; }
public static String  unavailableReason()  { return UNAVAILABLE_REASON; }
```
KEY INVARIANT: no native method is EVER called unless AVAILABLE==true. Public wrappers begin with `ensure()`:
```
private static void ensure() { if (!AVAILABLE) throw new DebuggerUnavailableException(UNAVAILABLE_REASON); }
```
So an absent DLL yields DebuggerUnavailableException (a domain RuntimeException in this package), never a leaked UnsatisfiedLinkError. DebugTools catches it → err().

Native declarations (private static native; jvmtiError int returns, 0==JVMTI_ERROR_NONE; out-params via 1-element arrays so we keep a uniform int error channel):
```
private static native boolean nAgentReady();
private static native int  nSuspendThread(Thread t);
private static native int  nResumeThread(Thread t);
private static native int  nPopFrame(Thread t);                 // needs t suspended
private static native int  nForceEarlyReturnVoid(Thread t);
private static native int  nForceEarlyReturnInt(Thread t, int v);
private static native int  nForceEarlyReturnObject(Thread t, Object v);
private static native int  nSetBreakpoint(Class<?> c, String method, String sig, long location);
private static native int  nClearBreakpoint(Class<?> c, String method, String sig, long location);
private static native int  nSetSingleStep(Thread t, boolean enabled);
private static native int  nGetLocalObject(Thread t, int depth, int slot, Object[] out);
private static native int  nGetLocalInt(Thread t, int depth, int slot, int[] out);
private static native int  nSetLocalInt(Thread t, int depth, int slot, int value);
private static native int  nSetFieldModificationWatch(Class<?> c, String field, String sig);
private static native int  nClearFieldModificationWatch(Class<?> c, String field, String sig);
```
Public wrappers (each: ensure(); call; JvmtiError.check(rc) which throws DebuggerException(codeName) on non-zero). E.g.:
```
public static void suspendThread(Thread t){ ensure(); JvmtiError.check(nSuspendThread(t)); }
public static Object readLocalObject(Thread t,int d,int s){ ensure(); Object[] o=new Object[1]; JvmtiError.check(nGetLocalObject(t,d,s,o)); return o[0]; }
public static void forceReturn(Thread t){ ensure(); JvmtiError.check(nForceEarlyReturnVoid(t)); }
public static void forceReturnInt(Thread t,int v){...} public static void forceReturnObject(Thread t,Object v){...}
```
Verified-fact honesty (encode as behavior + javadoc, don't pretend):
 - NO nSetLocalObject / nSetLocalFloat/Long/Double (only SetLocalInt verified) → write_local is int-slot only.
 - NO SetFieldAccessWatch/ClearFieldModificationWatch in the verified function list → nClearFieldModificationWatch is written in C guarded by a runtime NULL-check on the function-table slot; if the JBR build lacks it, it returns JVMTI_ERROR_NOT_AVAILABLE cleanly (never crash). watch_field covers MODIFICATION only.
 - ForceEarlyReturn is Void/Int/Object only (verified) — force_return "kind" arg accepts void|int|object.

NATIVE-EVENT INBOUND (native → Java), cached in JNI_OnLoad, invoked from the JVMTI callback thread — MUST be cheap:
```
// package-private, called from C via cached jmethodID
static void onDebugEvent(int kind, Thread thread, String location, long numeric) {
    DebugEventQueue.INSTANCE.offer(DebugEvent.of(kind, thread, location, numeric));
}
```
kind ints: 1=BREAKPOINT, 2=SINGLE_STEP, 3=FIELD_MODIFICATION (constants also in DebugEvent).

=== (B) DebugEvent + DebugEventQueue + DebugEventListener ===
`public final class DebugEvent extends net.marcloud.mcp.core.event.GameEvent` (so it can also ride EventBus). Fields: `Kind kind` (enum BREAKPOINT/SINGLE_STEP/FIELD_MODIFICATION), `String threadName`, `long threadId`, `String location` (e.g. "net/minecraft/client/Minecraft.runTick()V@0" or "field owner#name"), `long numeric` (new int value for field-mod, or bci). Factory `of(int,Thread,String,long)`. Immutable, accessor methods matching SeamKeyEvent idiom.
`@FunctionalInterface public interface DebugEventListener { void onDebugEvent(DebugEvent e); }`
`public final class DebugEventQueue`:
 - singleton `INSTANCE` (created by McpCore, but also default so native callback never NPEs before wiring — lazy holder).
 - bounded `ArrayBlockingQueue<DebugEvent>` (cap 1024) for a retained recent buffer + `CopyOnWriteArrayList<DebugEventListener>`.
 - `offer(DebugEvent e)`: non-blocking; if full, drop-oldest (poll then offer) so a runaway single-step storm can't block the JVMTI callback thread (which may hold a raw monitor). Then dispatch to listeners inside try/catch-Throwable (same isolation discipline as EventBus.publish).
 - `void addListener(DebugEventListener l)`, `List<DebugEvent> snapshot()`, `void clear()`.
 - McpCore wires one listener that republishes onto the shared EventBus (`bus.publish(e)`), so existing subscribers see debug events without new plumbing.

=== (C) DebugTools — 9 R-1 MCP tools, mirrors SeamTools/HookTools exactly ===
`public final class DebugTools { private final AccessGate gate; public DebugTools(AccessGate gate){...} public void registerAll(CapabilityRegistry r){ for spec in all(): r.register(name,spec,null,desc,true, Ring.forBuiltin(name, Ring.R3)); } }`
Same private helpers as SeamTools: ok/err/arg/boolArg/schema/str/bool + `intArg`. Add:
```
private static Thread findThread(String name){ for(Thread t: Thread.getAllStackTraces().keySet()) if(t.getName().equals(name)) return t; return null; }
```
Every handler follows this shape (the fallback contract lives HERE so tools are never dead):
```
return new SyncToolSpecification(tool, (ex, req) -> {
    if (!DebuggerBridge.isAvailable()) return err(DebuggerBridge.unavailableReason());
    try {
        gate.require(CapabilitySid.CAP_DEBUG_CONTROL, Privilege.SE_DEBUG_CONTROL); // L4/L5 defense-in-depth
        ... resolve args / thread ...
        DebuggerBridge.<op>(...);
        return ok("<result>");
    } catch (DebuggerUnavailableException | DebuggerException | SecurityException e) {
        return err(e.getMessage());
    }
});
```
The nine tools + schemas:
 1. debug_suspend_thread {threadName:req, resume:bool=false} → suspend or (resume=true) ResumeThread.
 2. debug_pop_frame {threadName:req} → nPopFrame (doc: thread must be suspended; JVMTI needs can_pop_frame — verified present).
 3. debug_force_return {threadName:req, kind:enum void|int|object =void, intValue?, — object value not marshalable from JSON so object-kind forces null} → forceReturn*/.
 4. debug_set_breakpoint {className:req, method:req, signature:req, location:int=0}.
 5. debug_clear_breakpoint {className, method, signature, location=0}.
 6. debug_single_step {threadName:req, enabled:bool:req}.
 7. debug_read_local {threadName:req, depth:int=0, slot:int:req, type:enum object|int =int} → readLocalObject/readLocalInt; returns value stringified.
 8. debug_write_local {threadName:req, depth:int=0, slot:int:req, intValue:int:req} → setLocalInt (doc: int slots only, per verified SetLocalInt-only surface).
 9. debug_watch_field {className:req, field:req, signature:req, enabled:bool:req} → set/clear field-modification watch.
Class resolution in handlers: `Class.forName(className,false,getClass().getClassLoader())` (never force-load), and refuse ProtectedClasses on set_breakpoint/watch_field targets for parity with redefine (message: "refusing to instrument protected Core class …").

=== (D) The four gate tables + isReserved (the "keystone" wiring) ===
Debug tool names constant list: debug_suspend_thread, debug_pop_frame, debug_force_return, debug_set_breakpoint, debug_clear_breakpoint, debug_single_step, debug_read_local, debug_write_local, debug_watch_field.
 1. security/Ring.java BUILTIN_RINGS: add all 9 → R_MINUS_1 (they pause/rewrite live thread state; strictly hypervisor).
 2. security/CapabilityCatalog.java REQUIRED: add all 9 → Set.of(CapabilitySid.CAP_DEBUG_CONTROL) (SID already exists, tagged C6).
 3. security/ToolPolicy.java: L3_WRITES add all 9 → IntegrityLevel.SYSTEM (native thread control is the most invasive write class; matches open_module/eval tier). L4_PRIVILEGE add all 9 → Privilege.SE_DEBUG_CONTROL (already exists).
 4. registry/MetaTools.java isReserved(): add the 9 names to the switch (core surface, not AI-overwritable).
CapabilitySid.CAP_DEBUG_CONTROL and Privilege.SE_DEBUG_CONTROL already present — NO enum edits needed.

=== (E) McpCore wiring (after the C8 SEAM block, before socketServer) ===
```
// C6 DEBUG: native JVMTI thread/frame/breakpoint control. Graceful no-op
// without -agentpath:core-jvmti.dll — tools still register and report honestly.
DebugEventQueue debugEvents = DebugEventQueue.INSTANCE;
debugEvents.addListener(bus::publish);                 // debug events also flow on the EventBus
new DebugTools(gate).registerAll(registry);            // reuse the same AllowAllGate 'gate' as C3/C5
if (!DebuggerBridge.isAvailable())
    System.err.println("[MCP Core] JVMTI debugger absent — " + DebuggerBridge.unavailableReason()
        + " (debug_* tools registered, return isError until the agent is present).");
```
No stop() change required (JVMTI agent lifetime == JVM).

=== (F) MSVC native build chain (core/src/main/native/core-jvmti/) ===
Files: core-jvmti.c, jvmti_error_names.h (optional), build.bat, CMakeLists.txt. Output build/core-jvmti.dll.
core-jvmti.c structure:
 - globals: `static JavaVM* g_vm; static jvmtiEnv* g_jvmti; static volatile int g_agentReady=0; static jmethodID g_onDebugEvent; static jclass g_bridgeCls;`
 - `JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* opts, void* rsv)`: store g_vm; `(*vm)->GetEnv(vm,(void**)&g_jvmti,JVMTI_VERSION_11)`; build `jvmtiCapabilities` with ONLY the verified onload caps set to 1 (can_suspend, can_pop_frame, can_force_early_return, can_access_local_variables, can_generate_breakpoint_events, can_generate_single_step_events, can_generate_field_modification_events, can_generate_field_access_events, can_tag_objects); `AddCapabilities`; `SetEventCallbacks` for Breakpoint/SingleStep/FieldModification; global `SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT/FIELD_MODIFICATION, NULL)` (SINGLE_STEP left per-thread, enabled by nSetSingleStep); on success g_agentReady=1; return JNI_OK. If AddCapabilities fails, leave g_agentReady=0 and STILL return JNI_OK (so the JVM boots; Java sees not-available).
 - `JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* rsv)`: get JNIEnv; FindClass "net/marcloud/mcp/core/debug/DebuggerBridge"; g_bridgeCls=NewGlobalRef; cache g_onDebugEvent = GetStaticMethodID(cls,"onDebugEvent","(ILjava/lang/Thread;Ljava/lang/String;J)V"); RegisterNatives with the JNINativeMethod table for the 16 n* methods; return JNI_VERSION_10.
 - native impls: each translates args, resolves methodID/fieldID from Class+name+sig via GetMethodID/GetFieldID (jclass from the passed jclass), calls the g_jvmti function, returns the jvmtiError as jint. nGetLocal* write out[0] via SetObjectArrayElement / SetIntArrayRegion. nAgentReady returns g_agentReady. nClearFieldModificationWatch: if that function pointer path is unavailable in this build, return JVMTI_ERROR_NOT_AVAILABLE (defensive).
 - event callbacks (Breakpoint/SingleStep/FieldModification): GetMethodName/GetMethodDeclaringClass+GetClassSignature to build the location string, then `(*jni)->CallStaticVoidMethod(jni, g_bridgeCls, g_onDebugEvent, kind, thread, jlocStr, numeric)`; keep allocation minimal; never call back into g_jvmti heavy ops here.
build.bat (Git-Bash/cmd-safe, no jvm.lib needed):
```
@echo off
set JBR=..\..\..\..\..\_tools\jbrsdk-25.0.3-windows-x64-b508.16\include
if not exist build mkdir build
cl /nologo /LD /O2 /MD /I "%JBR%" /I "%JBR%\win32" core-jvmti.c /Fe:build\core-jvmti.dll /Fo:build\ /link /IMPLIB:build\core-jvmti.lib
```
CMakeLists.txt:
```
cmake_minimum_required(VERSION 3.20)
project(core_jvmti C)
set(JBR_INCLUDE "${CMAKE_SOURCE_DIR}/../../../../../_tools/jbrsdk-25.0.3-windows-x64-b508.16/include")
add_library(core-jvmti SHARED core-jvmti.c)
target_include_directories(core-jvmti PRIVATE "${JBR_INCLUDE}" "${JBR_INCLUDE}/win32")
set_target_properties(core-jvmti PROPERTIES OUTPUT_NAME "core-jvmti" PREFIX "")
# MSVC: JNIEXPORT already carries __declspec(dllexport); no jvm.lib link needed.
```

=== (G) jvm-args-mcp.txt additions (append) ===
```
# --- C6 JVMTI native debugger: onload-only caps REQUIRE -agentpath (dynamic attach cannot gain them). ---
-agentpath:core/src/main/native/core-jvmti/build/core-jvmti.dll
# Same file, absolute-resolvable, so DebuggerBridge can System.load it for JNI binding.
-Dmcp.core.jvmtiLib=core/src/main/native/core-jvmti/build/core-jvmti.dll
```
(Coexists with -javaagent:core-…-all.jar ByteBuddy agent and DCEVM flags already present. No JDWP.)

NEW FILES:
core/src/main/java/net/marcloud/mcp/core/debug/DebuggerBridge.java
core/src/main/java/net/marcloud/mcp/core/debug/DebuggerException.java
core/src/main/java/net/marcloud/mcp/core/debug/DebuggerUnavailableException.java
core/src/main/java/net/marcloud/mcp/core/debug/JvmtiError.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugEvent.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugEventQueue.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugEventListener.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugTools.java
core/src/main/native/core-jvmti/core-jvmti.c
core/src/main/native/core-jvmti/build.bat
core/src/main/native/core-jvmti/CMakeLists.txt
core/src/test/java/DebuggerBridgeFallbackTest.java
core/src/test/java/DebugToolsTest.java
core/src/test/java/DebugEventQueueTest.java

TOUCH:
core/src/main/java/net/marcloud/mcp/core/McpCore.java
core/src/main/java/net/marcloud/mcp/core/security/Ring.java
core/src/main/java/net/marcloud/mcp/core/security/CapabilityCatalog.java
core/src/main/java/net/marcloud/mcp/core/security/ToolPolicy.java
core/src/main/java/net/marcloud/mcp/core/registry/MetaTools.java
core/src/main/java/net/marcloud/mcp/core/security/ProtectedClasses.java
jvm-args-mcp.txt

FALLBACK:
Degradation is the default state (MSVC not installed yet). Layered so a missing/broken DLL is always a clean domain error, never a crash: (1) DebuggerBridge static init wraps System.load + nAgentReady() in catch(Throwable) — a missing DLL, missing export, or missing agent all resolve to AVAILABLE=false + a fixed reason string; UnsatisfiedLinkError is caught and never escapes the class. (2) Every public wrapper calls ensure() first, so no native symbol is dereferenced when unavailable — the JVM never even attempts to link the n* methods at call time. (3) Every DebugTools handler checks isAvailable() before touching the bridge and returns err(unavailableReason()); the 9 tools are always registered, always callable, and honestly report isError — no dead tools, no NPE, no ULE. (4) Native side is defensive too: if Agent_OnLoad's AddCapabilities fails it still returns JNI_OK (JVM boots) and leaves g_agentReady=0 so Java sees not-available; nClearFieldModificationWatch returns JVMTI_ERROR_NOT_AVAILABLE if the JBR build lacks the function rather than crashing. (5) DebugEventQueue.INSTANCE exists even before McpCore wiring (lazy holder) so a stray native callback can never NPE. When MSVC lands: run build.bat, the two jvm-args lines activate the DLL, isAvailable() flips to true with zero Java code change, and the same tools begin doing real work.

TESTS:
All primary tests run HEADLESS with NO DLL present (the fallback path), which is exactly what CI has today (MSVC not installed):
1. DebuggerBridgeFallbackTest: DebuggerBridge.isAvailable()==false; unavailableReason() contains "-agentpath:core-jvmti.dll"; assert that calling a public wrapper (e.g. suspendThread(Thread.currentThread())) throws DebuggerUnavailableException and NEVER UnsatisfiedLinkError (assert the exception type). This is the load-bearing safety proof.
2. DebugToolsTest: construct DebugTools(new AllowAllGate()), register into a CapabilityRegistry (mirror SupervisedGateIntegrationTest / DeepAccessTest setup); assert all 9 tools register (registry.get(name)!=null) and are NOT dead; invoke each via the registry and assert result.isError()==true with text == DebuggerBridge.unavailableReason(). Proves "tools still register + return honest isError (no dead tools)".
3. Gate-table tests (extend SecurityKernelTest pattern): for each of the 9 names assert Ring.forBuiltin==R_MINUS_1, ToolPolicy.forTool(name,true).requiredCaps() contains CAP_DEBUG_CONTROL, requiredPrivilege()==SE_DEBUG_CONTROL, writesResourceAt()==SYSTEM; assert MetaTools reserved-name guard rejects create_tool for each (self-lobotomy invariant). Assert ProtectedClasses.isProtected("net.marcloud.mcp.core.debug.DebuggerBridge")==true.
4. DebugEventQueueTest: pure-Java, no native — offer() past capacity drops-oldest without blocking; a throwing listener is isolated (queue keeps working, EventBus-style); addListener delivery order; snapshot()/clear().
5. Manual/opt-in native smoke test (documented, gated on MSVC + JBR present, excluded from CI via a surefire system-property guard `-Dmcp.core.nativeDebug=true`): run build.bat, launch a tiny main with @jvm-args-mcp.txt, set a breakpoint on a known method, trigger it, assert a BREAKPOINT DebugEvent lands on the queue. This is the only path that needs the DLL and stays out of the headless suite.
Framework: JUnit 4 (import org.junit.Test/Before, org.junit.Assert.*) to match every existing test in core/src/test/java. Run `mvn -pl core test` after implementation; no new deps.

RISKS:
-agentpath vs JNI binding chicken-and-egg: -agentpath loads the module for JVMTI but does NOT register it in the JNI native-library list, so DebuggerBridge.System.load()/loadLibrary() must load the SAME file again to bind the n* methods and fire JNI_OnLoad. On Windows LoadLibrary refcounts the same module, so this is safe, but the path in -Dmcp.core.jvmtiLib MUST resolve to the identical DLL as -agentpath or two module instances could exist. Mitigation: document that both flags point at the same absolute path; prefer -Dmcp.core.jvmtiLib over java.library.path to remove ambiguity.
JVMTI event callbacks run on arbitrary threads possibly holding raw monitors; doing anything heavy (or re-entering JVMTI) from onDebugEvent risks deadlock. Mitigation: onDebugEvent only offers to a non-blocking drop-oldest queue and dispatches to listeners in try/catch — no JVMTI re-entry, no blocking.
PopFrame / ForceEarlyReturn require the target thread to be suspended and at a safe point; misuse can leave the game thread wedged. These are R-1 + SE_DEBUG_CONTROL gated, but the tool descriptions must warn and ideally the handler should verify suspension state (or document that debug_suspend_thread must precede pop/force).
Setting a breakpoint or single-step on the render/game thread can freeze the client; ProtectedClasses guard blocks Core classes but net.minecraft.* is intentionally allowed and can still hang the game. Honest doc + R-1 gate is the only guard (parity with redefine_class).
JVMTI_VERSION constant: code targets 11/whatever the b508.16 header exposes — build must include the JBR header, not a system JDK header, or capability struct layout mismatches. build.bat/CMake pin the _tools JBR include explicitly.
write_local is int-only and there is no field-access watch in the verified surface; if a consumer expects object-local writes or read-watch they will get JVMTI_ERROR_NOT_AVAILABLE — must be documented in tool descriptions to avoid a silent-wrong-behavior surprise.