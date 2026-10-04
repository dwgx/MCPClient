---
doc: brief-c6-native
title: C6 native JVMTI agent (core-jvmti.dll) — Agent_OnLoad + JNI bridge + event dispatch
layer: archive
status: archived
updated: 2026-07-11
parent: ../README.md
next:
  - path: ../architecture/03-NATIVE-C6-AND-BUILD.md
    when: 需要 C6 native/构建的当前权威状态(clang 构建链)
read_if: 历史设计 brief;仅当你在改 C6 native JVMTI agent 且想看原始设计意图时才读(构建部分已偏离,现用 LLVM/clang)。
---
> **归档设计 brief** — 实现已完成并偏离本 brief 的构建部分:C6 DLL 现用 **LLVM/clang**(`build-clang.sh`,`winget install LLVM.LLVM`,无需 MSVC/Windows SDK)编译,而非本文假设的 MSVC。JNI_OnLoad 绑定修正让 natives 在 app 类加载器上下文绑定。已 live 验证。当前状态见 `../architecture/03-NATIVE-C6-AND-BUILD.md`。设计意图仍有参考价值,故原样保留。

AREA: C6 native JVMTI agent (core-jvmti.dll) — Agent_OnLoad + JNI bridge + event dispatch

DESIGN:
CITED SIGNATURES (all from _tools/jbrsdk-25.0.3-windows-x64-b508.16/include/):
jvmti.h — Agent_OnLoad(JavaVM*,char*,void*) @52; Agent_OnUnload(JavaVM*) @58; JVMTI_VERSION_1_2=0x30010200 @43; jvmtiCapabilities bitfield struct @674 (can_tag_objects @675, can_generate_field_modification_events @676, can_generate_field_access_events @677, can_pop_frame @683, can_access_local_variables @689, can_generate_single_step_events @691, can_generate_breakpoint_events @694, can_suspend @695, can_force_early_return @708); AddCapabilities(env,const jvmtiCapabilities*) @1782; SetEventCallbacks(env,const jvmtiEventCallbacks*,jint size) @1695; SetEventNotificationMode(env,jvmtiEventMode,jvmtiEvent,jthread,...) @1048, JVMTI_ENABLE=1/JVMTI_DISABLE=0 @233-234, JVMTI_EVENT_VM_INIT=50 @401, JVMTI_EVENT_SINGLE_STEP=60 @411, JVMTI_EVENT_BREAKPOINT=62 @413, JVMTI_EVENT_FIELD_MODIFICATION=64 @415; SuspendThread(env,jthread) @1065; ResumeThread(env,jthread) @1069; GetThreadInfo(env,jthread,jvmtiThreadInfo*) @1082 (struct @528: char* name; jint priority; jboolean is_daemon; jthreadGroup thread_group; jobject context_class_loader); GetLocalObject(env,jthread,jint depth,jint slot,jobject* value_ptr) @1149; GetLocalInt(env,jthread,jint,jint,jint*) @1156; SetLocalInt(env,jthread,jint,jint,jint value) @1191; SetBreakpoint(env,jmethodID,jlocation) @1249; ClearBreakpoint(env,jmethodID,jlocation) @1254; SetFieldModificationWatch(env,jclass,jfieldID) @1275; ClearFieldModificationWatch(env,jclass,jfieldID) @1280; Deallocate(env,unsigned char*) @1295; PopFrame(env,jthread) @1474; ForceEarlyReturnObject(env,jthread,jobject) @1478; ForceEarlyReturnInt(env,jthread,jint) @1483; ForceEarlyReturnVoid(env,jthread) @1503; GetErrorName(env,jvmtiError,char**) @1722. Event typedefs: jvmtiEventBreakpoint(jvmtiEnv*,JNIEnv*,jthread,jmethodID,jlocation) @734; jvmtiEventFieldModification(jvmtiEnv*,JNIEnv*,jthread,jmethodID,jlocation,jclass field_klass,jobject object,jfieldID field,char signature_type,jvalue new_value) @816; jvmtiEventSingleStep(jvmtiEnv*,JNIEnv*,jthread,jmethodID,jlocation) @908; jvmtiEventVMInit(jvmtiEnv*,JNIEnv*,jthread) @939; jvmtiEventCallbacks struct @958 (fields .VMInit @960, .SingleStep @980, .Breakpoint @984, .FieldModification @988).
jni.h — JNINativeMethod{char* name; char* signature; void* fnPtr;} @182; RegisterNatives(JNIEnv*,jclass,const JNINativeMethod*,jint nMethods) @722; JavaVM GetEnv(void** penv,jint version) @1948. Note: jmethodID/jfieldID are opaque pointers; marshal to Java as jlong via (jlong)(intptr_t). jlocation is already jlong. jthread is a jobject (java.lang.Thread) — pass Thread objects straight through both directions.

