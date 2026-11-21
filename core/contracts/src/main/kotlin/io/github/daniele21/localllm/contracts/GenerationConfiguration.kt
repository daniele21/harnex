package io.github.daniele21.localllm.contracts

const val MAX_NATIVE_SEED: Long = 0xffff_ffffL

@JvmInline
value class InferencePresetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Inference preset ID must not be blank" }
        require(value.length <= 64) { "Inference preset ID must not exceed 64 characters" }
    }
}

data class InferencePresetRef(val id: InferencePresetId, val version: Int) {
    init {
        require(version > 0) { "Inference preset version must be positive" }
    }
}

sealed interface SeedPolicy {
    data object Random : SeedPolicy

    data class Fixed(val value: Long) : SeedPolicy {
        init {
            require(value in 0..MAX_NATIVE_SEED) {
                "Fixed seed must be between 0 and $MAX_NATIVE_SEED"
            }
        }
    }
}

enum class ThinkingMode {
    ENABLED,
    DISABLED,
}

enum class SeedPolicyType {
    RANDOM,
    FIXED,
}

sealed interface ContextPolicy {
    data object Auto : ContextPolicy

    data class Manual(val tokens: Int) : ContextPolicy {
        init {
            require(tokens > 0) { "Manual context size must be positive" }
        }
    }
}

enum class SessionKind {
    STATELESS,
    CONVERSATIONAL,
}

data class SessionOptions(val contextPolicy: ContextPolicy = ContextPolicy.Auto, val kind: SessionKind = SessionKind.STATELESS)

enum class ConversationRole {
    USER,
    ASSISTANT,
}

data class ConversationMessage(val role: ConversationRole, val content: String) {
    init {
        require(content.isNotBlank()) { "Conversation message must not be blank" }
        require('\u0000' !in content) { "Conversation message must not contain NUL" }
        require(content.length <= MAX_GENERATION_INPUT_CHARACTERS) {
            "Conversation message exceeds $MAX_GENERATION_INPUT_CHARACTERS characters"
        }
    }
}

sealed interface GenerationInput {
    data class Text(val value: String) : GenerationInput {
        init {
            validateInput(value)
        }
    }

    data class Messages(val values: List<ConversationMessage>) : GenerationInput {
        init {
            require(values.isNotEmpty()) { "Conversation messages must not be empty" }
            require(values.size <= MAX_CONVERSATION_MESSAGES) {
                "Conversation messages must not exceed $MAX_CONVERSATION_MESSAGES entries"
            }
            require(values.sumOf { it.content.length } <= MAX_GENERATION_INPUT_CHARACTERS) {
                "Conversation messages exceed $MAX_GENERATION_INPUT_CHARACTERS total characters"
            }
        }
    }

    /**
     * Experimental embedded-only image question. Image bytes are bounded compressed PNG/JPEG
     * payloads; Shared Host/Binder serialization deliberately rejects this input type.
     * Each request carries one photo and one question, with no implicit chat history.
     */
    class ImageQuestion(val question: String, imageBytes: ByteArray) : GenerationInput {
        private val data: ByteArray = imageBytes.copyOf()

        init {
            validateInput(question)
            require(data.isNotEmpty() && data.size <= MAX_IMAGE_INPUT_BYTES) {
                "Image input must be between 1 and $MAX_IMAGE_INPUT_BYTES bytes"
            }
        }

        fun copyImageBytes(): ByteArray = data.copyOf()
    }

    data class RawCompletion(val value: String) : GenerationInput {
        init {
            validateInput(value)
        }
    }
}

sealed interface OutputConstraint {
    data object Text : OutputConstraint
    data object Json : OutputConstraint

    data class JsonSchema(val schema: String) : OutputConstraint {
        init {
            require(schema.isNotBlank()) { "JSON schema must not be blank" }
            require('\u0000' !in schema) { "JSON schema must not contain NUL" }
            require(schema.length <= MAX_JSON_SCHEMA_CHARACTERS) {
                "JSON schema exceeds $MAX_JSON_SCHEMA_CHARACTERS characters"
            }
        }
    }
}

enum class ChatTemplateSource {
    APPLICATION_OVERRIDE,
    GGUF,
    FAMILY_FALLBACK,
    RAW_COMPLETION,
}

enum class StopReason {
    END_OF_GENERATION,
    MAX_OUTPUT_TOKENS,
    STOP_SEQUENCE,
    GRAMMAR_COMPLETE,
    GENERATION_GUARD_REPETITION,
    GENERATION_GUARD_THINKING_BUDGET,
    UNKNOWN,
}

data class EffectiveGenerationMetadata(
    val preset: InferencePresetRef?,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val requestedSeedPolicy: SeedPolicyType,
    val effectiveSeed: Long,
    val maxOutputTokens: Int,
    val contextSize: Int,
    val promptTokenCount: Int,
    val chatTemplateId: String,
    val chatTemplateSource: ChatTemplateSource,
    val systemPromptVersion: String?,
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    val minP: Float = 0f,
    val presencePenalty: Float = 0f,
) {
    init {
        require(chatTemplateId.isNotBlank()) { "Chat template ID must not be blank" }
        require(chatTemplateId.length <= 128) { "Chat template ID must not exceed 128 characters" }
        require(systemPromptVersion == null || systemPromptVersion.length <= 128) {
            "System prompt version must not exceed 128 characters"
        }
        require(minP.isFinite() && minP in 0f..1f) { "Min-p must be in [0, 1]" }
        require(presencePenalty.isFinite() && presencePenalty in 0f..2f) {
            "Presence penalty must be in [0, 2]"
        }
    }
}

private fun validateInput(value: String) {
    require(value.isNotBlank()) { "Generation input must not be blank" }
    require('\u0000' !in value) { "Generation input must not contain NUL" }
    require(value.length <= MAX_GENERATION_INPUT_CHARACTERS) {
        "Generation input exceeds $MAX_GENERATION_INPUT_CHARACTERS characters"
    }
}

private const val MAX_IMAGE_INPUT_BYTES = 2 * 1024 * 1024
private const val MAX_CONVERSATION_MESSAGES = 128
private const val MAX_GENERATION_INPUT_CHARACTERS = 32_768
private const val MAX_JSON_SCHEMA_CHARACTERS = 32_768
