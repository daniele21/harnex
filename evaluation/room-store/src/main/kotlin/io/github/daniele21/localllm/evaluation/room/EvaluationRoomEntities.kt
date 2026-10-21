package io.github.daniele21.localllm.evaluation.room

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "evaluation_runs",
    primaryKeys = ["run_id"],
    indices = [
        Index(value = ["started_at_epoch_ms"]),
        Index(value = ["state", "started_at_epoch_ms"]),
        Index(value = ["config_dataset_id", "started_at_epoch_ms"]),
        Index(value = ["config_model_digest", "started_at_epoch_ms"]),
    ],
)
data class EvaluationRunEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @Embedded(prefix = "config_") val config: EvaluationRunConfigEntity,
    @Embedded(prefix = "identity_") val identity: EvaluationRunIdentityEntity?,
    val state: String,
    @Embedded(prefix = "progress_") val progress: EvaluationProgressEntity,
    @ColumnInfo(name = "quality_present") val qualityPresent: Boolean,
    @ColumnInfo(name = "quality_aggregate_score") val qualityAggregateScore: Double?,
    @Embedded(prefix = "reliability_") val reliability: EvaluationReliabilityEntity?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long?,
    @Embedded(prefix = "failure_") val failure: EvaluationFailureEntity?,
)

data class EvaluationRunConfigEntity(
    @ColumnInfo(name = "model_digest") val modelDigest: String,
    @ColumnInfo(name = "model_profile_id") val modelProfileId: String,
    @ColumnInfo(name = "model_tier") val modelTier: String?,
    @ColumnInfo(name = "model_quantization") val modelQuantization: String?,
    @ColumnInfo(name = "dataset_id") val datasetId: String,
    @ColumnInfo(name = "dataset_version") val datasetVersion: String,
    @ColumnInfo(name = "dataset_digest") val datasetDigest: String,
    @ColumnInfo(name = "sample_set_digest") val sampleSetDigest: String,
    @ColumnInfo(name = "sampling_policy_id") val samplingPolicyId: String,
    @ColumnInfo(name = "sampling_policy_version") val samplingPolicyVersion: Int,
    @ColumnInfo(name = "sampling_seed") val samplingSeed: Long,
    @ColumnInfo(name = "execution_profile_id") val executionProfileId: String,
    @ColumnInfo(name = "execution_profile_version") val executionProfileVersion: Int,
    @ColumnInfo(name = "load_policy") val loadPolicy: String,
    @ColumnInfo(name = "warmup_policy") val warmupPolicy: String,
    @ColumnInfo(name = "case_timeout_ms") val caseTimeoutMs: Long,
)

data class EvaluationRunIdentityEntity(
    @ColumnInfo(name = "evaluator_set_digest") val evaluatorSetDigest: String,
    @ColumnInfo(name = "semantic_execution_fingerprint") val semanticExecutionFingerprint: String,
    @ColumnInfo(name = "run_fingerprint") val runFingerprint: String,
    @Embedded(prefix = "semantic_") val semantic: EvaluationSemanticExecutionEntity,
    @Embedded(prefix = "runtime_") val runtime: EvaluationRuntimeEnvironmentEntity,
)

@Suppress("LongParameterList")
data class EvaluationSemanticExecutionEntity(
    val semanticsVersion: Int,
    val backendRevision: String,
    val contextSize: Int,
    val presetId: String?,
    val presetVersion: Int?,
    val thinkingMode: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val presencePenalty: Float,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val seedPolicy: String,
    val effectiveSeed: Long,
    val maxOutputTokens: Int,
    val chatTemplateId: String,
    val chatTemplateSource: String,
    val systemPromptVersion: String?,
    val caseExecutionSemanticsDigest: String,
)

data class EvaluationRuntimeEnvironmentEntity(
    val deviceClass: String,
    val androidApiLevel: Int,
    val abi: String,
    val backendRevision: String,
    val harnessBuildIdentity: String,
    val runtimeTuningProfileId: String,
    val runtimeTuningProfileVersion: Int,
    val loadPolicy: String,
    val warmupPolicy: String,
)

data class EvaluationProgressEntity(val totalCases: Int, val attemptedCases: Int, val completedCases: Int, val currentCaseId: String?)

data class EvaluationReliabilityEntity(
    val totalCases: Int,
    val completedAndScored: Int,
    val incorrectButValid: Int,
    val invalidOutput: Int,
    val timeout: Int,
    val runtimeFailure: Int,
    val cancelled: Int,
    val skipped: Int,
)

data class EvaluationFailureEntity(val stage: String, val code: String, val caseId: String?, val retryable: Boolean)

@Entity(
    tableName = "evaluation_sample_cases",
    primaryKeys = ["run_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = EvaluationRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id"]), Index(value = ["run_id", "case_id"], unique = true)],
)
data class EvaluationSampleCaseEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    val ordinal: Int,
    @ColumnInfo(name = "case_id") val caseId: String,
)

@Entity(
    tableName = "evaluation_category_scores",
    primaryKeys = ["run_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = EvaluationRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id"]), Index(value = ["run_id", "ordinal"], unique = true)],
)
data class EvaluationCategoryScoreEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    val ordinal: Int,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val score: Double,
    @ColumnInfo(name = "scored_case_count") val scoredCaseCount: Int,
    val weight: Double?,
)

@Entity(
    tableName = "evaluation_case_results",
    primaryKeys = ["run_id", "case_id"],
    foreignKeys = [
        ForeignKey(
            entity = EvaluationSampleCaseEntity::class,
            parentColumns = ["run_id", "case_id"],
            childColumns = ["run_id", "case_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id"]), Index(value = ["request_id"])],
)
@Suppress("LongParameterList")
data class EvaluationCaseResultEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "case_id") val caseId: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "evaluator_type") val evaluatorType: String,
    @ColumnInfo(name = "evaluator_version") val evaluatorVersion: Int,
    val status: String,
    @ColumnInfo(name = "outcome_score") val outcomeScore: Double?,
    @ColumnInfo(name = "outcome_code") val outcomeCode: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "time_to_first_token_ms") val timeToFirstTokenMs: Long?,
    @ColumnInfo(name = "total_ms") val totalMs: Long?,
    @ColumnInfo(name = "prefill_ms") val prefillMs: Long?,
    @ColumnInfo(name = "decode_ms") val decodeMs: Long?,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int?,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int?,
    @ColumnInfo(name = "decode_tokens_per_second") val decodeTokensPerSecond: Double?,
    @ColumnInfo(name = "process_pss_bytes") val processPssBytes: Long?,
    @ColumnInfo(name = "available_memory_bytes") val availableMemoryBytes: Long?,
    @ColumnInfo(name = "thermal_status") val thermalStatus: String?,
    @Embedded(prefix = "failure_") val failure: EvaluationFailureEntity?,
)

@Entity(
    tableName = "evaluation_evaluator_parameters",
    primaryKeys = ["run_id", "case_id", "parameter_key"],
    foreignKeys = [
        ForeignKey(
            entity = EvaluationCaseResultEntity::class,
            parentColumns = ["run_id", "case_id"],
            childColumns = ["run_id", "case_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id", "case_id"])],
)
data class EvaluationEvaluatorParameterEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "case_id") val caseId: String,
    @ColumnInfo(name = "parameter_key") val parameterKey: String,
    @ColumnInfo(name = "parameter_value") val parameterValue: String,
)
