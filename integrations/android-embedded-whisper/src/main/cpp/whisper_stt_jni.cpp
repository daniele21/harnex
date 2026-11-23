#include <jni.h>
#include <limits>
#include <memory>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
constexpr int kMaxSamples = 16000 * 30;
constexpr int kMinSamples = 16000 / 4;

void fail(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

struct Cancellation {
    JavaVM * vm;
    jobject signal;  // Global reference, valid through synchronous whisper_full.
    jmethodID get;
};

bool is_cancelled(void * data) {
    auto * token = static_cast<Cancellation *>(data);
    JNIEnv * env = nullptr;
    bool attached = false;
    if (token->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (token->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
        attached = true;
    }
    const bool cancelled = env->CallBooleanMethod(token->signal, token->get) == JNI_TRUE || env->ExceptionCheck();
    if (attached) token->vm->DetachCurrentThread();
    return cancelled;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_daniele21_localllm_embedded_whisper_WhisperNative_transcribe(
        JNIEnv * env, jobject, jstring model_path, jfloatArray audio, jobject cancel_flag) {
    if (!model_path || !audio || !cancel_flag) {
        fail(env, "Invalid STT input");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(audio);
    if (sample_count < kMinSamples || sample_count > kMaxSamples) {
        fail(env, "Audio must be between 0.25 and 30 seconds at 16 kHz");
        return nullptr;
    }
    std::vector<float> samples(static_cast<size_t>(sample_count));
    env->GetFloatArrayRegion(audio, 0, sample_count, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return nullptr;
    whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    auto options = whisper_context_default_params();
    options.use_gpu = false;
    whisper_context * raw = whisper_init_from_file_with_params(path, options);
    env->ReleaseStringUTFChars(model_path, path);
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(raw, whisper_free);
    if (!ctx) {
        fail(env, "Unable to load verified Whisper Small Q5 model");
        return nullptr;
    }

    jclass flag_cls = env->GetObjectClass(cancel_flag);
    if (!flag_cls) return nullptr;
    jmethodID is_set = env->GetMethodID(flag_cls, "get", "()Z");
    env->DeleteLocalRef(flag_cls);
    if (!is_set) return nullptr;
    JavaVM * vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) {
        fail(env, "Java VM unavailable for Whisper cancellation");
        return nullptr;
    }
    jobject global_flag = env->NewGlobalRef(cancel_flag);
    if (!global_flag) return nullptr;
    Cancellation cancel{vm, global_flag, is_set};

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "it";
    params.translate = false;
    params.no_context = true;
    params.n_threads = 4;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.abort_callback = is_cancelled;
    params.abort_callback_user_data = &cancel;

    int result = is_cancelled(&cancel) ? -1 : whisper_full(ctx.get(), params, samples.data(), sample_count);
    env->DeleteGlobalRef(global_flag);
    if (env->ExceptionCheck()) return nullptr;
    if (result != 0) {
        fail(env, "Whisper transcription failed or was cancelled");
        return nullptr;
    }
    std::string text;
    const int segments = whisper_full_n_segments(ctx.get());
    for (int index = 0; index < segments; ++index) {
        const char * segment = whisper_full_get_segment_text(ctx.get(), index);
        if (segment) text.append(segment);
    }
    if (text.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        fail(env, "Whisper output exceeds JNI limits");
        return nullptr;
    }
    const auto size = static_cast<jsize>(text.size());
    jbyteArray utf8 = env->NewByteArray(size);
    if (utf8 && size > 0) {
        env->SetByteArrayRegion(utf8, 0, size, reinterpret_cast<const jbyte *>(text.data()));
    }
    return utf8;
}
