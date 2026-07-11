/*
 * core-jvmti.h — MCPClient C6 CONTROL-EXEC native JVMTI agent.
 *
 * Windows-x64 JVMTI agent loaded via -agentpath at JVM startup. Grabs the
 * onload-only capabilities (suspend / pop-frame / force-early-return /
 * breakpoint / single-step / local-variable access / field-modification watch)
 * that dynamic attach cannot obtain, then binds the native methods declared on
 * the Java class net.marcloud.mcp.core.kd.KdBridge via RegisterNatives
 * (in JNI_OnLoad) — NOT via System.loadLibrary alone, because the module is
 * loaded by -agentpath.
 *
 * The exported native functions match KdBridge's private static native
 * declarations EXACTLY (names + JNI signatures). Events call back into
 * KdBridge.onDebugEvent(int kind, Thread, String location, long numeric).
 *
 * x64-only: jmethodID/jfieldID are marshalled to Java as jlong via
 * (jlong)(intptr_t) — valid where pointers are <= 64-bit (windows-x64, the JBR
 * b508.16 build target).
 */
#ifndef CORE_JVMTI_H
#define CORE_JVMTI_H

#include <jvmti.h>   /* pulls in jni.h */

#ifdef __cplusplus
extern "C" {
#endif

/* Event kind ints passed to KdBridge.onDebugEvent. */
#define CORE_EVT_BREAKPOINT        1
#define CORE_EVT_SINGLE_STEP       2
#define CORE_EVT_FIELD_MODIFICATION 3

/* JVMTI entry points. */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved);
JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm);
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

#ifdef __cplusplus
}
#endif

#endif /* CORE_JVMTI_H */
