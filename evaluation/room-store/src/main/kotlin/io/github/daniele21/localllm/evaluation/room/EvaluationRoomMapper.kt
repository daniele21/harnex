package io.github.daniele21.localllm.evaluation.room

import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SeedPolicyType
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.evaluation.CaseExecutionSemanticsDigest
import io.github.daniele21.localllm.evaluation.EvaluationCaseId
import io.github.daniele21.localllm.evaluation.EvaluationCaseMetrics
import io.github.daniele21.localllm.evaluation.EvaluationCaseResult
import io.github.daniele21.localllm.evaluation.EvaluationCaseStatus
import io.github.daniele21.localllm.evaluation.EvaluationCategoryId
import io.github.daniele21.localllm.evaluation.EvaluationCategoryScore
import io.github.daniele21.localllm.evaluation.EvaluationDatasetDigest
import io.github.daniele21.localllm.evaluation.EvaluationDatasetId
import io.github.daniele21.localllm.evaluation.EvaluationDatasetIdentity
import io.github.daniele21.localllm.evaluation.EvaluationDatasetVersion
import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileId
import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileRef
import io.github.daniele21.localllm.evaluation.EvaluationFailure
import io.github.daniele21.localllm.evaluation.EvaluationFailureCode
import io.github.daniele21.localllm.evaluation.EvaluationFailureStage
import io.github.daniele21.localllm.evaluation.EvaluationModelIdentity
import io.github.daniele21.localllm.evaluation.EvaluationModelLoadPolicy
import io.github.daniele21.localllm.evaluation.EvaluationOutcome
import io.github.daniele21.localllm.evaluation.EvaluationProgress
import io.github.daniele21.localllm.evaluation.EvaluationQualitySummary
import io.github.daniele21.localllm.evaluation.EvaluationReliabilitySummary
import io.github.daniele21.localllm.evaluation.EvaluationRunConfig
import io.github.daniele21.localllm.evaluation.EvaluationRunId
import io.github.daniele21.localllm.evaluation.EvaluationRunIdentity
import io.github.daniele21.localllm.evaluation.EvaluationRunState
import io.github.daniele21.localllm.evaluation.EvaluationRunSummary
import io.github.daniele21.localllm.evaluation.EvaluationRuntimeEnvironmentIdentity
import io.github.daniele21.localllm.evaluation.EvaluationSemanticExecution
import io.github.daniele21.localllm.evaluation.EvaluationSemanticExecutionIdentity
import io.github.daniele21.localllm.evaluation.EvaluationWarmupPolicy
import io.github.daniele21.localllm.evaluation.EvaluatorOutcomeCode
import io.github.daniele21.localllm.evaluation.EvaluatorSetDigest
import io.github.daniele21.localllm.evaluation.EvaluatorSpec
import io.github.daniele21.localllm.evaluation.EvaluatorType
import io.github.daniele21.localllm.evaluation.EvaluatorVersion
import io.github.daniele21.localllm.evaluation.NormalizedScore
import io.github.daniele21.localllm.evaluation.PersistedEvaluationRun
import io.github.daniele21.localllm.evaluation.SamplingPolicyId
import io.github.daniele21.localllm.evaluation.SamplingPolicyRef
import io.github.daniele21.localllm.evaluation.SamplingSelection

@Suppress("TooManyFunctions")
internal object EvaluationRoomMapper {
    fun runEntity(summary: EvaluationRunSummary): EvaluationRunEntity = EvaluationRunEntity(
        runId = summary.runId.value,
        config = configEntity(summary.config),
        identity = summary.identity?.let(::identityEntity),
        state = summary.state.name,
        progress = progressEntity(summary.progress),
        qualityPresent = summary.quality != null,
        qualityAggregateScore = summary.quality?.aggregateScore?.value,
        reliability = summary.reliability?.let(::reliabilityEntity),
        startedAtEpochMs = summary.startedAtEpochMs,
        completedAtEpochMs = summary.completedAtEpochMs,
        failure = summary.failure?.let(::failureEntity),
    )

    fun sampleEntities(summary: EvaluationRunSummary): List<EvaluationSampleCaseEntity> =
        summary.config.sampling.orderedCaseIds.mapIndexed { ordinal, caseId ->
            EvaluationSampleCaseEntity(summary.runId.value, ordinal, caseId.value)
        }

