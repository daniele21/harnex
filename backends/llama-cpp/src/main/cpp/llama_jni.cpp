#include <jni.h>

#include "flash_attention_params.h"
#include "ggml-backend.h"
#include "gguf_metadata.h"
#include "kv_cache_type_params.h"
#include "llama.h"
#include "model_load_params_compat.h"
#include "native_handle_registry.h"
#include "prepared_prompt_cache.h"
#include "json-schema-to-grammar.h"
#include "chat.h"
#include <nlohmann/json.hpp>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {

jobjectArray to_java_string_array(JNIEnv* env, const std::vector<std::string>& values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(string_class);
        return nullptr;
    }

    for (std::size_t index = 0; index < values.size(); ++index) {
        jstring value = env->NewStringUTF(values[index].c_str());
        if (value == nullptr) {
            env->DeleteLocalRef(string_class);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

std::vector<std::string> error_response(const char* code, std::string message) {
    return {"error", code, std::move(message)};
}

std::vector<std::string> encode_inspection(const local_llm::GgufInspection& inspection) {
    if (!inspection.success()) {
        return error_response(
            local_llm::gguf_inspection_error_code_name(inspection.error_code),
            inspection.error_message
        );
    }

    const local_llm::GgufMetadata& metadata = inspection.metadata;
    return {
        "ok",
        std::to_string(metadata.version),
        std::to_string(metadata.alignment),
        std::to_string(metadata.data_offset),
        std::to_string(metadata.key_value_count),
        std::to_string(metadata.tensor_count),
        metadata.architecture,
        metadata.name,
        metadata.file_type.has_value() ? std::to_string(metadata.file_type.value()) : std::string{},
        metadata.context_length.has_value() ? std::to_string(metadata.context_length.value()) : std::string{},
        metadata.block_count.has_value() ? std::to_string(metadata.block_count.value()) : std::string{},
        metadata.embedding_length.has_value() ? std::to_string(metadata.embedding_length.value()) : std::string{},
    };
}

std::string base64_encode(const std::string& input) {
    static constexpr char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string output;
    output.reserve(((input.size() + 2) / 3) * 4);

    std::uint32_t accumulator = 0;
    int bits = -6;
    for (const unsigned char value : input) {
        accumulator = (accumulator << 8) | value;
        bits += 8;
        while (bits >= 0) {
            output.push_back(alphabet[(accumulator >> bits) & 0x3fU]);
            bits -= 6;
        }
    }
    if (bits > -6) {
        output.push_back(alphabet[((accumulator << 8) >> (bits + 8)) & 0x3fU]);
    }
    while (output.size() % 4 != 0) {
        output.push_back('=');
    }
    return output;
}

class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring value) {
        if (value == nullptr) return;
        const jsize length = env->GetStringLength(value);
        const jchar* units = env->GetStringChars(value, nullptr);
        if (units == nullptr) return;
        bool valid = true;
        for (jsize index = 0; index < length && valid; ++index) {
            std::uint32_t code_point = units[index];
            if (code_point >= 0xD800U && code_point <= 0xDBFFU) {
                if (++index >= length || units[index] < 0xDC00U || units[index] > 0xDFFFU) {
                    valid = false;
                    break;
                }
                code_point = 0x10000U + ((code_point - 0xD800U) << 10U) + (units[index] - 0xDC00U);
            } else if (code_point >= 0xDC00U && code_point <= 0xDFFFU) {
                valid = false;
                break;
            }
            append_code_point(code_point);
        }
        env->ReleaseStringChars(value, units);
        valid_ = valid;
    }

    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;

    const char* get() const {
        return valid_ ? chars_.c_str() : nullptr;
    }

private:
    void append_code_point(std::uint32_t code_point) {
        if (code_point <= 0x7FU) {
            chars_.push_back(static_cast<char>(code_point));
        } else if (code_point <= 0x7FFU) {
            chars_.push_back(static_cast<char>(0xC0U | (code_point >> 6U)));
            chars_.push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
        } else if (code_point <= 0xFFFFU) {
            chars_.push_back(static_cast<char>(0xE0U | (code_point >> 12U)));
            chars_.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
            chars_.push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
        } else {
            chars_.push_back(static_cast<char>(0xF0U | (code_point >> 18U)));
            chars_.push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3FU)));
            chars_.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
            chars_.push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
        }
    }

    std::string chars_;
    bool valid_ = false;
};

class BackendRuntime final {
public:
    std::vector<std::string> initialize(const std::string& native_library_dir) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (initialized_) {
            return {"ok", std::to_string(llama_max_devices())};
        }

        if (native_library_dir.empty()) {
            ggml_backend_load_all();
        } else {
            ggml_backend_load_all_from_path(native_library_dir.c_str());
        }
        llama_backend_init();

        const std::size_t device_count = llama_max_devices();
        if (device_count == 0) {
            llama_backend_free();
            return error_response("BACKEND_UNAVAILABLE", "No llama.cpp backend device was registered");
        }

