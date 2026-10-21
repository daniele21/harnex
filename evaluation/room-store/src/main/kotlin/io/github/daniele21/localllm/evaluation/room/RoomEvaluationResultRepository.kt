package io.github.daniele21.localllm.evaluation.room

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.daniele21.localllm.evaluation.EvaluationCaseResult
import io.github.daniele21.localllm.evaluation.EvaluationResultRepository
import io.github.daniele21.localllm.evaluation.EvaluationRetentionPolicy
import io.github.daniele21.localllm.evaluation.EvaluationRetentionResult
import io.github.daniele21.localllm.evaluation.EvaluationRunDeleteStatus
import io.github.daniele21.localllm.evaluation.EvaluationRunId
import io.github.daniele21.localllm.evaluation.EvaluationRunQuery
import io.github.daniele21.localllm.evaluation.EvaluationRunState
import io.github.daniele21.localllm.evaluation.EvaluationRunSummary
import io.github.daniele21.localllm.evaluation.PersistedEvaluationRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomEvaluationResultRepository internal constructor(
    private val dao: EvaluationDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val closeDatabase: () -> Unit = {},
) : EvaluationResultRepository,
    AutoCloseable {
    override suspend fun createRun(summary: EvaluationRunSummary) = withContext(Dispatchers.IO) {
        require(dao.findRun(summary.runId.value) == null) { "Evaluation run already exists" }
        dao.createRunGraph(
            EvaluationRoomMapper.runEntity(summary),
            EvaluationRoomMapper.sampleEntities(summary),
            EvaluationRoomMapper.categoryScoreEntities(summary),
        )
    }

    override suspend fun updateRunSummary(summary: EvaluationRunSummary) = withContext(Dispatchers.IO) {
        val current = requireStoredRun(summary.runId)
        val currentSummary = EvaluationRoomMapper.summary(current)
        require(currentSummary.config == summary.config) { "Evaluation run config cannot change after creation" }
        requireValidTransition(currentSummary.state, summary.state)
        dao.updateRunGraph(
            EvaluationRoomMapper.runEntity(summary),
            EvaluationRoomMapper.categoryScoreEntities(summary),
        )
    }

    override suspend fun appendCaseResult(runId: EvaluationRunId, result: EvaluationCaseResult) = withContext(Dispatchers.IO) {
        val stored = requireStoredRun(runId)
        val summary = EvaluationRoomMapper.summary(stored)
        require(!summary.state.isTerminal()) { "Cannot append case result to terminal evaluation run" }
        require(result.caseId in summary.config.sampling.orderedCaseIds) {
            "Evaluation case result must belong to run sample set"
        }
        require(dao.sampleCaseCount(runId.value, result.caseId.value) == 1) {
            "Evaluation case result must reference exactly one sampled case"
        }
        dao.upsertCaseResultGraph(
            EvaluationRoomMapper.caseResultEntity(runId, result),
            EvaluationRoomMapper.evaluatorParameterEntities(runId, result),
        )
    }

    override suspend fun getRun(runId: EvaluationRunId): PersistedEvaluationRun? = withContext(Dispatchers.IO) {
        dao.loadStoredRun(runId.value)?.let(EvaluationRoomMapper::persistedRun)
    }

    override suspend fun queryRuns(query: EvaluationRunQuery): List<EvaluationRunSummary> = withContext(Dispatchers.IO) {
        dao.queryRuns(query.toSqlQuery()).map { entity ->
            val stored = requireNotNull(dao.loadStoredRun(entity.runId)) {
                "Evaluation run disappeared while reading history"
            }
            EvaluationRoomMapper.summary(stored)
        }
    }

    override suspend fun deleteRun(runId: EvaluationRunId): EvaluationRunDeleteStatus = withContext(Dispatchers.IO) {
        val run = dao.findRun(runId.value) ?: return@withContext EvaluationRunDeleteStatus.NOT_FOUND
        if (!EvaluationRunState.valueOf(run.state).isTerminal()) {
            EvaluationRunDeleteStatus.ACTIVE_RUN
        } else {
            check(dao.deleteRunRow(runId.value) == 1) { "Evaluation run disappeared while deleting" }
            EvaluationRunDeleteStatus.DELETED
        }
    }

    override suspend fun applyRetention(policy: EvaluationRetentionPolicy): EvaluationRetentionResult = withContext(Dispatchers.IO) {
        val now = clock()
        val terminal = dao.terminalRunsNewestFirst()
        val keepByCount = terminal.take(policy.maxTerminalRuns).map { it.runId }.toSet()
        val expiredByAge = policy.maxAgeMs?.let { maxAgeMs ->
            terminal.filter { now - it.startedAtEpochMs >= maxAgeMs }.map { it.runId }.toSet()
        }.orEmpty()
        val deleteIds = terminal.asSequence()
            .map { it.runId }
            .filter { it !in keepByCount || it in expiredByAge }
            .toList()
        if (deleteIds.isNotEmpty()) {
            dao.deleteRunRows(deleteIds)
        }
        EvaluationRetentionResult(
            deletedRunIds = deleteIds.map(::EvaluationRunId),
            retainedRunCount = dao.runCount(),
        )
    }

    override fun close() {
        closeDatabase()
    }

    private fun requireStoredRun(runId: EvaluationRunId): EvaluationStoredRun = requireNotNull(dao.loadStoredRun(runId.value)) {
        "Evaluation run does not exist"
    }

    private fun requireValidTransition(from: EvaluationRunState, to: EvaluationRunState) {
        require(from == to || to in ALLOWED_TRANSITIONS.getValue(from)) {
            "Invalid evaluation run transition: $from -> $to"
        }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME: String = "local-llm-evaluation.db"
        private val MIGRATIONS: List<Migration> = emptyList()

        fun open(context: Context, databaseName: String = DEFAULT_DATABASE_NAME): RoomEvaluationResultRepository {
            require(databaseName.isNotBlank()) { "Evaluation database name must not be blank" }
            val builder = Room.databaseBuilder(
                context.applicationContext,
                EvaluationDatabase::class.java,
                databaseName,
            )
            MIGRATIONS.forEach { migration -> builder.addMigrations(migration) }
            val database = builder.build()
            return RoomEvaluationResultRepository(
                dao = database.evaluationDao(),
                closeDatabase = database::close,
            )
        }

        private val ALLOWED_TRANSITIONS: Map<EvaluationRunState, Set<EvaluationRunState>> = mapOf(
            EvaluationRunState.CREATED to setOf(EvaluationRunState.VALIDATING, EvaluationRunState.CANCELLING, EvaluationRunState.FAILED),
            EvaluationRunState.VALIDATING to
                setOf(EvaluationRunState.PREPARING_MODEL, EvaluationRunState.CANCELLING, EvaluationRunState.FAILED),
            EvaluationRunState.PREPARING_MODEL to setOf(
                EvaluationRunState.WARMING_UP,
                EvaluationRunState.RUNNING,
                EvaluationRunState.CANCELLING,
                EvaluationRunState.FAILED,
            ),
            EvaluationRunState.WARMING_UP to setOf(EvaluationRunState.RUNNING, EvaluationRunState.CANCELLING, EvaluationRunState.FAILED),
            EvaluationRunState.RUNNING to setOf(EvaluationRunState.AGGREGATING, EvaluationRunState.CANCELLING, EvaluationRunState.FAILED),
            EvaluationRunState.AGGREGATING to setOf(EvaluationRunState.COMPLETED, EvaluationRunState.CANCELLING, EvaluationRunState.FAILED),
            EvaluationRunState.CANCELLING to setOf(EvaluationRunState.CANCELLED, EvaluationRunState.FAILED),
            EvaluationRunState.COMPLETED to emptySet(),
            EvaluationRunState.CANCELLED to emptySet(),
            EvaluationRunState.FAILED to emptySet(),
        )
    }
}

private fun EvaluationRunQuery.toSqlQuery(): SimpleSQLiteQuery {
    val sql = StringBuilder("SELECT * FROM evaluation_runs WHERE 1=1")
    val args = mutableListOf<Any?>()
    if (states.isNotEmpty()) {
        val orderedStates = states.sortedBy { it.name }
        sql.append(" AND state IN (")
        sql.append(orderedStates.joinToString(",") { "?" })
        sql.append(')')
        args.addAll(orderedStates.map { it.name })
    }
    datasetId?.let {
        sql.append(" AND config_dataset_id = ?")
        args += it.value
    }
    modelDigest?.let {
        sql.append(" AND config_model_digest = ?")
        args += it.sha256
    }
    startedBeforeEpochMs?.let {
        sql.append(" AND started_at_epoch_ms < ?")
        args += it
    }
    sql.append(" ORDER BY started_at_epoch_ms DESC, run_id ASC LIMIT ?")
    args += limit
    return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
}

private fun EvaluationRunState.isTerminal(): Boolean = when (this) {
    EvaluationRunState.COMPLETED,
    EvaluationRunState.CANCELLED,
    EvaluationRunState.FAILED,
    -> true

    else -> false
}
