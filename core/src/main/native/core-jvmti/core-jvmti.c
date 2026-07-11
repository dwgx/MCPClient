/*
 * core-jvmti.c — MCPClient C6 native JVMTI debugger agent (windows-x64).
 * Binds onto net.marcloud.mcp.core.kd.KdBridge. See core-jvmti.h.
 *
 * All jvmti.h/jni.h signatures verified against
 * _tools/jbrsdk-25.0.3-windows-x64-b508.16/include.
 */
#include "core-jvmti.h"
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static JavaVM*   g_vm       = NULL;
static jvmtiEnv* g_jvmti    = NULL;
static volatile int g_ready = 0;      /* 1 iff OnLoad ran AND AddCapabilities succeeded */
static jclass    g_bridge   = NULL;   /* global ref to KdBridge */
static jmethodID g_onEvent  = NULL;   /* KdBridge.onDebugEvent(I,Thread,String,J)V */

/* Log a jvmtiError and return it as jint (0 == JVMTI_ERROR_NONE). */
static jint check(jvmtiError e) {
    if (e != JVMTI_ERROR_NONE && g_jvmti != NULL) {
        char* n = NULL;
        (*g_jvmti)->GetErrorName(g_jvmti, e, &n);
        fprintf(stderr, "[core-jvmti] jvmtiError %d %s\n", (int)e, n ? n : "?");
        if (n) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)n);
    }
    return (jint)e;
}

/* Build "pkg/Class.method<sig>@loc" (or "owner#field") into a Java string. */
static jstring locString(JNIEnv* e, jmethodID m, jlocation loc) {
    char buf[512];
    char* name = NULL; char* sig = NULL;
    if (g_jvmti && (*g_jvmti)->GetMethodName(g_jvmti, m, &name, &sig, NULL) == JVMTI_ERROR_NONE) {
        snprintf(buf, sizeof(buf), "%s%s@%lld",
                 name ? name : "?", sig ? sig : "", (long long)loc);
        if (name) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)name);
        if (sig)  (*g_jvmti)->Deallocate(g_jvmti, (unsigned char*)sig);
    } else {
        snprintf(buf, sizeof(buf), "<method>@%lld", (long long)loc);
    }
    return (*e)->NewStringUTF(e, buf);
}

/* ---- event callbacks: forward to KdBridge.onDebugEvent, cheaply ---- */

static void JNICALL onBreakpoint(jvmtiEnv* j, JNIEnv* e, jthread t, jmethodID m, jlocation loc) {
    if (g_bridge && g_onEvent) {
        jstring s = locString(e, m, loc);
        (*e)->CallStaticVoidMethod(e, g_bridge, g_onEvent,
                                   (jint)CORE_EVT_BREAKPOINT, (jobject)t, s, (jlong)loc);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
        if (s) (*e)->DeleteLocalRef(e, s);
    }
}

static void JNICALL onSingleStep(jvmtiEnv* j, JNIEnv* e, jthread t, jmethodID m, jlocation loc) {
    if (g_bridge && g_onEvent) {
        jstring s = locString(e, m, loc);
        (*e)->CallStaticVoidMethod(e, g_bridge, g_onEvent,
                                   (jint)CORE_EVT_SINGLE_STEP, (jobject)t, s, (jlong)loc);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
        if (s) (*e)->DeleteLocalRef(e, s);
    }
}

static void JNICALL onFieldModification(jvmtiEnv* j, JNIEnv* e, jthread t, jmethodID m,
        jlocation loc, jclass fk, jobject obj, jfieldID f, char sigType, jvalue nv) {
    if (g_bridge && g_onEvent) {
        jlong bits;
        switch (sigType) {
            case 'J': bits = nv.j; break;
            case 'I': bits = (jlong)nv.i; break;
            case 'Z': bits = (jlong)nv.z; break;
            case 'S': bits = (jlong)nv.s; break;
            case 'B': bits = (jlong)nv.b; break;
            case 'C': bits = (jlong)nv.c; break;
            default:  bits = 0; break; /* object/float/double: raw value not marshalled */
        }
        char fbuf[64];
        snprintf(fbuf, sizeof(fbuf), "field@%lld", (long long)loc);
        jstring s = (*e)->NewStringUTF(e, fbuf);
        (*e)->CallStaticVoidMethod(e, g_bridge, g_onEvent,
                                   (jint)CORE_EVT_FIELD_MODIFICATION, (jobject)t, s, bits);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
        if (s) (*e)->DeleteLocalRef(e, s);
    }
}