        initialized_ = true;
        return {"ok", std::to_string(device_count)};
    }

    std::vector<std::string> shutdown(bool resources_active) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!initialized_) {
            return {"ok"};
        }
        if (resources_active) {
            return error_response("BUSY", "Native resources must be released before runtime shutdown");
        }

        llama_backend_free();
        initialized_ = false;
        return {"ok"};
    }

    bool initialized() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return initialized_;
    }

private:
    mutable std::mutex mutex_;
    bool initialized_ = false;
};

struct ModelRecord final {
    ModelRecord(llama_model* model_value, std::string path_value)
        : model(model_value), path(std::move(path_value)) {}

    ~ModelRecord() {
        if (model != nullptr) {
            llama_model_free(model);
        }
    }

    ModelRecord(const ModelRecord&) = delete;
    ModelRecord& operator=(const ModelRecord&) = delete;

    llama_model* model;
    std::string path;
    local_llm::PreparedPromptCache prepared_prompt_cache;
    std::mutex lifecycle_mutex;
    std::size_t active_contexts = 0;
    bool available = true;
};

struct ContextRecord final {
    ContextRecord(
        llama_context* context_value,
        std::shared_ptr<ModelRecord> model_value,
        std::int32_t batch_size_value
    ) : context(context_value), model(std::move(model_value)), batch_size(batch_size_value) {}

    ~ContextRecord() {
        if (context != nullptr) {
            llama_free(context);
        }
        std::lock_guard<std::mutex> lock(model->lifecycle_mutex);
        if (model->active_contexts > 0) {
            --model->active_contexts;
        }
    }

    ContextRecord(const ContextRecord&) = delete;
    ContextRecord& operator=(const ContextRecord&) = delete;

    llama_context* context;
    std::shared_ptr<ModelRecord> model;
    std::int32_t batch_size;
    std::mutex generation_mutex;
};

BackendRuntime backend_runtime;
NativeHandleRegistry<ModelRecord> models;
NativeHandleRegistry<ContextRecord> contexts;
std::atomic<std::size_t> active_generations{0};

class ActiveGeneration final {
public:
    ActiveGeneration() {
        active_generations.fetch_add(1, std::memory_order_acq_rel);
    }

    ~ActiveGeneration() {
        active_generations.fetch_sub(1, std::memory_order_acq_rel);
    }

    ActiveGeneration(const ActiveGeneration&) = delete;
    ActiveGeneration& operator=(const ActiveGeneration&) = delete;
};

std::vector<llama_token> tokenize_prompt(const llama_vocab* vocab, const std::string& prompt) {
    if (prompt.size() > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
        return {};
    }

    const std::int32_t required = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<std::int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (required == std::numeric_limits<std::int32_t>::min()) {
        return {};
    }

    const std::int32_t token_count = required < 0 ? -required : required;
    if (token_count <= 0) {
        return {};
    }

    std::vector<llama_token> tokens(static_cast<std::size_t>(token_count));
    const std::int32_t written = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<std::int32_t>(prompt.size()),
        tokens.data(),
        token_count,
        true,
        true
    );
    if (written < 0) {
        return {};
    }
    tokens.resize(static_cast<std::size_t>(written));
    return tokens;
}

std::string token_piece(const llama_vocab* vocab, llama_token token) {
    std::vector<char> buffer(128);
    std::int32_t written = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<std::int32_t>(buffer.size()),
        0,
        true
    );
    if (written < 0) {
        buffer.resize(static_cast<std::size_t>(-written));
        written = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<std::int32_t>(buffer.size()),
            0,
            true
        );
    }
    return written < 0 ? std::string{} : std::string(buffer.data(), static_cast<std::size_t>(written));
}

bool read_java_string_array(JNIEnv* env, jobjectArray values, std::vector<std::string>& output) {
    if (values == nullptr) {
        return false;
    }
    const jsize size = env->GetArrayLength(values);
    if (size < 0 || size > 128) {
        return false;
    }
    output.reserve(static_cast<std::size_t>(size));
    for (jsize index = 0; index < size; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        if (value == nullptr) {
            return false;
        }
        const UtfChars chars(env, value);
        if (chars.get() == nullptr) {
            env->DeleteLocalRef(value);
            return false;
        }
        output.emplace_back(chars.get());
        env->DeleteLocalRef(value);
    }
    return true;
}

