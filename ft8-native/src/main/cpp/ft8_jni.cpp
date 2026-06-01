#include <jni.h>
#include <string>

// JNI bridge for the FT8 native core.
//
// Phase 0: only reports a version string, confirming the .so loads and the
// JNI symbol resolves at runtime. Phase 2 adds decodeFrame()/encodeMessage()
// backed by kgoba/ft8_lib.

extern "C" JNIEXPORT jstring JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeVersion(JNIEnv *env, jobject /* this */) {
    const std::string version = "ft8vc-native 0.1.0 (stub)";
    return env->NewStringUTF(version.c_str());
}
