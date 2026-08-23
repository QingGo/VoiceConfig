#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>

#include "transcribe.h"

#define TAG "TranscribeCppJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct JniSession {
    transcribe_session * session = nullptr;
    std::atomic<bool> * cancel = nullptr;
};

bool abort_thunk(void * user_data) {
    auto * flag = static_cast<std::atomic<bool> *>(user_data);
    return flag != nullptr && flag->load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voiceconfig_app_ai_TranscribeCppAsrEngine_nativeOpen(
        JNIEnv * env, jobject /*thiz*/, jstring modelPath, jint threads) {
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }
    const std::string path_str(path);

    transcribe_model_load_params load_params;
    transcribe_model_load_params_init(&load_params);
    transcribe_session_params session_params;
    transcribe_session_params_init(&session_params);
    session_params.n_threads = threads > 0 ? threads : 4;

    auto * wrapper = new (std::nothrow) JniSession();
    if (wrapper == nullptr) {
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    transcribe_status st = transcribe_open(path, &load_params, &session_params, &wrapper->session);
    env->ReleaseStringUTFChars(modelPath, path);
    const char * display_path = path_str.c_str();

    if (st != TRANSCRIBE_OK) {
        LOGE("nativeOpen failed: %s (%d)", transcribe_status_string(st), static_cast<int>(st));
        delete wrapper;
        return 0;
    }

    wrapper->cancel = new (std::nothrow) std::atomic<bool>(false);
    if (wrapper->cancel == nullptr) {
        transcribe_close(wrapper->session);
        delete wrapper;
        return 0;
    }
    transcribe_set_abort_callback(wrapper->session, abort_thunk, wrapper->cancel);
    LOGI("nativeOpen ok: model=%s threads=%d", display_path, static_cast<int>(session_params.n_threads));
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_com_voiceconfig_app_ai_TranscribeCppAsrEngine_nativeClose(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * wrapper = reinterpret_cast<JniSession *>(handle);
    if (wrapper == nullptr) {
        return;
    }
    transcribe_set_abort_callback(wrapper->session, nullptr, nullptr);
    if (wrapper->session != nullptr) {
        transcribe_close(wrapper->session);
    }
    delete wrapper->cancel;
    delete wrapper;
}

JNIEXPORT void JNICALL
Java_com_voiceconfig_app_ai_TranscribeCppAsrEngine_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * wrapper = reinterpret_cast<JniSession *>(handle);
    if (wrapper != nullptr && wrapper->cancel != nullptr) {
        wrapper->cancel->store(true);
    }
}

JNIEXPORT jstring JNICALL
Java_com_voiceconfig_app_ai_TranscribeCppAsrEngine_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jfloatArray samples, jstring language) {
    auto * wrapper = reinterpret_cast<JniSession *>(handle);
    if (wrapper == nullptr || wrapper->session == nullptr) {
        return env->NewStringUTF("");
    }
    jsize len = env->GetArrayLength(samples);
    if (len <= 0) {
        return env->NewStringUTF("");
    }
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        return env->NewStringUTF("");
    }

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    if (wrapper->cancel != nullptr) {
        wrapper->cancel->store(false);
    }
    std::string language_str;
    if (language != nullptr) {
        const char * lang = env->GetStringUTFChars(language, nullptr);
        if (lang != nullptr && lang[0] != '\0') {
            language_str.assign(lang);
            run_params.language = language_str.c_str();
        }
        env->ReleaseStringUTFChars(language, lang);
    }

    const auto start_ms = std::chrono::steady_clock::now();
    transcribe_status st = transcribe_run(wrapper->session, pcm, static_cast<int>(len), &run_params);
    const auto end_ms = std::chrono::steady_clock::now();
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    if (st != TRANSCRIBE_OK) {
        LOGE("nativeTranscribe failed: %s (%d) elapsedMs=%lld",
             transcribe_status_string(st), static_cast<int>(st),
             static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(end_ms - start_ms).count()));
        return env->NewStringUTF("");
    }

    transcribe_timings timings;
    transcribe_timings_init(&timings);
    if (transcribe_get_timings(wrapper->session, &timings) == TRANSCRIBE_OK) {
        LOGI("transcribe-cpp-file loadMs=%.1f melMs=%.1f encodeMs=%.1f decodeMs=%.1f",
             timings.load_ms, timings.mel_ms, timings.encode_ms, timings.decode_ms);
    }

    const char * text = transcribe_full_text(wrapper->session);
    if (text == nullptr) {
        text = "";
    }
    return env->NewStringUTF(text);
}

} // extern "C"