std::vector<std::string> plan_prompt(
    ModelRecord& record,
    const std::vector<std::string>& roles,
    const std::vector<std::string>& contents,
    const std::string& system_prompt,
    const std::string& application_template_id,
    const std::string& application_template,
    const std::string& family_template_id,
    const std::string& family_template,
    const std::string& raw_completion,
    bool enable_thinking
) {
    const llama_vocab* vocab = llama_model_get_vocab(record.model);
    if (!raw_completion.empty()) {
        const std::vector<llama_token> tokens = tokenize_prompt(vocab, raw_completion);
        if (tokens.empty()) {
            return error_response("TOKENIZATION_FAILED", "Raw completion tokenization returned no tokens");
        }
        record.prepared_prompt_cache.store(raw_completion, tokens);
        return {
            "ok",
            base64_encode(raw_completion),
            std::to_string(tokens.size()),
            "raw-completion",
            "RAW_COMPLETION",
            "",
        };
    }
    if (roles.size() != contents.size() || roles.empty()) {
        return error_response("INVALID_ARGUMENT", "Prompt roles and contents must have the same non-zero size");
    }

    const char* selected_template = nullptr;
    std::string template_id;
    std::string template_source;
    selected_template = llama_model_chat_template(record.model, nullptr);
    if (selected_template != nullptr && selected_template[0] != '\0') {
        template_id = "gguf-default";
        template_source = "GGUF";
    } else if (!application_template.empty()) {
        selected_template = application_template.c_str();
        template_id = application_template_id;
        template_source = "APPLICATION_OVERRIDE";
    } else if (!family_template.empty()) {
        selected_template = family_template.c_str();
        template_id = family_template_id;
        template_source = "FAMILY_FALLBACK";
    } else {
        return error_response("CHAT_TEMPLATE_UNAVAILABLE", "No approved chat template is available for this model");
    }

    common_chat_templates_ptr templates;
    try {
        const std::string override_template = template_source == "GGUF" ? std::string{} : std::string(selected_template);
        templates = common_chat_templates_init(record.model, override_template);
    } catch (const std::exception&) {
        return error_response("CHAT_TEMPLATE_UNSUPPORTED", "The selected chat template is not supported by this runtime");
    }
    if (!templates) {
        return error_response("CHAT_TEMPLATE_UNSUPPORTED", "The selected chat template could not be initialized");
    }

    common_chat_templates_inputs inputs;
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = enable_thinking;
    inputs.messages.reserve(roles.size() + (system_prompt.empty() ? 0U : 1U));
    if (!system_prompt.empty()) {
        common_chat_msg message;
        message.role = "system";
        message.content = system_prompt;
        inputs.messages.push_back(std::move(message));
    }
    for (std::size_t index = 0; index < roles.size(); ++index) {
        common_chat_msg message;
        message.role = roles[index];
        message.content = contents[index];
        inputs.messages.push_back(std::move(message));
    }

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(templates.get(), inputs).prompt;
    } catch (const std::exception&) {
        return error_response("CHAT_TEMPLATE_UNSUPPORTED", "The selected chat template could not be rendered");
    }
    if (prompt.empty()) {
        return error_response("CHAT_TEMPLATE_UNSUPPORTED", "The selected chat template rendered an empty prompt");
    }
    const std::vector<llama_token> tokens = tokenize_prompt(vocab, prompt);
    if (tokens.empty()) {
        return error_response("TOKENIZATION_FAILED", "Rendered prompt tokenization returned no tokens");
    }
    record.prepared_prompt_cache.store(prompt, tokens);
    return {
        "ok",
        base64_encode(prompt),
        std::to_string(tokens.size()),
        template_id,
        template_source,
        "",
    };
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_runtimeVersion(JNIEnv* env, jobject /* this */) {
    const std::string version = std::string(LOCAL_LLM_LLAMA_CPP_TAG) + " (" + LOCAL_LLM_LLAMA_CPP_COMMIT + ")";
    return env->NewStringUTF(version.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_isLlamaCppLinked(JNIEnv* /* env */, jobject /* this */) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_supportsMmap(JNIEnv* /* env */, jobject /* this */) {
    return llama_supports_mmap() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_initialize(
    JNIEnv* env,
    jobject /* this */,
    jstring native_library_dir
) {
    if (native_library_dir == nullptr) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Native library directory must not be null"));
    }

    const UtfChars path(env, native_library_dir);
    if (path.get() == nullptr) {
        return nullptr;
    }
    return to_java_string_array(env, backend_runtime.initialize(path.get()));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_shutdown(JNIEnv* env, jobject /* this */) {
    const bool resources_active = !models.empty() || !contexts.empty() ||
        active_generations.load(std::memory_order_acquire) != 0;
    return to_java_string_array(env, backend_runtime.shutdown(resources_active));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_loadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path,
    jint n_gpu_layers,
    jboolean use_mmap,
    jboolean use_mlock
) {
    if (!backend_runtime.initialized()) {
        return to_java_string_array(env, error_response("NOT_INITIALIZED", "Initialize the native runtime before loading a model"));
    }
    if (model_path == nullptr) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Model path must not be null"));
    }

    const UtfChars path(env, model_path);
    if (path.get() == nullptr) {
        return nullptr;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = n_gpu_layers;
    local_llm::apply_legacy_model_load_policy(
        params,
        use_mmap == JNI_TRUE,
        use_mlock == JNI_TRUE
    );

    const auto started_at = std::chrono::steady_clock::now();
    llama_model* raw_model = llama_model_load_from_file(path.get(), params);
    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started_at
    ).count();

    if (raw_model == nullptr) {
        return to_java_string_array(env, error_response("MODEL_LOAD_FAILED", "llama.cpp could not load the GGUF model"));
    }

    const std::int64_t handle = models.add(std::make_shared<ModelRecord>(raw_model, path.get()));
    if (handle == 0) {
        llama_model_free(raw_model);
        return to_java_string_array(env, error_response("INTERNAL", "Unable to allocate a native model handle"));
    }

    return to_java_string_array(env, {"ok", std::to_string(handle), std::to_string(load_ms)});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_unloadModel(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle
) {
    const auto model = models.get(static_cast<std::int64_t>(model_handle));
    if (!model) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }

    std::lock_guard<std::mutex> lock(model->lifecycle_mutex);
    if (!model->available) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    if (model->active_contexts != 0) {
        return to_java_string_array(env, error_response("MODEL_IN_USE", "Release all contexts before unloading the model"));
    }
    model->available = false;
    if (!models.remove(static_cast<std::int64_t>(model_handle))) {
        model->available = true;
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    return to_java_string_array(env, {"ok"});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaGenerationApi_createContext(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle,
    jint context_size,
    jint batch_size,
    jint micro_batch_size,
    jint threads,
    jint batch_threads,
    jint flash_attention_mode,
    jstring kv_cache_type_k_value,
    jstring kv_cache_type_v_value
) {
    const auto model = models.get(static_cast<std::int64_t>(model_handle));
    if (!model) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    if (context_size <= 0 || batch_size <= 0 || micro_batch_size <= 0 || threads <= 0 || batch_threads <= 0) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Context and thread parameters must be positive"));
    }

    std::string kv_cache_type_k;
    std::string kv_cache_type_v;
    const char* kv_cache_type_k_ptr = nullptr;
    const char* kv_cache_type_v_ptr = nullptr;
    if (kv_cache_type_k_value != nullptr) {
        const UtfChars value(env, kv_cache_type_k_value);
        if (value.get() == nullptr) {
            return nullptr;
        }
        kv_cache_type_k.assign(value.get());
        kv_cache_type_k_ptr = kv_cache_type_k.c_str();
    }
    if (kv_cache_type_v_value != nullptr) {
        const UtfChars value(env, kv_cache_type_v_value);
        if (value.get() == nullptr) {
            return nullptr;
        }
        kv_cache_type_v.assign(value.get());
        kv_cache_type_v_ptr = kv_cache_type_v.c_str();
    }

    std::lock_guard<std::mutex> lifecycle_lock(model->lifecycle_mutex);
    if (!model->available) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<std::uint32_t>(context_size);
    params.n_batch = static_cast<std::uint32_t>(batch_size);
    params.n_ubatch = static_cast<std::uint32_t>(micro_batch_size);
    params.n_threads = threads;
    params.n_threads_batch = batch_threads;
    if (!local_llm::apply_flash_attention_mode(params, flash_attention_mode)) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Unsupported flash-attention mode"));
    }
    if (!local_llm::apply_kv_cache_type_overrides(params, kv_cache_type_k_ptr, kv_cache_type_v_ptr)) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Unsupported K/V cache type"));
    }

    llama_context* raw_context = llama_init_from_model(model->model, params);
    if (raw_context == nullptr) {
        return to_java_string_array(env, error_response("CONTEXT_CREATE_FAILED", "llama.cpp could not create the context"));
    }

    ++model->active_contexts;
    const std::int64_t handle = contexts.add(
        std::make_shared<ContextRecord>(raw_context, model, batch_size)
    );
    if (handle == 0) {
        --model->active_contexts;
        llama_free(raw_context);
        return to_java_string_array(env, error_response("INTERNAL", "Unable to allocate a native context handle"));
    }
    return to_java_string_array(env, {"ok", std::to_string(handle)});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaGenerationApi_releaseContext(
    JNIEnv* env,
    jobject /* this */,
    jlong context_handle
) {
    if (!contexts.remove(static_cast<std::int64_t>(context_handle))) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native context handle"));
    }
    return to_java_string_array(env, {"ok"});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaPromptApi_modelCapabilities(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle
) {
    const auto model = models.get(static_cast<std::int64_t>(model_handle));
    if (!model) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    const std::int32_t maximum_context = llama_model_n_ctx_train(model->model);
    if (maximum_context <= 0) {
        return to_java_string_array(env, error_response("INTERNAL", "Model context capacity is unavailable"));
    }
    return to_java_string_array(env, {"ok", std::to_string(maximum_context), "true"});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaPromptApi_planPrompt(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle,
    jobjectArray roles_value,
    jobjectArray contents_value,
    jstring system_prompt_value,
    jstring application_template_id_value,
    jstring application_template_value,
    jstring family_template_id_value,
    jstring family_template_value,
    jstring raw_completion_value,
    jboolean enable_thinking
) {
    const auto model = models.get(static_cast<std::int64_t>(model_handle));
    if (!model) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native model handle"));
    }
    std::vector<std::string> roles;
    std::vector<std::string> contents;
    if (!read_java_string_array(env, roles_value, roles) ||
        !read_java_string_array(env, contents_value, contents)) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Prompt message arrays are invalid"));
    }

    auto read_optional = [env](jstring value, std::string& output) -> bool {
        if (value == nullptr) {
            return true;
        }
        const UtfChars chars(env, value);
        if (chars.get() == nullptr) {
            return false;
        }
        output.assign(chars.get());
        return true;
    };
    std::string system_prompt;
    std::string application_template_id;
    std::string application_template;
    std::string family_template_id;
    std::string family_template;
    std::string raw_completion;
    if (!read_optional(system_prompt_value, system_prompt) ||
        !read_optional(application_template_id_value, application_template_id) ||
        !read_optional(application_template_value, application_template) ||
        !read_optional(family_template_id_value, family_template_id) ||
        !read_optional(family_template_value, family_template) ||
        !read_optional(raw_completion_value, raw_completion)) {
        return nullptr;
    }
    return to_java_string_array(
        env,
        plan_prompt(
            *model,
            roles,
            contents,
            system_prompt,
            application_template_id,
            application_template,
            family_template_id,
            family_template,
            raw_completion,
            enable_thinking == JNI_TRUE
        )
    );
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaApi_inspectGguf(
    JNIEnv* env,
    jobject /* this */,
    jstring path_value
) {
    if (path_value == nullptr) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "GGUF path must not be null"));
    }

    const UtfChars path(env, path_value);
    if (path.get() == nullptr) {
        return nullptr;
    }

    return to_java_string_array(env, encode_inspection(local_llm::inspect_gguf_metadata(path.get())));
}