    fun categoryScoreEntities(summary: EvaluationRunSummary): List<EvaluationCategoryScoreEntity> =
        summary.quality?.categoryScores.orEmpty().mapIndexed { ordinal, score ->
            EvaluationCategoryScoreEntity(
                runId = summary.runId.value,
                ordinal = ordinal,
                categoryId = score.categoryId.value,
                score = score.score.value,
                scoredCaseCount = score.scoredCaseCount,
                weight = score.weight,
            )
        }

    fun caseResultEntity(runId: EvaluationRunId, result: EvaluationCaseResult): EvaluationCaseResultEntity = EvaluationCaseResultEntity(
        runId = runId.value,
        caseId = result.caseId.value,
        categoryId = result.categoryId.value,
        evaluatorType = result.evaluator.type.name,
        evaluatorVersion = result.evaluator.version.value,
        status = result.status.name,
        outcomeScore = result.outcome?.score?.value,
        outcomeCode = result.outcome?.code?.name,
        requestId = result.requestId?.value,
        timeToFirstTokenMs = result.metrics.timeToFirstTokenMs,
        totalMs = result.metrics.totalMs,
        prefillMs = result.metrics.prefillMs,
        decodeMs = result.metrics.decodeMs,
        inputTokens = result.metrics.inputTokens,
        outputTokens = result.metrics.outputTokens,
        decodeTokensPerSecond = result.metrics.decodeTokensPerSecond,
        processPssBytes = result.metrics.processPssBytes,
        availableMemoryBytes = result.metrics.availableMemoryBytes,
        thermalStatus = result.metrics.thermalStatus,
        failure = result.failure?.let(::failureEntity),
    )

    fun evaluatorParameterEntities(runId: EvaluationRunId, result: EvaluationCaseResult): List<EvaluationEvaluatorParameterEntity> =
        result.evaluator.parameters.toSortedMap().map { (key, value) ->
            EvaluationEvaluatorParameterEntity(runId.value, result.caseId.value, key, value)
        }

    fun summary(stored: EvaluationStoredRun): EvaluationRunSummary {
        val config = config(stored.run.config, stored.samples)
        return summary(stored.run, config, stored.categoryScores)
    }

    fun persistedRun(stored: EvaluationStoredRun): PersistedEvaluationRun {
        val config = config(stored.run.config, stored.samples)
        val summary = summary(stored.run, config, stored.categoryScores)
        val parametersByCase = stored.evaluatorParameters.groupBy { it.caseId }
        val results = stored.caseResults.map { entity ->
            caseResult(entity, parametersByCase[entity.caseId].orEmpty())
        }
        return PersistedEvaluationRun(summary, results)
    }

    private fun configEntity(config: EvaluationRunConfig): EvaluationRunConfigEntity = EvaluationRunConfigEntity(
        modelDigest = config.model.artifactDigest.sha256,
        modelProfileId = config.model.modelProfileId,
        modelTier = config.model.tier,
        modelQuantization = config.model.quantization,
        datasetId = config.dataset.id.value,
        datasetVersion = config.dataset.version.value,
        datasetDigest = config.dataset.digest.sha256,
        sampleSetDigest = config.sampling.digest.sha256,
        samplingPolicyId = config.sampling.policy.id.value,
        samplingPolicyVersion = config.sampling.policy.version,
        samplingSeed = config.sampling.seed,
        executionProfileId = config.executionProfile.id.value,
        executionProfileVersion = config.executionProfile.version,
        loadPolicy = config.loadPolicy.name,
        warmupPolicy = config.warmupPolicy.name,
        caseTimeoutMs = config.caseTimeoutMs,
    )

