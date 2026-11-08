/* SPDX-License-Identifier: AGPL-3.0-only */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "msgat_security_core.h"

static void wipe(uint8_t *bytes, size_t length) {
    volatile uint8_t *cursor = bytes;
    while (length--) *cursor++ = 0;
}

static void fail(JNIEnv *env, const char *class_name, const char *message) {
    jclass type = (*env)->FindClass(env, class_name);
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jint JNICALL Java_org_msgat_core_crypto_SecurityCore_abiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (jint)msgat_security_abi_version();
}

JNIEXPORT jbyteArray JNICALL Java_org_msgat_core_crypto_SecurityCore_nativeCall(JNIEnv *env, jobject self, jbyteArray input) {
    (void)self;
    if (input == NULL) {
        fail(env, "java/lang/IllegalArgumentException", "Missing security request");
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, input);
    if (length <= 0 || length > 4 * 1024 * 1024) {
        fail(env, "java/lang/IllegalArgumentException", "Security request exceeds its size limit");
        return NULL;
    }
    uint8_t *request = malloc((size_t)length);
    if (request == NULL) {
        fail(env, "java/lang/OutOfMemoryError", "Cannot allocate a security request");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)request);
    if ((*env)->ExceptionCheck(env)) {
        wipe(request, (size_t)length); free(request);
        return NULL;
    }
    MsgatBuffer output = {0};
    int32_t status = msgat_security_call(request, (size_t)length, &output);
    wipe(request, (size_t)length); free(request);
    if (status != 0 || output.data == NULL || output.length == 0 || output.length > 8 * 1024 * 1024) {
        msgat_buffer_free(&output);
        fail(env, "java/lang/IllegalStateException", "Invalid native security response");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output.length);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.length, (const jbyte *)output.data);
    msgat_buffer_free(&output);
    return result;
}
