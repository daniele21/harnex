package io.github.daniele21.localllm.llamacpp

import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.ConversationRole
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.models.ChatTemplatePolicy
import java.util.Base64

interface NativeLlamaPromptApi {
    fun modelCapabilities(modelHandle: Long): Array<String>

    @Suppress("LongParameterList")
    fun planPrompt(
        modelHandle: Long,
        roles: Array<String>,
        contents: Array<String>,
        systemPrompt: String?,
        applicationTemplateId: String?,
        applicationTemplate: String?,
        familyTemplateId: String?,
        familyTemplate: String?,
        rawCompletion: String?,
        enableThinking: Boolean,
    ): Array<String>
}

class JniLlamaPromptApi : NativeLlamaPromptApi {
    init {
        System.loadLibrary("local_llm_jni")
    }

    external override fun modelCapabilities(modelHandle: Long): Array<String>

    @Suppress("LongParameterList")
    external override fun planPrompt(
        modelHandle: Long,
        roles: Array<String>,
        contents: Array<String>,
        systemPrompt: String?,
        applicationTemplateId: String?,
        applicationTemplate: String?,
        familyTemplateId: String?,
        familyTemplate: String?,
        rawCompletion: String?,
        enableThinking: Boolean,
    ): Array<String>
}

class LlamaCppPromptPlanningBridge(private val nativeApi: NativeLlamaPromptApi = JniLlamaPromptApi()) {
    fun capabilities(model: LoadedNativeModel): NativeModelCapabilitiesResult = decodeCapabilities(
        nativeApi.modelCapabilities(model.handle.value),
    )

    fun plan(
        model: LoadedNativeModel,
        input: GenerationInput,
        systemPrompt: String?,
        policy: ChatTemplatePolicy,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    ): NativePromptPlanningResult {
        val messages = input.messages()
        val response = nativeApi.planPrompt(
            modelHandle = model.handle.value,
            roles = messages.map { it.first }.toTypedArray(),
            contents = messages.map { it.second }.toTypedArray(),
            systemPrompt = systemPrompt,
            applicationTemplateId = policy.applicationOverrideId,
            applicationTemplate = policy.applicationOverride,
            familyTemplateId = policy.familyFallbackId,
            familyTemplate = policy.familyFallback,
            rawCompletion = (input as? GenerationInput.RawCompletion)?.value,
            enableThinking = thinkingMode == ThinkingMode.ENABLED,
        )
        if (response.size == PROMPT_FIELD_COUNT && response[0] == OK) {
            return try {
                NativePromptPlanningResult.Success(
                    NativePromptPlan(
                        prompt = String(Base64.getDecoder().decode(response[1]), Charsets.UTF_8),
                        tokenCount = response[2].toInt(),
                        chatTemplateId = response[3],
                        chatTemplateSource = ChatTemplateSource.valueOf(response[4]),
                        stopTokenIds = response[5].split(',')
                            .filter(String::isNotBlank)
                            .map(String::toInt)
                            .toSet(),
                    ),
                )
            } catch (error: IllegalArgumentException) {
                NativePromptPlanningResult.Failure(protocolError("Native prompt plan is invalid: ${error.message}"))
            }
        }
        return NativePromptPlanningResult.Failure(decodeError(response))
    }

    private fun decodeCapabilities(response: Array<String>): NativeModelCapabilitiesResult {
        if (response.size == CAPABILITIES_FIELD_COUNT && response[0] == OK) {
            return try {
                NativeModelCapabilitiesResult.Success(
                    NativeModelCapabilities(
                        maximumContextTokens = response[1].toInt(),
                        supportsGrammar = response[2].toBooleanStrict(),
                    ),
                )
            } catch (error: IllegalArgumentException) {
                NativeModelCapabilitiesResult.Failure(protocolError("Native model capabilities are invalid: ${error.message}"))
            }
        }
        return NativeModelCapabilitiesResult.Failure(decodeError(response))
    }

    private fun decodeError(response: Array<String>): PromptPlanningNativeError {
        if (response.size != ERROR_FIELD_COUNT || response[0] != ERROR) {
            return protocolError("Malformed native prompt response: ${response.joinToString("|")}")
        }
        val code = PromptPlanningNativeErrorCode.entries.firstOrNull { it.name == response[1] }
            ?: PromptPlanningNativeErrorCode.NATIVE_PROTOCOL
        return PromptPlanningNativeError(code, response[2])
    }

    private fun protocolError(message: String) = PromptPlanningNativeError(PromptPlanningNativeErrorCode.NATIVE_PROTOCOL, message)

    private fun GenerationInput.messages(): List<Pair<String, String>> = when (this) {
        is GenerationInput.Text -> listOf("user" to value)

        is GenerationInput.Messages -> values.map { message ->
            when (message.role) {
                ConversationRole.USER -> "user"
                ConversationRole.ASSISTANT -> "assistant"
            } to message.content
        }

        is GenerationInput.ImageQuestion -> listOf("user" to "<__media__>" + question)

        is GenerationInput.RawCompletion -> emptyList()
    }

    private companion object {
        const val OK = "ok"
        const val ERROR = "error"
        const val CAPABILITIES_FIELD_COUNT = 3
        const val PROMPT_FIELD_COUNT = 6
        const val ERROR_FIELD_COUNT = 3
    }
}

data class NativeModelCapabilities(val maximumContextTokens: Int, val supportsGrammar: Boolean)

sealed interface NativeModelCapabilitiesResult {
    data class Success(val capabilities: NativeModelCapabilities) : NativeModelCapabilitiesResult
    data class Failure(val error: PromptPlanningNativeError) : NativeModelCapabilitiesResult
}

data class NativePromptPlan(
    val prompt: String,
    val tokenCount: Int,
    val chatTemplateId: String,
    val chatTemplateSource: ChatTemplateSource,
    val stopTokenIds: Set<Int>,
)

sealed interface NativePromptPlanningResult {
    data class Success(val plan: NativePromptPlan) : NativePromptPlanningResult
    data class Failure(val error: PromptPlanningNativeError) : NativePromptPlanningResult
}

data class PromptPlanningNativeError(val code: PromptPlanningNativeErrorCode, val message: String)

enum class PromptPlanningNativeErrorCode {
    INVALID_ARGUMENT,
    UNKNOWN_HANDLE,
    CHAT_TEMPLATE_UNAVAILABLE,
    CHAT_TEMPLATE_UNSUPPORTED,
    TOKENIZATION_FAILED,
    INTERNAL,
    NATIVE_PROTOCOL,
}
