package io.github.daniele21.localllm.embedded

import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.ConversationRole
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.models.OutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedModelPolicyTest {
    @Test
    fun mapsTheSingleReviewedModelToStatelessTextInference() {
        val resolved =
            EmbeddedModelPolicy.resolve(
                EmbeddedConfiguration(
                    modelId = EmbeddedModelPolicy.INITIAL_MODEL_ID,
                    systemInstructions = "Only answer questions in Italian.",
                ),
                availableProcessors = 16,
            )

        assertEquals("harnex-embedded", resolved.binding.applicationId.value)
        assertEquals("local-text-generation", resolved.binding.useCaseId.value)
        assertEquals("qwen35", resolved.model.artifact.architecture)
        assertEquals("Q4_K_M", resolved.model.artifact.quantization)
        assertEquals(4, resolved.model.cpuThreads)
        assertEquals(OutputMode.TEXT, resolved.useCase.outputMode)
        assertEquals("Only answer questions in Italian.", resolved.useCase.systemPrompt)
        assertFalse(resolved.useCase.cachePolicy.reuseStatelessContext)
        assertFalse(resolved.useCase.cachePolicy.enableDeterministicResultCache)
    }

    @Test
    fun rejectsUnsupportedModelsAndUnboundedInstructionsWithoutFallback() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedModelPolicy.resolve(EmbeddedConfiguration("qwen35-4b-q4-k-m"), 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedConfiguration(EmbeddedModelPolicy.INITIAL_MODEL_ID, "x".repeat(4_097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedConfiguration(EmbeddedModelPolicy.INITIAL_MODEL_ID, "a\u0000b")
        }
    }

    @Test
    fun passesExplicitHistoryAsBoundedTypedModelInput() {
        val history =
            listOf(
                ConversationMessage(ConversationRole.USER, "My name is Mario"),
                ConversationMessage(ConversationRole.ASSISTANT, "Hello Mario"),
                ConversationMessage(ConversationRole.USER, "What is my name?"),
            )

        val input = GenerationInput.Messages(history)

        assertEquals(history, input.values)
        assertTrue(input.values.last().role == ConversationRole.USER)
        assertThrows(IllegalArgumentException::class.java) {
            GenerationInput.Messages(emptyList())
        }
    }
}
