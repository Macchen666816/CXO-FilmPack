#include <jni.h>
// Keep the APK installable on other ABIs. Their product path deliberately uses
// the Kotlin reference; no stub encode/render function can be selected.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cxo_filmpack_app_NativeEntryBridge_isAArch64(JNIEnv*, jobject) { return JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cxo_filmpack_app_NativeJpegBridge_isAArch64(JNIEnv*, jobject) { return JNI_FALSE; }