=== FILE LAYOUT under core/src/main/native/core-jvmti/ ===
core-jvmti.h  — global-state struct decl, error-code macros, forward decls of the 3 callbacks, VMInit, and every nXxx bridge fn (all JNICALL). One include of <jvmti.h> (pulls jni.h). Guard #ifndef CORE_JVMTI_H.
core-jvmti.c  — the whole implementation below.
build.md      — cl.exe command + Maven wiring (informational).

=== GLOBAL STATE (core-jvmti.c top) ===
static JavaVM*    g_vm      = NULL;
static jvmtiEnv*  g_jvmti   = NULL;
static jclass     g_events  = NULL;   /* global ref to JvmtiEvents */
static jmethodID  g_onBreakpoint = NULL, g_onSingleStep = NULL, g_onFieldMod = NULL;
Helper: static jint check(jvmtiError e){ if(e!=JVMTI_ERROR_NONE){ char* n=NULL; if(g_jvmti){(*g_jvmti)->GetErrorName(g_jvmti,e,&n); fprintf(stderr,"[core-jvmti] err %d %s\n",e,n?n:"?"); if(n)(*g_jvmti)->Deallocate(g_jvmti,(unsigned char*)n);} } return (jint)e; }  // returns jvmtiError as jint; 0==NONE. Every nXxx returns this jint to Java.