#include "native_cancellation_registry.h"
#include "generation_output_buffer.h"
#include "generation_sampler.h"
#include "reasoning_transition.h"

namespace {

NativeCancellationRegistry cancellations;

class CancellationScope final {
public:
    CancellationScope(NativeCancellationRegistry& registry, std::string request_id)
        : registry_(registry), request_id_(std::move(request_id)) {}

    ~CancellationScope() {
        registry_.finish(request_id_);
    }

    CancellationScope(const CancellationScope&) = delete;
    CancellationScope& operator=(const CancellationScope&) = delete;

private:
    NativeCancellationRegistry& registry_;
    std::string request_id_;
};

class JavaStreamingCallback final {
public:
    JavaStreamingCallback(JNIEnv* env, jobject callback) : env_(env), callback_(callback) {
        if (callback_ == nullptr) {
            return;
        }
        jclass callback_class = env_->GetObjectClass(callback_);
        if (callback_class == nullptr) {
            return;
        }
        method_ = env_->GetMethodID(callback_class, "onChunk", "(Ljava/lang/String;I)Z");
        env_->DeleteLocalRef(callback_class);
    }

    bool valid() const {
        return callback_ != nullptr && method_ != nullptr;
    }

    bool emit(const std::string& text, std::int32_t generated_tokens) {
        const std::string encoded = base64_encode(text);
        jstring payload = env_->NewStringUTF(encoded.c_str());
        if (payload == nullptr) {
            return false;
        }
        const jboolean accepted = env_->CallBooleanMethod(
            callback_,
            method_,
            payload,
            static_cast<jint>(generated_tokens)
        );
        env_->DeleteLocalRef(payload);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            return false;
        }
        return accepted == JNI_TRUE;
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID method_ = nullptr;
};

std::vector<std::string> terminal_response(
    const char* status,
    std::size_t input_tokens,
    std::int32_t output_tokens,
    std::int64_t prompt_ms,
    std::int64_t generation_ms,
    const std::string& stop_reason,
    std::int32_t reasoning_tokens,
    std::int32_t answer_tokens
) {
    return {
        status,
        std::to_string(input_tokens),
        std::to_string(output_tokens),
        std::to_string(prompt_ms),
        std::to_string(generation_ms),
        stop_reason,
        std::to_string(reasoning_tokens),
        std::to_string(answer_tokens),
    };
}

std::vector<std::string> cancelled_response(
    std::size_t input_tokens,
    std::int32_t output_tokens,
    std::int64_t prompt_ms,
    std::int64_t generation_ms,
    std::int32_t reasoning_tokens = -1,
    std::int32_t answer_tokens = -1
) {
    return terminal_response(
        "cancelled",
        input_tokens,
        output_tokens,
        prompt_ms,
        generation_ms,
        "UNKNOWN",
        reasoning_tokens,
        answer_tokens
    );
}

std::vector<std::string> read_java_stop_sequences(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> output;
    if (values != nullptr && !read_java_string_array(env, values, output)) {
        return {};
    }
    return output;
}

std::vector<llama_token> read_java_stop_tokens(JNIEnv* env, jintArray values) {
    if (values == nullptr) {
        return {};
    }
    const jsize count = env->GetArrayLength(values);
    std::vector<llama_token> output(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(values, 0, count, reinterpret_cast<jint*>(output.data()));
    return output;
}

bool contains_token(const std::vector<llama_token>& values, llama_token token) {
    return std::find(values.begin(), values.end(), token) != values.end();
}

std::vector<llama_token> tokenize_generated_text(const llama_vocab* vocab, const std::string& text) {
    if (text.empty() || text.size() > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
        return {};
    }
    const std::int32_t required = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<std::int32_t>(text.size()),
        nullptr,
        0,
        false,
        true
    );
    if (required == std::numeric_limits<std::int32_t>::min()) {
        return {};
    }
    const std::int32_t token_count = required < 0 ? -required : required;
    if (token_count <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<std::size_t>(token_count));
    const std::int32_t written = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<std::int32_t>(text.size()),
        tokens.data(),
        token_count,
        false,
        true
    );
    if (written <= 0) {
        return {};
    }
    tokens.resize(static_cast<std::size_t>(written));
    return tokens;
}