    private fun identityEntity(identity: EvaluationRunIdentity): EvaluationRunIdentityEntity {
        val semantic = identity.semanticExecution.execution
        val runtime = identity.runtimeEnvironment
        return EvaluationRunIdentityEntity(
            evaluatorSetDigest = identity.evaluatorSetDigest.sha256,
            semanticExecutionFingerprint = identity.semanticExecution.fingerprint.sha256,
            runFingerprint = identity.fingerprint.sha256,
            semantic = EvaluationSemanticExecutionEntity(
                semanticsVersion = semantic.semanticsVersion,
                backendRevision = semantic.backendRevision,
                contextSize = semantic.contextSize,
                presetId = semantic.preset?.id?.value,
                presetVersion = semantic.preset?.version,
                thinkingMode = semantic.thinkingMode.name,
                temperature = semantic.temperature,
                topP = semantic.topP,
                topK = semantic.topK,
                minP = semantic.minP,
                presencePenalty = semantic.presencePenalty,
                repeatPenalty = semantic.repeatPenalty,
                repeatLastN = semantic.repeatLastN,
                seedPolicy = semantic.seedPolicy.name,
                effectiveSeed = semantic.effectiveSeed,
                maxOutputTokens = semantic.maxOutputTokens,
                chatTemplateId = semantic.chatTemplateId,
                chatTemplateSource = semantic.chatTemplateSource.name,
                systemPromptVersion = semantic.systemPromptVersion,
                caseExecutionSemanticsDigest = semantic.caseExecutionSemanticsDigest.sha256,
            ),
            runtime = EvaluationRuntimeEnvironmentEntity(
                deviceClass = runtime.deviceClass,
                androidApiLevel = runtime.androidApiLevel,
                abi = runtime.abi,
                backendRevision = runtime.backendRevision,
                harnessBuildIdentity = runtime.harnessBuildIdentity,
                runtimeTuningProfileId = runtime.runtimeTuningProfileId,
                runtimeTuningProfileVersion = runtime.runtimeTuningProfileVersion,
                loadPolicy = runtime.loadPolicy.name,
                warmupPolicy = runtime.warmupPolicy.name,
            ),
        )
    }

    private fun progressEntity(progress: EvaluationProgress) = EvaluationProgressEntity(
        totalCases = progress.totalCases,
        attemptedCases = progress.attemptedCases,
        completedCases = progress.completedCases,
        currentCaseId = progress.currentCaseId?.value,
    )

    private fun reliabilityEntity(summary: EvaluationReliabilitySummary) = EvaluationReliabilityEntity(
        totalCases = summary.totalCases,
        completedAndScored = summary.completedAndScored,
        incorrectButValid = summary.incorrectButValid,
        invalidOutput = summary.invalidOutput,
        timeout = summary.timeout,
        runtimeFailure = summary.runtimeFailure,
        cancelled = summary.cancelled,
        skipped = summary.skipped,
    )

    private fun failureEntity(failure: EvaluationFailure) = EvaluationFailureEntity(
        stage = failure.stage.name,
        code = failure.code.name,
        caseId = failure.caseId?.value,
        retryable = failure.retryable,
    )

    private fun config(entity: EvaluationRunConfigEntity, samples: List<EvaluationSampleCaseEntity>): EvaluationRunConfig {
        require(samples.map { it.ordinal } == samples.indices.toList()) { "Persisted evaluation sample ordinals must be contiguous" }
        val dataset = EvaluationDatasetIdentity(
            id = EvaluationDatasetId(entity.datasetId),
            version = EvaluationDatasetVersion(entity.datasetVersion),
            digest = EvaluationDatasetDigest(entity.datasetDigest),
        )
        val sampling = SamplingSelection.create(
            dataset = dataset,
            policy = SamplingPolicyRef(SamplingPolicyId(entity.samplingPolicyId), entity.samplingPolicyVersion),
            seed = entity.samplingSeed,
            orderedCaseIds = samples.map { EvaluationCaseId(it.caseId) },
        )
        require(sampling.digest.sha256 == entity.sampleSetDigest) { "Persisted evaluation sample digest does not match sample rows" }
        return EvaluationRunConfig(
            runId = EvaluationRunId(samples.firstOrNull()?.runId ?: error("Persisted evaluation run has no sample rows")),
            model = EvaluationModelIdentity(
                artifactDigest = ModelDigest(entity.modelDigest),
                modelProfileId = entity.modelProfileId,
                tier = entity.modelTier,
                quantization = entity.modelQuantization,
            ),
            dataset = dataset,
            sampling = sampling,
            executionProfile = EvaluationExecutionProfileRef(
                EvaluationExecutionProfileId(entity.executionProfileId),
                entity.executionProfileVersion,
            ),
            loadPolicy = EvaluationModelLoadPolicy.valueOf(entity.loadPolicy),
            warmupPolicy = EvaluationWarmupPolicy.valueOf(entity.warmupPolicy),
            caseTimeoutMs = entity.caseTimeoutMs,
        )
    }

