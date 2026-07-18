#include <jni.h>
#include <string>

namespace {
constexpr const char* kStatus =
    "JNI bridge ready; CuraEngine 5.11.0 source is not linked yet";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tomppi_enderslicer_nativebridge_NativeSlicer_nativeStatus(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(kStatus);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tomppi_enderslicer_nativebridge_NativeSlicer_nativeSlice(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jstring /* input_path */,
    jstring /* output_path */,
    jstring /* settings_json */) {
    // Reserved for the CuraEngine adapter. Never emit placeholder G-code.
    return -1001;
}
