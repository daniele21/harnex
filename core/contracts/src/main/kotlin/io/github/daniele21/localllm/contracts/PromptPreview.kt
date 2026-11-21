package io.github.daniele21.localllm.contracts

/**
 * Exact model/template token planning only. Fits is not memory/thermal admission;
 * no rendered prompt, model internals, native context or private text is returned.
 */
sealed interface PromptPreviewResult {
    data class Fits(
        val promptTokens: Int,
        val contextTokens: Int,
        val maxOutputTokens: Int,
        val chatTemplateSource: ChatTemplateSource,
    ) : PromptPreviewResult {
        init {
            require(promptTokens > 0 && maxOutputTokens > 0)
            require(contextTokens >= promptTokens + maxOutputTokens)
        }
    }

    data class Rejected(val error: LocalLlmError) : PromptPreviewResult
}