    private fun summary(
        entity: EvaluationRunEntity,
        config: EvaluationRunConfig,
        categories: List<EvaluationCategoryScoreEntity>,
    ): EvaluationRunSummary {
        require(config.runId.value == entity.runId) { "Persisted evaluation run ID does not match sample rows" }
        val quality = quality(entity, categories)
        return EvaluationRunSummary(
            runId = EvaluationRunId(entity.runId),
            config = config,
            identity = entity.identity?.let { identity(it, config) },
            state = EvaluationRunState.valueOf(entity.state),
            progress = EvaluationProgress(
                totalCases = entity.progress.totalCases,
                attemptedCases = entity.progress.attemptedCases,
                completedCases = entity.progress.completedCases,
                currentCaseId = entity.progress.currentCaseId?.let(::EvaluationCaseId),
            ),
            quality = quality,
            reliability = entity.reliability?.let(::reliability),
            startedAtEpochMs = entity.startedAtEpochMs,
            completedAtEpochMs = entity.completedAtEpochMs,
            failure = entity.failure?.let(::failure),
        )
    }

    private fun quality(entity: EvaluationRunEntity, categories: List<EvaluationCategoryScoreEntity>): EvaluationQualitySummary? {
        if (!entity.qualityPresent) {
            require(entity.qualityAggregateScore == null && categories.isEmpty()) {
                "Persisted quality rows exist while quality is absent"
            }
            return null
        }
        require(categories.map { it.ordinal } == categories.indices.toList()) {
            "Persisted category score ordinals must be contiguous"
        }
        return EvaluationQualitySummary(
            aggregateScore = entity.qualityAggregateScore?.let(::NormalizedScore),
            categoryScores = categories.map { row ->
                EvaluationCategoryScore(
                    categoryId = EvaluationCategoryId(row.categoryId),
                    score = NormalizedScore(row.score),
                    scoredCaseCount = row.scoredCaseCount,
                    weight = row.weight,
                )
            },
        )
    }

    private fun identity(entity: EvaluationRunIdentityEntity, config: EvaluationRunConfig): EvaluationRunIdentity {
        val semanticExecution = semanticExecution(entity.semantic, config.executionProfile)
        val semanticIdentity = EvaluationSemanticExecutionIdentity.create(semanticExecution)
        require(semanticIdentity.fingerprint.sha256 == entity.semanticExecutionFingerprint) {
            "Persisted semantic execution fingerprint does not match semantic fields"
        }
        val runtime = EvaluationRuntimeEnvironmentIdentity(
            deviceClass = entity.runtime.deviceClass,
            androidApiLevel = entity.runtime.androidApiLevel,
            abi = entity.runtime.abi,
            backendRevision = entity.runtime.backendRevision,
            harnessBuildIdentity = entity.runtime.harnessBuildIdentity,
            runtimeTuningProfileId = entity.runtime.runtimeTuningProfileId,
            runtimeTuningProfileVersion = entity.runtime.runtimeTuningProfileVersion,
            loadPolicy = EvaluationModelLoadPolicy.valueOf(entity.runtime.loadPolicy),
            warmupPolicy = EvaluationWarmupPolicy.valueOf(entity.runtime.warmupPolicy),
        )
        val identity = EvaluationRunIdentity.create(
            model = config.model,
            dataset = config.dataset,
            sampleSetDigest = config.sampling.digest,
            samplingPolicy = config.sampling.policy,
            samplingSeed = config.sampling.seed,
            evaluatorSetDigest = EvaluatorSetDigest(entity.evaluatorSetDigest),
            semanticExecution = semanticIdentity,
            runtimeEnvironment = runtime,
        )
        require(identity.fingerprint.sha256 == entity.runFingerprint) {
            "Persisted evaluation run fingerprint does not match identity fields"
        }
        return identity
    }

