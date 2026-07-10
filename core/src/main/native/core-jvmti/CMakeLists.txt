cmake_minimum_required(VERSION 3.20)
project(core_jvmti C)

# JBR headers (jvmti.h + jni.h + win32/jni_md.h). Pinned to the bundled JBR so
# the jvmtiCapabilities struct layout matches the runtime exactly.
set(JBR_INCLUDE "${CMAKE_SOURCE_DIR}/../../../../../_tools/jbrsdk-25.0.3-windows-x64-b508.16/include")

add_library(core-jvmti SHARED core-jvmti.c)
target_include_directories(core-jvmti PRIVATE "${JBR_INCLUDE}" "${JBR_INCLUDE}/win32")

# Output "core-jvmti.dll" (no "lib" prefix). JNIEXPORT carries __declspec(dllexport);
# no jvm.lib link needed — the agent is loaded by the JVM, not linked against it.
set_target_properties(core-jvmti PROPERTIES OUTPUT_NAME "core-jvmti" PREFIX "")

# x64 only (jmethodID/jfieldID marshalled as jlong assumes <=64-bit pointers).
if (NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "core-jvmti is windows-x64 only")
endif()
