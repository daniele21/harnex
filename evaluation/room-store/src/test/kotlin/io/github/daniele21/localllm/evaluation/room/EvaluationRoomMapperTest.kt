package io.github.daniele21.localllm.evaluation.room

import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.evaluation.EvaluationCaseId
import io.github.daniele21.localllm.evaluation.EvaluationCaseMetrics
import io.github.daniele21.localllm.evaluation.EvaluationCaseResult
import io.github.daniele21.localllm.evaluation.EvaluationCaseStatus
import io.github.daniele21.localllm.evaluation.EvaluationCategoryId
import io.github.daniele21.localllm.evaluation.EvaluationDatasetDigest
import io.github.daniele21.localllm.evaluation.EvaluationDatasetId
import io.github.daniele21.localllm.evaluation.EvaluationDatasetIdentity
import io.github.daniele21.localllm.evaluation.EvaluationDatasetVersion
import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileId
import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileRef
import io.github.daniele21.localllm.evaluation.EvaluationModelIdentity
import io.github.daniele21.localllm.evaluation.EvaluationModelLoadPolicy
import io.github.daniele21.localllm.evaluation.EvaluationOutcome
import io.github.daniele21.localllm.evaluation.EvaluationProgress
import io.github.daniele21.localllm.evaluation.EvaluationQualitySummary
import io.github.daniele21.localllm.evaluation.EvaluationRunConfig
import io.github.daniele21.localllm.evaluation.EvaluationRunId
import io.github.daniele21.localllm.evaluation.EvaluationRunState
import io.github.daniele21.localllm.evaluation.EvaluationRunSummary
import io.github.daniele21.localllm.evaluation.EvaluationWarmupPolicy
import io.github.daniele21.localllm.evaluation.EvaluatorOutcomeCode
import io.github.daniele21.localllm.evaluation.EvaluatorSpec
import io.github.daniele21.localllm.evaluation.EvaluatorType
import io.github.daniele21.localllm.evaluation.EvaluatorVersion
import io.github.daniele21.localllm.evaluation.NormalizedScore
import io.github.daniele21.localllm.evaluation.SamplingPolicyId
import io.github.daniele21.localllm.evaluation.SamplingPolicyRef
import io.github.daniele21.localllm.evaluation.SamplingSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationRoomMapperTest {
    @Test
    fun `round trip preserves optional empty quality and sample order`() {
        val summary = summary()
        val stored = EvaluationStoredRun(
            EvaluationRoomMapper.runEntity(summary),
            EvaluationRoomMapper.sampleEntities(summary),
            EvaluationRoomMapper.categoryScoreEntities(summary),
            emptyList(),
            emptyList(),
        )

        val restored = EvaluationRoomMapper.summary(stored)

        assertEquals(summary, restored)
        assertTrue(stored.run.qualityPresent)
        assertEquals(listOf("case-b", "case-a"), stored.samples.map { it.caseId })
    }

    @Test
    fun `case result round trip preserves only privacy safe result fields`() {
        val summary = summary()
        val result = EvaluationCaseResult(
            caseId = CASE_B,
            categoryId = EvaluationCategoryId("general"),
            evaluator = EvaluatorSpec(
                EvaluatorType.EXACT_MATCH,
                EvaluatorVersion(1),
                mapOf("case" to "sensitive"),
            ),
            status = EvaluationCaseStatus.SCORED,
            outcome = EvaluationOutcome(NormalizedScore(1.0), EvaluatorOutcomeCode.CORRECT),
            requestId = RequestId("request-1"),
            metrics = EvaluationCaseMetrics(totalMs = 42, outputTokens = 3),
        )
        val stored = EvaluationStoredRun(
            EvaluationRoomMapper.runEntity(summary),
            EvaluationRoomMapper.sampleEntities(summary),
            EvaluationRoomMapper.categoryScoreEntities(summary),
            listOf(EvaluationRoomMapper.caseResultEntity(RUN_ID, result)),
            EvaluationRoomMapper.evaluatorParameterEntities(RUN_ID, result),
        )

        val restored = EvaluationRoomMapper.persistedRun(stored)

        assertEquals(listOf(result), restored.caseResults)
        assertEquals(summary, restored.summary)
    }

    private fun summary(): EvaluationRunSummary = EvaluationRunSummary(
        runId = RUN_ID,
        config = CONFIG,
        identity = null,
        state = EvaluationRunState.CREATED,
        progress = EvaluationProgress(totalCases = 2, attemptedCases = 0, completedCases = 0),
        quality = EvaluationQualitySummary(aggregateScore = null, categoryScores = emptyList()),
        reliability = null,
        startedAtEpochMs = 100,
        completedAtEpochMs = null,
        failure = null,
    )

    private companion object {
        val RUN_ID = EvaluationRunId("run-1")
        val CASE_A = EvaluationCaseId("case-a")
        val CASE_B = EvaluationCaseId("case-b")
        val DATASET = EvaluationDatasetIdentity(
            EvaluationDatasetId("fixture"),
            EvaluationDatasetVersion("1"),
            EvaluationDatasetDigest("1".repeat(64)),
        )
        val CONFIG = EvaluationRunConfig(
            runId = RUN_ID,
            model = EvaluationModelIdentity(ModelDigest("a".repeat(64)), "model-profile", quantization = "Q4_K_M"),
            dataset = DATASET,
            sampling = SamplingSelection.create(
                dataset = DATASET,
                policy = SamplingPolicyRef(SamplingPolicyId("fixed"), 1),
                seed = 7,
                orderedCaseIds = listOf(CASE_B, CASE_A),
            ),
            executionProfile = EvaluationExecutionProfileRef(EvaluationExecutionProfileId("direct"), 1),
            loadPolicy = EvaluationModelLoadPolicy.PRESERVE_CURRENT_RESIDENCY,
            warmupPolicy = EvaluationWarmupPolicy.NONE,
            caseTimeoutMs = 30_000,
        )
    }
}