/* ---- native bridge functions (bound onto KdBridge via RegisterNatives) ----
 * Signatures match KdBridge's private static native declarations exactly.
 */

static jboolean JNICALL nAgentReady(JNIEnv* e, jclass c) {
    return g_ready ? JNI_TRUE : JNI_FALSE;
}

static jint JNICALL nSuspendThread(JNIEnv* e, jclass c, jobject t) {
    return check((*g_jvmti)->SuspendThread(g_jvmti, (jthread)t));
}

static jint JNICALL nResumeThread(JNIEnv* e, jclass c, jobject t) {
    return check((*g_jvmti)->ResumeThread(g_jvmti, (jthread)t));
}

static jint JNICALL nPopFrame(JNIEnv* e, jclass c, jobject t) {
    return check((*g_jvmti)->PopFrame(g_jvmti, (jthread)t));
}

static jint JNICALL nForceEarlyReturnVoid(JNIEnv* e, jclass c, jobject t) {
    return check((*g_jvmti)->ForceEarlyReturnVoid(g_jvmti, (jthread)t));
}

static jint JNICALL nForceEarlyReturnInt(JNIEnv* e, jclass c, jobject t, jint v) {
    return check((*g_jvmti)->ForceEarlyReturnInt(g_jvmti, (jthread)t, v));
}

static jint JNICALL nForceEarlyReturnObject(JNIEnv* e, jclass c, jobject t, jobject v) {
    return check((*g_jvmti)->ForceEarlyReturnObject(g_jvmti, (jthread)t, v));
}

/* Resolve a jmethodID from (class,name,sig); tries instance then static. */
static jmethodID resolveMethod(JNIEnv* e, jclass k, jstring name, jstring sig) {
    const char* n = (*e)->GetStringUTFChars(e, name, NULL);
    const char* s = (*e)->GetStringUTFChars(e, sig, NULL);
    jmethodID m = (*e)->GetMethodID(e, k, n, s);
    if ((*e)->ExceptionCheck(e)) {
        (*e)->ExceptionClear(e);
        m = (*e)->GetStaticMethodID(e, k, n, s);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
    }
    (*e)->ReleaseStringUTFChars(e, name, n);
    (*e)->ReleaseStringUTFChars(e, sig, s);
    return m;
}