    private fun semanticExecution(
        entity: EvaluationSemanticExecutionEntity,
        profile: EvaluationExecutionProfileRef,
    ): EvaluationSemanticExecution {
        val preset = when {
            entity.presetId == null && entity.presetVersion == null -> null

            entity.presetId != null && entity.presetVersion != null ->
                InferencePresetRef(InferencePresetId(entity.presetId), entity.presetVersion)

            else -> error("Persisted inference preset identity is incomplete")
        }
        return EvaluationSemanticExecution(
            semanticsVersion = entity.semanticsVersion,
            profile = profile,
            backendRevision = entity.backendRevision,
            contextSize = entity.contextSize,
            preset = preset,
            thinkingMode = ThinkingMode.valueOf(entity.thinkingMode),
            temperature = entity.temperature,
            topP = entity.topP,
            topK = entity.topK,
            minP = entity.minP,
            presencePenalty = entity.presencePenalty,
            repeatPenalty = entity.repeatPenalty,
            repeatLastN = entity.repeatLastN,
            seedPolicy = SeedPolicyType.valueOf(entity.seedPolicy),
            effectiveSeed = entity.effectiveSeed,
            maxOutputTokens = entity.maxOutputTokens,
            chatTemplateId = entity.chatTemplateId,
            chatTemplateSource = ChatTemplateSource.valueOf(entity.chatTemplateSource),
            systemPromptVersion = entity.systemPromptVersion,
            caseExecutionSemanticsDigest = CaseExecutionSemanticsDigest(entity.caseExecutionSemanticsDigest),
        )
    }

    private fun reliability(entity: EvaluationReliabilityEntity) = EvaluationReliabilitySummary(
        totalCases = entity.totalCases,
        completedAndScored = entity.completedAndScored,
        incorrectButValid = entity.incorrectButValid,
        invalidOutput = entity.invalidOutput,
        timeout = entity.timeout,
        runtimeFailure = entity.runtimeFailure,
        cancelled = entity.cancelled,
        skipped = entity.skipped,
    )

    private fun failure(entity: EvaluationFailureEntity) = EvaluationFailure(
        stage = EvaluationFailureStage.valueOf(entity.stage),
        code = EvaluationFailureCode.valueOf(entity.code),
        caseId = entity.caseId?.let(::EvaluationCaseId),
        retryable = entity.retryable,
    )

    private fun caseResult(
        entity: EvaluationCaseResultEntity,
        parameters: List<EvaluationEvaluatorParameterEntity>,
    ): EvaluationCaseResult {
        require(parameters.all { it.runId == entity.runId && it.caseId == entity.caseId }) {
            "Persisted evaluator parameter identity does not match case result"
        }
        val outcome = when {
            entity.outcomeScore == null && entity.outcomeCode == null -> null

            entity.outcomeScore != null && entity.outcomeCode != null -> EvaluationOutcome(
                score = NormalizedScore(entity.outcomeScore),
                code = EvaluatorOutcomeCode.valueOf(entity.outcomeCode),
            )

            else -> error("Persisted evaluator outcome is incomplete")
        }
        return EvaluationCaseResult(
            caseId = EvaluationCaseId(entity.caseId),
            categoryId = EvaluationCategoryId(entity.categoryId),
            evaluator = EvaluatorSpec(
                type = EvaluatorType.valueOf(entity.evaluatorType),
                version = EvaluatorVersion(entity.evaluatorVersion),
                parameters = parameters.associate { it.parameterKey to it.parameterValue },
            ),
            status = EvaluationCaseStatus.valueOf(entity.status),
            outcome = outcome,
            requestId = entity.requestId?.let(::RequestId),
            metrics = EvaluationCaseMetrics(
                timeToFirstTokenMs = entity.timeToFirstTokenMs,
                totalMs = entity.totalMs,
                prefillMs = entity.prefillMs,
                decodeMs = entity.decodeMs,
                inputTokens = entity.inputTokens,
                outputTokens = entity.outputTokens,
                decodeTokensPerSecond = entity.decodeTokensPerSecond,
                processPssBytes = entity.processPssBytes,
                availableMemoryBytes = entity.availableMemoryBytes,
                thermalStatus = entity.thermalStatus,
            ),
            failure = entity.failure?.let(::failure),
        )
    }
}