=== (1) Agent_OnLoad ===
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved){
  g_vm = vm;
  jvmtiEnv* jvmti = NULL;
  if ((*vm)->GetEnv(vm,(void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK || jvmti==NULL) return JNI_ERR;  // GetEnv @1948
  g_jvmti = jvmti;
  jvmtiCapabilities caps; memset(&caps,0,sizeof(caps));
  caps.can_tag_objects=1; caps.can_generate_field_modification_events=1; caps.can_generate_field_access_events=1;
  caps.can_pop_frame=1; caps.can_access_local_variables=1; caps.can_generate_single_step_events=1;
  caps.can_generate_breakpoint_events=1; caps.can_suspend=1; caps.can_force_early_return=1;  // the 9 onload caps
  if ((*jvmti)->AddCapabilities(jvmti,&caps) != JVMTI_ERROR_NONE) return JNI_ERR;  // @1782
  jvmtiEventCallbacks cb; memset(&cb,0,sizeof(cb));
  cb.VMInit           = &vmInit;             // @960
  cb.Breakpoint       = &onBreakpoint;       // @984
  cb.SingleStep       = &onSingleStep;       // @980
  cb.FieldModification= &onFieldModification;// @988
  if ((*jvmti)->SetEventCallbacks(jvmti,&cb,(jint)sizeof(cb)) != JVMTI_ERROR_NONE) return JNI_ERR;  // @1695
  (*jvmti)->SetEventNotificationMode(jvmti,JVMTI_ENABLE,JVMTI_EVENT_VM_INIT,NULL);  // enable VM_INIT globally @1048
  return JNI_OK;
}
JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm){ /* nothing to free that outlives VM; leave g_events for JVM teardown */ }
Rationale: the 9 caps MUST be added in OnLoad — they are onload-only and dynamic attach cannot gain them (iron rule). Breakpoint/SingleStep/FieldModification notification modes are NOT enabled here; they are toggled on demand inside nSetBreakpoint/nSingleStep/nWatchFieldModification so the JVM pays zero event cost until a tool asks.

=== VMInit — RegisterNatives (NOT System.loadLibrary) ===
static void JNICALL vmInit(jvmtiEnv* jvmti, JNIEnv* env, jthread thread){
  jclass natives = (*env)->FindClass(env,"net/marcloud/mcp/core/debug/JvmtiNative");
  if(natives==NULL){ (*env)->ExceptionClear(env); fprintf(stderr,"[core-jvmti] JvmtiNative not found\n"); return; }
  static const JNINativeMethod M[] = {
    {"nSuspendThread",        "(Ljava/lang/Thread;)I",                        (void*)&nSuspendThread},
    {"nResumeThread",         "(Ljava/lang/Thread;)I",                        (void*)&nResumeThread},
    {"nPopFrame",             "(Ljava/lang/Thread;)I",                        (void*)&nPopFrame},
    {"nForceEarlyReturnVoid", "(Ljava/lang/Thread;)I",                        (void*)&nForceEarlyReturnVoid},
    {"nForceEarlyReturnInt",  "(Ljava/lang/Thread;I)I",                       (void*)&nForceEarlyReturnInt},
    {"nForceEarlyReturnObject","(Ljava/lang/Thread;Ljava/lang/Object;)I",     (void*)&nForceEarlyReturnObject},
    {"nSetBreakpoint",        "(Ljava/lang/Class;JJ)I",                        (void*)&nSetBreakpoint},
    {"nClearBreakpoint",      "(Ljava/lang/Class;JJ)I",                        (void*)&nClearBreakpoint},
    {"nSingleStep",           "(Ljava/lang/Thread;Z)I",                        (void*)&nSingleStep},
    {"nGetLocalObject",       "(Ljava/lang/Thread;II)Ljava/lang/Object;",      (void*)&nGetLocalObject},
    {"nGetLocalInt",          "(Ljava/lang/Thread;II)J",                       (void*)&nGetLocalInt},
    {"nSetLocalInt",          "(Ljava/lang/Thread;III)I",                      (void*)&nSetLocalInt},
    {"nWatchFieldModification","(Ljava/lang/Class;J)I",                        (void*)&nWatchFieldModification},
    {"nResolveMethodId",      "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z)J", (void*)&nResolveMethodId},
    {"nResolveFieldId",       "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z)J", (void*)&nResolveFieldId},
  };
  if((*env)->RegisterNatives(env,natives,M,(jint)(sizeof(M)/sizeof(M[0]))) != 0){ (*env)->ExceptionClear(env); fprintf(stderr,"[core-jvmti] RegisterNatives failed\n"); return; }  // @722
  jclass ev = (*env)->FindClass(env,"net/marcloud/mcp/core/debug/JvmtiEvents");
  if(ev==NULL){ (*env)->ExceptionClear(env); return; }
  g_events = (*env)->NewGlobalRef(env,ev);
  g_onBreakpoint = (*env)->GetStaticMethodID(env,ev,"onBreakpoint","(Ljava/lang/Thread;JJ)V");
  g_onSingleStep = (*env)->GetStaticMethodID(env,ev,"onSingleStep","(Ljava/lang/Thread;JJ)V");
  g_onFieldMod   = (*env)->GetStaticMethodID(env,ev,"onFieldModification","(Ljava/lang/Thread;JJLjava/lang/Class;Ljava/lang/Object;JCJ)V");
}
Note: nResolveMethodId/nResolveFieldId are the required companions — Java cannot mint a jmethodID/jfieldID, so these wrap JNI GetMethodID/GetStaticMethodID and GetFieldID/GetStaticFieldID (returning (jlong)(intptr_t)id, 0 on failure) so that nSetBreakpoint/nWatchFieldModification receive real IDs. Last boolean = isStatic.

=== (2) JNI-bridge exports (each = one jvmtiEnv call). All signatures (JNIEnv* env, jclass cls, ...) because the Java methods are static. jthread param IS the Thread jobject; long method/field marshalled via (jXxxID)(intptr_t). ===
static jint JNICALL nSuspendThread(JNIEnv* e,jclass c,jobject t){ return check((*g_jvmti)->SuspendThread(g_jvmti,(jthread)t)); }              // @1065
static jint JNICALL nResumeThread (JNIEnv* e,jclass c,jobject t){ return check((*g_jvmti)->ResumeThread (g_jvmti,(jthread)t)); }              // @1069
static jint JNICALL nPopFrame     (JNIEnv* e,jclass c,jobject t){ return check((*g_jvmti)->PopFrame     (g_jvmti,(jthread)t)); }              // @1474
static jint JNICALL nForceEarlyReturnVoid(JNIEnv* e,jclass c,jobject t){ return check((*g_jvmti)->ForceEarlyReturnVoid(g_jvmti,(jthread)t)); } // @1503
static jint JNICALL nForceEarlyReturnInt (JNIEnv* e,jclass c,jobject t,jint v){ return check((*g_jvmti)->ForceEarlyReturnInt(g_jvmti,(jthread)t,v)); } // @1483
static jint JNICALL nForceEarlyReturnObject(JNIEnv* e,jclass c,jobject t,jobject v){ return check((*g_jvmti)->ForceEarlyReturnObject(g_jvmti,(jthread)t,v)); } // @1478
static jint JNICALL nSetBreakpoint(JNIEnv* e,jclass c,jclass k,jlong mid,jlong loc){ jint r=check((*g_jvmti)->SetBreakpoint(g_jvmti,(jmethodID)(intptr_t)mid,(jlocation)loc)); if(r==0)(*g_jvmti)->SetEventNotificationMode(g_jvmti,JVMTI_ENABLE,JVMTI_EVENT_BREAKPOINT,NULL); return r; }  // @1249
static jint JNICALL nClearBreakpoint(JNIEnv* e,jclass c,jclass k,jlong mid,jlong loc){ return check((*g_jvmti)->ClearBreakpoint(g_jvmti,(jmethodID)(intptr_t)mid,(jlocation)loc)); } // @1254
static jint JNICALL nSingleStep(JNIEnv* e,jclass c,jobject t,jboolean on){ return check((*g_jvmti)->SetEventNotificationMode(g_jvmti,on?JVMTI_ENABLE:JVMTI_DISABLE,JVMTI_EVENT_SINGLE_STEP,(jthread)t)); }  // per-thread @1048/@411
static jobject JNICALL nGetLocalObject(JNIEnv* e,jclass c,jobject t,jint depth,jint slot){ jobject v=NULL; if(check((*g_jvmti)->GetLocalObject(g_jvmti,(jthread)t,depth,slot,&v))!=0) return NULL; return v; }  // @1149
static jlong JNICALL nGetLocalInt(JNIEnv* e,jclass c,jobject t,jint depth,jint slot){ jint v=0; jvmtiError r=(*g_jvmti)->GetLocalInt(g_jvmti,(jthread)t,depth,slot,&v); if(r!=JVMTI_ERROR_NONE){ check(r); return ((jlong)r)<<32 | 0xFFFFFFFFL; } return (jlong)(jint)v & 0xFFFFFFFFL; }  // @1156 — pack: hi32=error, lo32=value; Java unpacks
static jint JNICALL nSetLocalInt(JNIEnv* e,jclass c,jobject t,jint depth,jint slot,jint v){ return check((*g_jvmti)->SetLocalInt(g_jvmti,(jthread)t,depth,slot,v)); }  // @1191
static jint JNICALL nWatchFieldModification(JNIEnv* e,jclass c,jclass k,jlong fid){ jint r=check((*g_jvmti)->SetFieldModificationWatch(g_jvmti,k,(jfieldID)(intptr_t)fid)); if(r==0)(*g_jvmti)->SetEventNotificationMode(g_jvmti,JVMTI_ENABLE,JVMTI_EVENT_FIELD_MODIFICATION,NULL); return r; }  // @1275
static jlong JNICALL nResolveMethodId(JNIEnv* e,jclass c,jclass k,jstring name,jstring sig,jboolean isStatic){ const char* n=(*e)->GetStringUTFChars(e,name,0); const char* s=(*e)->GetStringUTFChars(e,sig,0); jmethodID m = isStatic ? (*e)->GetStaticMethodID(e,k,n,s) : (*e)->GetMethodID(e,k,n,s); (*e)->ReleaseStringUTFChars(e,name,n); (*e)->ReleaseStringUTFChars(e,sig,s); if((*e)->ExceptionCheck(e)){(*e)->ExceptionClear(e); return 0;} return (jlong)(intptr_t)m; }
static jlong JNICALL nResolveFieldId (JNIEnv* e,jclass c,jclass k,jstring name,jstring sig,jboolean isStatic){ /* symmetric with GetStaticFieldID/GetFieldID */ ... return (jlong)(intptr_t)f; }
Design note on nGetLocalInt packing: GetLocalInt has no natural sentinel, so we return jlong with hi32=jvmtiError, lo32=value; JvmtiNative.getLocalInt() checks hi32==0. nGetLocalObject uses NULL-on-error (Java rechecks via a separate nGetLastError if it must distinguish null value from failure — optional; documented).

=== (3) Event callbacks → CallStaticVoidMethod ===
The JVMTI callback already hands us a live JNIEnv* on the event thread — NO AttachCurrentThread needed. jmethodID/jfieldID → (jlong)(intptr_t); jlocation is jlong; jthread forwarded as the Thread jobject.
static void JNICALL onBreakpoint(jvmtiEnv* j,JNIEnv* e,jthread t,jmethodID m,jlocation loc){ if(g_events&&g_onBreakpoint) (*e)->CallStaticVoidMethod(e,g_events,g_onBreakpoint,(jobject)t,(jlong)(intptr_t)m,(jlong)loc); if((*e)->ExceptionCheck(e))(*e)->ExceptionClear(e); }
static void JNICALL onSingleStep(jvmtiEnv* j,JNIEnv* e,jthread t,jmethodID m,jlocation loc){ if(g_events&&g_onSingleStep) (*e)->CallStaticVoidMethod(e,g_events,g_onSingleStep,(jobject)t,(jlong)(intptr_t)m,(jlong)loc); if((*e)->ExceptionCheck(e))(*e)->ExceptionClear(e); }
static void JNICALL onFieldModification(jvmtiEnv* j,JNIEnv* e,jthread t,jmethodID m,jlocation loc,jclass fk,jobject obj,jfieldID f,char sigType,jvalue nv){ if(g_events&&g_onFieldMod){ jlong newBits; switch(sigType){ case 'J': newBits=nv.j; break; case 'I': newBits=nv.i; break; case 'Z': newBits=nv.z; break; case 'F': newBits=(jlong)nv.i; break; /* raw */ default: newBits=0; } (*e)->CallStaticVoidMethod(e,g_events,g_onFieldMod,(jobject)t,(jlong)(intptr_t)m,(jlong)loc,fk,obj,(jlong)(intptr_t)f,(jchar)sigType,newBits); } if((*e)->ExceptionCheck(e))(*e)->ExceptionClear(e); }
Thread-id representation: we forward the actual java.lang.Thread object (jthread==jobject), so the Java dispatcher can Thread.threadId()/getName() itself — no GetThreadInfo marshalling in C. (GetThreadInfo @1082 is available if a C-side name is ever wanted; not used on the hot path.) ExceptionCheck+Clear after every upcall so a Java-side exception never propagates back into the JVMTI event and corrupts VM state (the DepthARK gate discipline: never let the payload destabilize the monitor).

=== JAVA SIDE (feeds the lead; new package net.marcloud.mcp.core.debug) ===
JvmtiNative.java — the RegisterNatives target. Fields: private static volatile boolean READY=false. static native int nSuspendThread(Thread t); ...(all 15). public static boolean nativeReady(){return READY;} A CoreBootstrap/McpCore probe calls a cheap sentinel (e.g. try nResolveMethodId(Object.class,"toString","()Ljava/lang/String;",false); READY = result!=0) inside try/catch(UnsatisfiedLinkError|Throwable){READY=false;}. When -agentpath is absent the methods are never bound → UnsatisfiedLinkError on first call → caught → READY stays false. GRACEFUL FALLBACK: all C6 MCP tools check JvmtiNative.nativeReady() first and return err("native JVMTI agent not loaded; start JVM with -agentpath:core-jvmti.dll") exactly like SeamTools returns "start with -javaagent". Build and every non-C6 path run unchanged with no DLL.
JvmtiEvents.java — static void onBreakpoint(Thread,long methodId,long location); onSingleStep(...); onFieldModification(Thread,long methodId,long location,Class<?> fieldKlass,Object target,long fieldId,char sigType,long newValueBits). Each translates the raw marshalled longs into a typed event and publishes on the existing EventBus (mirror seam/events/*). Keep bodies cheap — they run on the suspended/event thread.
DebugController.java + DebugTools.java — mirror SeamController/SeamTools exactly: DebugTools.registerAll(CapabilityRegistry) registers debug_suspend_thread, debug_resume_thread, debug_pop_frame, debug_force_return, debug_set_breakpoint, debug_clear_breakpoint, debug_single_step, debug_get_local, debug_set_local_int, debug_watch_field — each registry.register(name,spec,null,desc,true,Ring.R_MINUS_1). Handlers resolve a Thread via ThreadRegistry/state layer, call JvmtiNative, map jint!=0 → err(GetErrorName text).

=== SECURITY-SPINE WIRING (already present, verify only) ===
CapabilitySid.CAP_DEBUG_CONTROL [C6] and Privilege.SE_DEBUG_CONTROL EXIST (security/CapabilitySid.java:29, security/Privilege.java:24) — reuse, do not add. LEAD MUST STILL: (a) add every debug_* tool name to Ring.BUILTIN_RINGS at R_MINUS_1; (b) map each to {CAP_DEBUG_CONTROL} in CapabilityCatalog and to SE_DEBUG_CONTROL in ToolPolicy; (c) add debug_* to registry/MetaTools isReserved; (d) add net.marcloud.mcp.core.debug.JvmtiNative + JvmtiEvents to security/ProtectedClasses so the natives/dispatcher can't be retransformed out from under the guard (same reason CoreAgent is protected). Every debug_* call still flows CapabilityRegistry.supervise() -> PolicyEngine.evaluate before touching the native layer — the 7-layer gate is unchanged.

NEW FILES:
core/src/main/native/core-jvmti/core-jvmti.h
core/src/main/native/core-jvmti/core-jvmti.c
core/src/main/native/core-jvmti/build.md
core/src/main/java/net/marcloud/mcp/core/debug/JvmtiNative.java
core/src/main/java/net/marcloud/mcp/core/debug/JvmtiEvents.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugController.java
core/src/main/java/net/marcloud/mcp/core/debug/DebugTools.java

TOUCH:
core/src/main/java/net/marcloud/mcp/core/security/Ring.java (add debug_* to BUILTIN_RINGS @R_MINUS_1)
core/src/main/java/net/marcloud/mcp/core/security/CapabilityCatalog.java (map debug_* -> CAP_DEBUG_CONTROL)
core/src/main/java/net/marcloud/mcp/core/security/ToolPolicy.java (map debug_* -> SE_DEBUG_CONTROL)
core/src/main/java/net/marcloud/mcp/core/registry/MetaTools.java (add debug_* to isReserved)
core/src/main/java/net/marcloud/mcp/core/security/ProtectedClasses.java (protect JvmtiNative + JvmtiEvents)
core/src/main/java/net/marcloud/mcp/core/McpCore.java (register DebugTools; probe JvmtiNative.nativeReady() at startup)
core/pom.xml (add guarded native-compile profile)

FALLBACK:
The DLL is optional. JvmtiNative.nativeReady() returns false whenever -agentpath:core-jvmti.dll was not passed (the RegisterNatives binding never happened, so the first native call throws UnsatisfiedLinkError, which the static probe catches and records as not-ready). Every debug_* MCP tool short-circuits on !nativeReady() and returns an err() telling the user to relaunch with -agentpath — identical UX to SeamTools' "start with -javaagent" message. No debug_* handler ever calls a native method without the ready guard, so absence of the DLL causes zero NoClassDefFound/link crashes. The entire rest of Core (C1/C3/C5/C7/C8, seams, hotload) is untouched and builds/runs with no native toolchain. Because caps are onload-only, dynamic attach is intentionally NOT offered as a fallback — the tools stay dark rather than pretend.

TESTS:
1) Java-only (CI, no DLL, no MSVC): unit test JvmtiNative.nativeReady()==false and that each DebugTools handler returns isError=true with the -agentpath message; assert debug_* are registered at R_MINUS_1, require CAP_DEBUG_CONTROL+SE_DEBUG_CONTROL, are in MetaTools.isReserved, and JvmtiNative/JvmtiEvents are ProtectedClasses.isProtected — this runs today and must stay green. 2) Header-conformance check: a tiny compile-only harness (once MSVC lands) that #includes core-jvmti.h against _tools/.../include to catch signature drift — no linking needed. 3) Native smoke (when cl.exe installed): build the DLL, launch the JBR with -agentpath:core-jvmti.dll, assert nativeReady()==true, set a breakpoint on a known method via nResolveMethodId+nSetBreakpoint and assert JvmtiEvents.onBreakpoint fires; suspend/resume a spawned thread and assert Thread state. 4) Manual: verify no JVMTI_ERROR on AddCapabilities at OnLoad (the 9-cap set is the gate). Clean up any temp harness artifacts.

RISKS:
MSVC cl.exe is not yet installed (user-locked toolchain) — the .c/.h must compile the moment it lands; mitigated by writing strictly to the cited jvmti.h/jni.h and a header-conformance harness. Build stays green via the guarded Maven profile.
jmethodID/jfieldID marshalled as jlong assumes pointer<=64-bit — true on windows-x64; document that this DLL is x64-only (matches the JBR build b508.16 x64).
SetBreakpoint/SingleStep have real perf and stability cost on the hot game loop; events are enabled on-demand and disabled by clear/step-off, but a careless breakpoint in runTick can wedge the render thread — gate at R_MINUS_1 + SE_DEBUG_CONTROL and document.
Single-step is per-thread (SetEventNotificationMode with jthread); forgetting to disable it floods JvmtiEvents.onSingleStep — nSingleStep(false) must be paired and DebugController should auto-disable on breakpoint hit.
CallStaticVoidMethod upcalls run on a suspended/event thread; a slow or throwing dispatcher can deadlock or destabilize the VM — bodies must be cheap and every callback does ExceptionCheck+Clear.
nGetLocalInt hi32/lo32 packing is a convention the Java side must decode exactly; a simpler alternative is an out-param via a 1-element int[] using JNI SetIntArrayRegion if the lead prefers explicitness.
GetLocalObject returns a local ref valid only for the callback/JNI frame — if JvmtiEvents needs to retain it, it must NewGlobalRef; document in JvmtiNative.getLocalObject Javadoc.