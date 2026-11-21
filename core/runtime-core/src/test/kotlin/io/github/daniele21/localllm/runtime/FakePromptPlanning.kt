package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.GenerationInput

internal fun fakePromptPlan(request: BackendPromptPlanningRequest): BackendPromptPlan {
    val content = when (val input = request.input) {
        is GenerationInput.ImageQuestion -> "<__media__>${input.question}"
        is GenerationInput.Text -> input.value
        is GenerationInput.RawCompletion -> input.value
        is GenerationInput.Messages -> input.values.joinToString("\n") { "${it.role.name.lowercase()}: ${it.content}" }
    }
    val prompt = listOfNotNull(request.systemPrompt, content).joinToString("\n")
    return BackendPromptPlan(
        prompt = prompt,
        tokenCount = prompt.split(Regex("\\s+")).count(String::isNotBlank).coerceAtLeast(1),
        chatTemplateId = "fake-template-v1",
        chatTemplateSource = ChatTemplateSource.FAMILY_FALLBACK,
        imageBytes = (request.input as? GenerationInput.ImageQuestion)?.copyImageBytes(),
    )
}
