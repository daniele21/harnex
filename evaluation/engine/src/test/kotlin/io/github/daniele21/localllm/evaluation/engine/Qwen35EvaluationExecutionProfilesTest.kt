package io.github.daniele21.localllm.evaluation.engine

import io.github.daniele21.localllm.models.Qwen35GenerationProfileId
import io.github.daniele21.localllm.models.Qwen35GenerationProfiles
import io.github.daniele21.localllm.models.Qwen35ModelTier
import io.github.daniele21.localllm.models.Qwen35RuntimeEvidenceStatus
import io.github.daniele21.localllm.models.Qwen35RuntimeTuningProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen35EvaluationExecutionProfilesTest {
    @Test
    fun `registry definitions are derived from source generation and runtime profiles`() {
        val tier = Qwen35ModelTier.B0_8
        val sourceGeneration = Qwen35GenerationProfiles.forTier(tier)
        val sourceRuntime = Qwen35RuntimeTuningProfiles.candidateForTier(tier).resolve(8)

        val definitions = Qwen35EvaluationExecutionProfiles.forTier(tier, availableProcessors = 8)

        assertEquals(sourceGeneration.size, definitions.size)
        definitions.zip(sourceGeneration).forEach { (definition, source) ->
            assertEquals(source.id.name.lowercase(), definition.ref.id.value)
            assertEquals(source.version, definition.ref.version)
            assertEquals(source.defaults, definition.generation)
            assertEquals(sourceRuntime, definition.runtimeTuning)
            assertEquals(Qwen35RuntimeEvidenceStatus.CANDIDATE, definition.evidenceStatus)
        }
    }

    @Test
    fun `thinking and non-thinking options preserve canonical source semantics`() {
        val definitions = Qwen35EvaluationExecutionProfiles.forTier(Qwen35ModelTier.B2, availableProcessors = 4)
        val byId = definitions.associateBy { it.ref.id.value }

        val textQuality = requireNotNull(byId[Qwen35GenerationProfileId.QWEN35_TEXT_QUALITY.name.lowercase()])
        val thinking = requireNotNull(byId[Qwen35GenerationProfileId.QWEN35_THINKING.name.lowercase()])

        assertEquals(
            Qwen35GenerationProfiles.forTier(Qwen35ModelTier.B2)
                .single { it.id == Qwen35GenerationProfileId.QWEN35_TEXT_QUALITY }
                .defaults,
            textQuality.generation,
        )
        assertEquals(
            Qwen35GenerationProfiles.forTier(Qwen35ModelTier.B2)
                .single { it.id == Qwen35GenerationProfileId.QWEN35_THINKING }
                .defaults,
            thinking.generation,
        )
        assertTrue(textQuality.description.contains("runtime evidence candidate"))
        assertTrue(thinking.description.contains("runtime evidence candidate"))
    }
}