static jint JNICALL nSetBreakpoint(JNIEnv* e, jclass c, jclass k, jstring method, jstring sig, jlong loc) {
    jmethodID m = resolveMethod(e, k, method, sig);
    if (m == NULL) return (jint)JVMTI_ERROR_INVALID_METHODID;
    jint r = check((*g_jvmti)->SetBreakpoint(g_jvmti, m, (jlocation)loc));
    if (r == 0) (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
    return r;
}

static jint JNICALL nClearBreakpoint(JNIEnv* e, jclass c, jclass k, jstring method, jstring sig, jlong loc) {
    jmethodID m = resolveMethod(e, k, method, sig);
    if (m == NULL) return (jint)JVMTI_ERROR_INVALID_METHODID;
    return check((*g_jvmti)->ClearBreakpoint(g_jvmti, m, (jlocation)loc));
}

static jint JNICALL nSetSingleStep(JNIEnv* e, jclass c, jobject t, jboolean on) {
    return check((*g_jvmti)->SetEventNotificationMode(g_jvmti,
            on ? JVMTI_ENABLE : JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, (jthread)t));
}

static jint JNICALL nGetLocalObject(JNIEnv* e, jclass c, jobject t, jint depth, jint slot, jobjectArray out) {
    jobject v = NULL;
    jint r = check((*g_jvmti)->GetLocalObject(g_jvmti, (jthread)t, depth, slot, &v));
    if (r == 0 && out != NULL) (*e)->SetObjectArrayElement(e, out, 0, v);
    return r;
}

static jint JNICALL nGetLocalInt(JNIEnv* e, jclass c, jobject t, jint depth, jint slot, jintArray out) {
    jint v = 0;
    jint r = check((*g_jvmti)->GetLocalInt(g_jvmti, (jthread)t, depth, slot, &v));
    if (r == 0 && out != NULL) (*e)->SetIntArrayRegion(e, out, 0, 1, &v);
    return r;
}

static jint JNICALL nSetLocalInt(JNIEnv* e, jclass c, jobject t, jint depth, jint slot, jint v) {
    return check((*g_jvmti)->SetLocalInt(g_jvmti, (jthread)t, depth, slot, v));
}

/* Resolve a jfieldID from (class,name,sig); tries instance then static. */
static jfieldID resolveField(JNIEnv* e, jclass k, jstring name, jstring sig) {
    const char* n = (*e)->GetStringUTFChars(e, name, NULL);
    const char* s = (*e)->GetStringUTFChars(e, sig, NULL);
    jfieldID f = (*e)->GetFieldID(e, k, n, s);
    if ((*e)->ExceptionCheck(e)) {
        (*e)->ExceptionClear(e);
        f = (*e)->GetStaticFieldID(e, k, n, s);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
    }
    (*e)->ReleaseStringUTFChars(e, name, n);
    (*e)->ReleaseStringUTFChars(e, sig, s);
    return f;
}

static jint JNICALL nSetFieldModificationWatch(JNIEnv* e, jclass c, jclass k, jstring field, jstring sig) {
    jfieldID f = resolveField(e, k, field, sig);
    if (f == NULL) return (jint)JVMTI_ERROR_INVALID_FIELDID;
    jint r = check((*g_jvmti)->SetFieldModificationWatch(g_jvmti, k, f));
    if (r == 0) (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIELD_MODIFICATION, NULL);
    return r;
}

static jint JNICALL nClearFieldModificationWatch(JNIEnv* e, jclass c, jclass k, jstring field, jstring sig) {
    jfieldID f = resolveField(e, k, field, sig);
    if (f == NULL) return (jint)JVMTI_ERROR_INVALID_FIELDID;
    return check((*g_jvmti)->ClearFieldModificationWatch(g_jvmti, k, f));
}

/* ---- native binding: shared by VMInit and JNI_OnLoad ----
 *
 * Binds the KdBridge natives + caches onDebugEvent onto the class as
 * resolved by the given JNIEnv. Idempotent: RegisterNatives may run more than
 * once (VMInit's bootstrap-context resolution AND KdBridge's own
 * System.load in the APP classloader context); the app-context binding is the
 * one that matters, so calling twice is harmless and the second (correct) call
 * wins. Returns 1 on success, 0 if the class could not be found/bound. */
static int bindNatives(JNIEnv* e) {
    jclass bridge = (*e)->FindClass(e, "net/marcloud/mcp/core/kd/KdBridge");
    if (bridge == NULL) {
        (*e)->ExceptionClear(e);
        return 0;
    }
    static const JNINativeMethod M[] = {
        {"nAgentReady",                "()Z",                                          (void*)&nAgentReady},
        {"nSuspendThread",             "(Ljava/lang/Thread;)I",                        (void*)&nSuspendThread},
        {"nResumeThread",              "(Ljava/lang/Thread;)I",                        (void*)&nResumeThread},
        {"nPopFrame",                  "(Ljava/lang/Thread;)I",                        (void*)&nPopFrame},
        {"nForceEarlyReturnVoid",      "(Ljava/lang/Thread;)I",                        (void*)&nForceEarlyReturnVoid},
        {"nForceEarlyReturnInt",       "(Ljava/lang/Thread;I)I",                       (void*)&nForceEarlyReturnInt},
        {"nForceEarlyReturnObject",    "(Ljava/lang/Thread;Ljava/lang/Object;)I",      (void*)&nForceEarlyReturnObject},
        {"nSetBreakpoint",             "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;J)I", (void*)&nSetBreakpoint},
        {"nClearBreakpoint",           "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;J)I", (void*)&nClearBreakpoint},
        {"nSetSingleStep",             "(Ljava/lang/Thread;Z)I",                       (void*)&nSetSingleStep},
        {"nGetLocalObject",            "(Ljava/lang/Thread;II[Ljava/lang/Object;)I",   (void*)&nGetLocalObject},
        {"nGetLocalInt",               "(Ljava/lang/Thread;II[I)I",                    (void*)&nGetLocalInt},
        {"nSetLocalInt",               "(Ljava/lang/Thread;III)I",                     (void*)&nSetLocalInt},
        {"nSetFieldModificationWatch", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)I", (void*)&nSetFieldModificationWatch},
        {"nClearFieldModificationWatch","(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)I", (void*)&nClearFieldModificationWatch},
    };
    if ((*e)->RegisterNatives(e, bridge, M, (jint)(sizeof(M) / sizeof(M[0]))) != 0) {
        (*e)->ExceptionClear(e);
        fprintf(stderr, "[core-jvmti] RegisterNatives failed\n");
        return 0;
    }
    /* Refresh the global ref + dispatcher to the class the natives are now bound
     * on (the app-classloader instance when called from JNI_OnLoad). */
    if (g_bridge != NULL) {
        (*e)->DeleteGlobalRef(e, g_bridge);
    }
    g_bridge = (*e)->NewGlobalRef(e, bridge);
    g_onEvent = (*e)->GetStaticMethodID(e, bridge, "onDebugEvent",
                                        "(ILjava/lang/Thread;Ljava/lang/String;J)V");
    if (g_onEvent == NULL) {
        (*e)->ExceptionClear(e);
        fprintf(stderr, "[core-jvmti] onDebugEvent not found\n");
    }
    return 1;
}

/* ---- VMInit: attempt an early bind (bootstrap context) + it is the event seam.
 * The authoritative bind happens in JNI_OnLoad (app-classloader context); this
 * early attempt is best-effort and silent if the app class isn't visible yet. */
static void JNICALL vmInit(jvmtiEnv* j, JNIEnv* e, jthread t) {
    bindNatives(e); /* best-effort; JNI_OnLoad does the authoritative bind */
}

/* ---- Agent_OnLoad: grab the onload-only capabilities ---- */

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    g_vm = vm;
    jvmtiEnv* jvmti = NULL;
    if ((*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK || jvmti == NULL) {
        return JNI_ERR;
    }
    g_jvmti = jvmti;

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_tag_objects = 1;
    caps.can_generate_field_modification_events = 1;
    caps.can_generate_field_access_events = 1;
    caps.can_pop_frame = 1;
    caps.can_access_local_variables = 1;
    caps.can_generate_single_step_events = 1;
    caps.can_generate_breakpoint_events = 1;
    caps.can_suspend = 1;
    caps.can_force_early_return = 1;
    if ((*jvmti)->AddCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE) {
        /* Boot the JVM anyway; Java sees nAgentReady()==false. */
        fprintf(stderr, "[core-jvmti] AddCapabilities failed — debugger disabled\n");
        return JNI_OK;
    }

    jvmtiEventCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.VMInit = &vmInit;
    cb.Breakpoint = &onBreakpoint;
    cb.SingleStep = &onSingleStep;
    cb.FieldModification = &onFieldModification;
    if ((*jvmti)->SetEventCallbacks(jvmti, &cb, (jint)sizeof(cb)) != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[core-jvmti] SetEventCallbacks failed\n");
        return JNI_OK;
    }
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
    /* Breakpoint/SingleStep/FieldModification modes are toggled on demand, so the
     * JVM pays zero event cost until a debug tool asks. */
    g_ready = 1;
    fprintf(stderr, "[core-jvmti] agent loaded; JVMTI debugger capabilities acquired.\n");
    return JNI_OK;
}

/* Authoritative native binding point. When KdBridge calls System.load on
 * the (already -agentpath-loaded) module, the JVM invokes JNI_OnLoad on the
 * calling thread, whose JNIEnv resolves FindClass in the APP classloader context
 * — the same class whose `native` methods must be bound. VMInit's earlier bind
 * used the bootstrap context and may have bound a different Class instance (or
 * none), which is exactly why the app-side nAgentReady() previously threw
 * UnsatisfiedLinkError. Binding here fixes that. */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* e = NULL;
    if ((*vm)->GetEnv(vm, (void**)&e, JNI_VERSION_10) == JNI_OK && e != NULL) {
        if (!bindNatives(e)) {
            fprintf(stderr, "[core-jvmti] JNI_OnLoad: KdBridge natives not bound\n");
        } else {
            fprintf(stderr, "[core-jvmti] JNI_OnLoad: natives bound to KdBridge.\n");
        }
    }
    return JNI_VERSION_10;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    /* JVMTI env + global ref live for the JVM's lifetime; nothing to free here. */
}