std::string grammar_for_constraint(const std::string& type, const std::string& schema) {
    if (type == "TEXT") {
        return {};
    }
    if (type == "JSON") {
        static const std::string json_schema = R"({"$schema":"http://json-schema.org/draft-07/schema#"})";
        return json_schema_to_grammar(common_json::parse(json_schema), true);
    }
    if (type == "JSON_SCHEMA") {
        return json_schema_to_grammar(common_json::parse(schema), true);
    }
    throw std::invalid_argument("Unsupported output constraint type");
}

std::vector<std::string> stream_text(
    ContextRecord& record,
    const std::string& request_id,
    const std::string& prompt,
    std::int32_t max_output_tokens,
    float temperature,
    float top_p,
    std::int32_t top_k,
    float min_p,
    float presence_penalty,
    float repeat_penalty,
    std::int32_t repeat_last_n,
    std::uint32_t seed,
    const std::string& output_constraint_type,
    const std::string& output_schema,
    const std::vector<llama_token>& stop_token_ids,
    const std::vector<std::string>& stop_sequences,
    std::int32_t max_reasoning_tokens,
    const std::string& reasoning_close_marker,
    const std::string& reasoning_forced_close_text,
    JavaStreamingCallback& callback
) {
    const auto cancellation = cancellations.begin(request_id);
    if (!cancellation) {
        return error_response("DUPLICATE_REQUEST", "A generation with this request ID is already active");
    }
    CancellationScope cancellation_scope(cancellations, request_id);
    std::lock_guard<std::mutex> generation_lock(record.generation_mutex);

    if (!callback.valid()) {
        return error_response("CALLBACK_FAILED", "Streaming callback does not expose onChunk(String, Int)");
    }
    if (max_output_tokens <= 0) {
        return error_response("INVALID_ARGUMENT", "Maximum output tokens must be positive");
    }
    if (llama_model_has_encoder(record.model->model)) {
        return error_response("UNSUPPORTED_MODEL", "Encoder-decoder models are not supported by this runtime path");
    }

    const bool reasoning_controlled = max_reasoning_tokens > 0;
    if (reasoning_controlled != (!reasoning_close_marker.empty() && !reasoning_forced_close_text.empty())) {
        return error_response("INVALID_ARGUMENT", "Reasoning transition configuration is incomplete");
    }
    if (reasoning_controlled && reasoning_forced_close_text.find(reasoning_close_marker) == std::string::npos) {
        return error_response("INVALID_ARGUMENT", "Forced reasoning close text must contain the close marker");
    }

    const llama_vocab* vocab = llama_model_get_vocab(record.model->model);
    std::vector<llama_token> forced_transition_tokens;
    if (reasoning_controlled) {
        forced_transition_tokens = tokenize_generated_text(vocab, reasoning_forced_close_text);
        if (forced_transition_tokens.empty()) {
            return error_response("TOKENIZATION_FAILED", "Reasoning close text tokenization returned no tokens");
        }
        if (max_reasoning_tokens >= max_output_tokens ||
            static_cast<std::size_t>(max_reasoning_tokens) + forced_transition_tokens.size() >=
                static_cast<std::size_t>(max_output_tokens)) {
            return error_response("INVALID_ARGUMENT", "Reasoning budget must reserve tokens for the final answer");
        }
    }

    std::string grammar;
    try {
        grammar = grammar_for_constraint(output_constraint_type, output_schema);
    } catch (const std::exception& error) {
        return error_response("INVALID_OUTPUT_CONSTRAINT", error.what());
    }
    auto sampler = create_generation_sampler(
        vocab,
        temperature,
        top_p,
        top_k,
        min_p,
        presence_penalty,
        repeat_penalty,
        repeat_last_n,
        seed,
        grammar
    );
    if (!sampler) {
        return error_response("SAMPLER_FAILED", "Unable to create the llama.cpp sampler chain");
    }
    auto prepared_prompt = record.model->prepared_prompt_cache.take(prompt);
    std::vector<llama_token> prompt_tokens = prepared_prompt.has_value()
        ? std::move(prepared_prompt.value())
        : tokenize_prompt(vocab, prompt);
    if (prompt_tokens.empty()) {
        return error_response("TOKENIZATION_FAILED", "Prompt tokenization returned no tokens");
    }
    if (prompt_tokens.size() + static_cast<std::size_t>(max_output_tokens) > llama_n_ctx(record.context)) {
        return error_response("CONTEXT_OVERFLOW", "Prompt and requested output exceed the configured context size");
    }

    ActiveGeneration active_generation;
    llama_memory_clear(llama_get_memory(record.context), true);
    const auto prompt_started = std::chrono::steady_clock::now();
    for (std::size_t offset = 0; offset < prompt_tokens.size();) {
        if (cancellation->load(std::memory_order_acquire)) {
            const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - prompt_started
            ).count();
            return cancelled_response(prompt_tokens.size(), 0, prompt_ms, 0);
        }
        const std::size_t remaining = prompt_tokens.size() - offset;
        const std::int32_t chunk_size = static_cast<std::int32_t>(
            std::min<std::size_t>(remaining, static_cast<std::size_t>(record.batch_size))
        );
        llama_batch prompt_batch = llama_batch_get_one(
            const_cast<llama_token*>(prompt_tokens.data() + offset),
            chunk_size
        );
        if (llama_decode(record.context, prompt_batch) != 0) {
            return error_response("DECODE_FAILED", "llama.cpp failed while processing the prompt");
        }
        offset += static_cast<std::size_t>(chunk_size);
    }
    const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - prompt_started
    ).count();

    std::string pending;
    std::size_t stop_lookbehind = 0;
    for (const std::string& sequence : stop_sequences) {
        stop_lookbehind = std::max(stop_lookbehind, sequence.size() > 0 ? sequence.size() - 1 : 0);
    }
    std::int32_t pending_tokens = 0;
    std::int32_t generated_tokens = 0;
    std::int32_t reasoning_boundary_tokens = -1;
    bool cancelled = false;
    bool stopped = false;
    std::string stop_reason = "MAX_OUTPUT_TOKENS";
    std::unique_ptr<ReasoningTransitionTracker> reasoning_tracker;
    if (reasoning_controlled) {
        reasoning_tracker = std::make_unique<ReasoningTransitionTracker>(reasoning_close_marker);
    }

    const auto record_reasoning_piece = [&](const std::string& piece) {
        if (!reasoning_tracker || reasoning_tracker->closed()) {
            return;
        }
        reasoning_tracker->observe(piece);
        if (reasoning_tracker->closed()) {
            reasoning_boundary_tokens = generated_tokens;
        }
    };

    const auto generation_started = std::chrono::steady_clock::now();
    while (generated_tokens < max_output_tokens) {
        if (cancellation->load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }

        llama_token token = llama_sampler_sample(sampler.get(), record.context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            stop_reason = grammar.empty() ? "END_OF_GENERATION" : "GRAMMAR_COMPLETE";
            break;
        }
        if (contains_token(stop_token_ids, token)) {
            stop_reason = "STOP_SEQUENCE";
            stopped = true;
            break;
        }

        const std::string piece = token_piece(vocab, token);
        if (piece.empty()) {
            return error_response("TOKEN_DECODE_FAILED", "Unable to decode a generated token");
        }
        pending.append(piece);
        ++pending_tokens;
        ++generated_tokens;
        llama_sampler_accept(sampler.get(), token);
        record_reasoning_piece(piece);

        const auto stop_position = earliest_stop_position(pending, stop_sequences);
        if (stop_position.has_value()) {
            pending.resize(stop_position.value());
            stop_reason = "STOP_SEQUENCE";
            stopped = true;
        }
        if (stopped) {
            break;
        }

        if (pending_tokens >= 8 || pending.size() >= 64) {
            const std::size_t desired_emit_size = pending.size() > stop_lookbehind
                ? pending.size() - stop_lookbehind
                : 0;
            const std::size_t emit_size = utf8_complete_prefix_size(pending, desired_emit_size);
            const std::string ready = pending.substr(0, emit_size);
            if (!ready.empty() && !callback.emit(ready, generated_tokens)) {
                cancellation->store(true, std::memory_order_release);
                cancelled = true;
                pending.clear();
                break;
            }
            pending.erase(0, emit_size);
            pending_tokens = 0;
        }

        llama_batch token_batch = llama_batch_get_one(&token, 1);
        if (generated_tokens < max_output_tokens && llama_decode(record.context, token_batch) != 0) {
            return error_response("DECODE_FAILED", "llama.cpp failed while decoding a generated token");
        }

        if (reasoning_tracker && !reasoning_tracker->closed() && generated_tokens >= max_reasoning_tokens) {
            for (const llama_token forced_token : forced_transition_tokens) {
                if (generated_tokens >= max_output_tokens) {
                    return error_response("INTERNAL", "Reasoning transition exhausted the output budget");
                }
                const std::string forced_piece = token_piece(vocab, forced_token);
                if (forced_piece.empty()) {
                    return error_response("TOKEN_DECODE_FAILED", "Unable to decode a forced reasoning transition token");
                }
                pending.append(forced_piece);
                ++pending_tokens;
                ++generated_tokens;
                llama_sampler_accept(sampler.get(), forced_token);
                record_reasoning_piece(forced_piece);

                llama_token forced_value = forced_token;
                llama_batch forced_batch = llama_batch_get_one(&forced_value, 1);
                if (llama_decode(record.context, forced_batch) != 0) {
                    return error_response("DECODE_FAILED", "llama.cpp failed while applying the reasoning transition");
                }
            }
            if (!reasoning_tracker->closed()) {
                return error_response("INTERNAL", "Forced reasoning transition did not emit the configured close marker");
            }
        }
    }

    if (!cancelled && !pending.empty()) {
        const std::size_t emit_size = utf8_complete_prefix_size(pending, pending.size());
        if (emit_size != pending.size()) {
            return error_response("TOKEN_DECODE_FAILED", "Generated output ended with incomplete UTF-8");
        }
        if (!callback.emit(pending, generated_tokens)) {
            cancellation->store(true, std::memory_order_release);
            cancelled = true;
        }
    }
    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - generation_started
    ).count();
    const std::int32_t answer_tokens = reasoning_boundary_tokens >= 0
        ? generated_tokens - reasoning_boundary_tokens
        : -1;

    if (cancelled) {
        return cancelled_response(
            prompt_tokens.size(),
            generated_tokens,
            prompt_ms,
            generation_ms,
            reasoning_boundary_tokens,
            answer_tokens
        );
    }
    return terminal_response(
        "ok",
        prompt_tokens.size(),
        generated_tokens,
        prompt_ms,
        generation_ms,
        stop_reason,
        reasoning_boundary_tokens,
        answer_tokens
    );
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaStreamingApi_generateStreaming(
    JNIEnv* env,
    jobject /* this */,
    jlong context_handle,
    jstring request_id_value,
    jstring prompt_value,
    jint max_output_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat min_p,
    jfloat presence_penalty,
    jfloat repeat_penalty,
    jint repeat_last_n,
    jlong seed,
    jstring output_constraint_type_value,
    jstring output_schema_value,
    jintArray stop_token_ids_value,
    jobjectArray stop_sequences_value,
    jint reasoning_max_tokens,
    jstring reasoning_close_marker_value,
    jstring reasoning_forced_close_text_value,
    jobject callback_value
) {
    const auto context = contexts.get(static_cast<std::int64_t>(context_handle));
    if (!context) {
        return to_java_string_array(env, error_response("UNKNOWN_HANDLE", "Unknown native context handle"));
    }
    if (request_id_value == nullptr || prompt_value == nullptr || output_constraint_type_value == nullptr || callback_value == nullptr) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Request ID, prompt and callback are required"));
    }

    const UtfChars request_id(env, request_id_value);
    const UtfChars prompt(env, prompt_value);
    const UtfChars output_constraint_type(env, output_constraint_type_value);
    if (request_id.get() == nullptr || prompt.get() == nullptr || output_constraint_type.get() == nullptr) {
        return nullptr;
    }

    std::string output_schema;
    if (output_schema_value != nullptr) {
        const UtfChars schema(env, output_schema_value);
        if (schema.get() == nullptr) {
            return nullptr;
        }
        output_schema.assign(schema.get());
    }
    std::string reasoning_close_marker;
    if (reasoning_close_marker_value != nullptr) {
        const UtfChars marker(env, reasoning_close_marker_value);
        if (marker.get() == nullptr) {
            return nullptr;
        }
        reasoning_close_marker.assign(marker.get());
    }
    std::string reasoning_forced_close_text;
    if (reasoning_forced_close_text_value != nullptr) {
        const UtfChars close_text(env, reasoning_forced_close_text_value);
        if (close_text.get() == nullptr) {
            return nullptr;
        }
        reasoning_forced_close_text.assign(close_text.get());
    }

    const std::vector<llama_token> stop_token_ids = read_java_stop_tokens(env, stop_token_ids_value);
    const std::vector<std::string> stop_sequences = read_java_stop_sequences(env, stop_sequences_value);
    if (request_id.get()[0] == '\0') {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Request ID must not be empty"));
    }

    JavaStreamingCallback callback(env, callback_value);
    return to_java_string_array(
        env,
        stream_text(
            *context,
            request_id.get(),
            prompt.get(),
            max_output_tokens,
            temperature,
            top_p,
            top_k,
            min_p,
            presence_penalty,
            repeat_penalty,
            repeat_last_n,
            static_cast<std::uint32_t>(seed),
            output_constraint_type.get(),
            output_schema,
            stop_token_ids,
            stop_sequences,
            reasoning_max_tokens,
            reasoning_close_marker,
            reasoning_forced_close_text,
            callback
        )
    );
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_daniele21_localllm_llamacpp_JniLlamaStreamingApi_cancel(
    JNIEnv* env,
    jobject /* this */,
    jstring request_id_value
) {
    if (request_id_value == nullptr) {
        return to_java_string_array(env, error_response("INVALID_ARGUMENT", "Request ID must not be null"));
    }
    const UtfChars request_id(env, request_id_value);
    if (request_id.get() == nullptr) {
        return nullptr;
    }
    const bool was_running = cancellations.cancel(request_id.get());
    return to_java_string_array(env, {"ok", was_running ? "true" : "false"});
}

// Evaluation-only JNI extension intentionally shares this translation unit
// with the stable JNI implementation so native model residency stays singular.
#include "evaluation_batch_jni.inc"
