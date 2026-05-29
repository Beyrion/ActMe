#include <jni.h>
#include <string>
#include <utility>
#include <vector>
#include <sstream>
#include <mutex>

#include "mls_log.h"
#include "json.hpp"
#include "llm_session.h"

using json = nlohmann::json;

namespace {

JavaVM* g_jvm = nullptr;

struct NativeSession {
    mls::LlmSession* session = nullptr;
    std::mutex mutex;
    bool busy = false;
};

jfieldID getNativePtrField(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    return env->GetFieldID(clazz, "nativePtr", "J");
}

NativeSession* getNativeSession(JNIEnv* env, jobject thiz) {
    jfieldID fieldId = getNativePtrField(env, thiz);
    if (fieldId == nullptr) return nullptr;
    jlong ptr = env->GetLongField(thiz, fieldId);
    return reinterpret_cast<NativeSession*>(ptr);
}

} // anonymous namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    MNN_DEBUG("JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    g_jvm = nullptr;
    MNN_DEBUG("JNI_OnUnload");
}

JNIEXPORT jstring JNICALL
Java_com_actme_app_mnn_MnnLlm_nativeGetVersion(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(MNN_VERSION);
}

JNIEXPORT jlong JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeInit(JNIEnv* env, jobject thiz,
                                                  jstring modelDir,
                                                  jstring configJson) {
    const char* modelDirCstr = env->GetStringUTFChars(modelDir, nullptr);
    const char* configCstr = env->GetStringUTFChars(configJson, nullptr);

    std::string modelDirStr(modelDirCstr);
    std::string configStr(configCstr);

    env->ReleaseStringUTFChars(modelDir, modelDirCstr);
    env->ReleaseStringUTFChars(configJson, configCstr);

    json config;
    try {
        config = json::parse(configStr);
    } catch (const std::exception& e) {
        MNN_ERROR("Failed to parse config JSON: %s", e.what());
        jclass exClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exClass) {
            env->ThrowNew(exClass, "Invalid config JSON");
        }
        return 0;
    }

    if (!config.contains("llm_model")) {
        config["llm_model"] = "llm.mnn";
    }
    if (!config.contains("llm_weight")) {
        config["llm_weight"] = "llm.mnn.weight";
    }

    auto* nativeSession = new NativeSession();
    json extraConfig = json::object();

    std::vector<std::string> emptyHistory;
    nativeSession->session = new mls::LlmSession(modelDirStr, config, extraConfig, emptyHistory);

    MNN_DEBUG("initNative: modelDir=%s", modelDirStr.c_str());

    bool loadSuccess = nativeSession->session->load();
    if (!loadSuccess) {
        std::string errMsg = nativeSession->session->getLastLoadError();
        MNN_ERROR("Model load failed: %s", errMsg.c_str());
        delete nativeSession->session;
        delete nativeSession;
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass) {
            env->ThrowNew(exClass, errMsg.c_str());
        }
        return 0;
    }

    MNN_DEBUG("Session initialized successfully at %p", nativeSession);
    return reinterpret_cast<jlong>(nativeSession);
}

JNIEXPORT jstring JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeSubmit(JNIEnv* env, jobject thiz,
                                                    jlong nativePtr,
                                                    jstring prompt) {
    auto* nativeSession = reinterpret_cast<NativeSession*>(nativePtr);
    if (!nativeSession || !nativeSession->session) {
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass) {
            env->ThrowNew(exClass, "Session not initialized");
        }
        return nullptr;
    }

    const char* promptCstr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptCstr);
    env->ReleaseStringUTFChars(prompt, promptCstr);

    MNN_DEBUG("nativeSubmit: prompt length=%zu", promptStr.size());

    std::string fullResponse;
    std::mutex responseMutex;
    std::condition_variable cv;
    bool done = false;

    {
        std::lock_guard<std::mutex> lock(nativeSession->mutex);
        nativeSession->busy = true;
    }

    nativeSession->session->response(promptStr,
        [&](const std::string& chunk, bool isEop) -> bool {
            std::lock_guard<std::mutex> lock(responseMutex);
            if (!isEop) {
                fullResponse += chunk;
            }
            if (isEop) {
                done = true;
                cv.notify_one();
            }
            return false;  // never request stop
        });

    {
        std::unique_lock<std::mutex> lock(responseMutex);
        cv.wait(lock, [&] { return done; });
    }

    {
        std::lock_guard<std::mutex> lock(nativeSession->mutex);
        nativeSession->busy = false;
    }

    return env->NewStringUTF(fullResponse.c_str());
}

JNIEXPORT void JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong nativePtr) {
    auto* nativeSession = reinterpret_cast<NativeSession*>(nativePtr);
    if (!nativeSession || !nativeSession->session) return;
    std::lock_guard<std::mutex> lock(nativeSession->mutex);
    nativeSession->session->reset();
}

JNIEXPORT void JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jlong nativePtr) {
    auto* nativeSession = reinterpret_cast<NativeSession*>(nativePtr);
    if (!nativeSession) return;
    MNN_DEBUG("Releasing session at %p", nativeSession);
    std::lock_guard<std::mutex> lock(nativeSession->mutex);
    delete nativeSession->session;
    nativeSession->session = nullptr;
    delete nativeSession;
}

JNIEXPORT void JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeSetMaxNewTokens(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong nativePtr, jint maxTokens) {
    auto* nativeSession = reinterpret_cast<NativeSession*>(nativePtr);
    if (!nativeSession || !nativeSession->session) return;
    std::lock_guard<std::mutex> lock(nativeSession->mutex);
    nativeSession->session->setMaxNewTokens(maxTokens);
}

JNIEXPORT jstring JNICALL
Java_com_actme_app_mnn_MnnLlmSession_nativeDumpConfig(JNIEnv* env, jobject /*thiz*/,
                                                       jlong nativePtr) {
    auto* nativeSession = reinterpret_cast<NativeSession*>(nativePtr);
    if (!nativeSession || !nativeSession->session) return env->NewStringUTF("{}");
    return env->NewStringUTF(nativeSession->session->dumpConfig().c_str());
}

} // extern "C"
