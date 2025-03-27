#include <jni.h>
#include <string.h>

// Implementation of the native method
JNIEXPORT jstring JNICALL Java_foo_HelloWorld_sayHello(JNIEnv *env, jobject obj) {
    return (*env)->NewStringUTF(env, "Hello, World!");
}
