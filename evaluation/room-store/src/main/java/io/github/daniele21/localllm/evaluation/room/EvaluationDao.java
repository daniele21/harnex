package io.github.daniele21.localllm.evaluation.room;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.room.Transaction;
import androidx.room.Update;
import androidx.sqlite.db.SupportSQLiteQuery;
import java.util.List;

@Dao
public interface EvaluationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertRun(EvaluationRunEntity run);

    @Update
    int updateRun(EvaluationRunEntity run);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertSamples(List<EvaluationSampleCaseEntity> samples);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCategoryScores(List<EvaluationCategoryScoreEntity> scores);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertCaseResult(EvaluationCaseResultEntity result);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertEvaluatorParameters(List<EvaluationEvaluatorParameterEntity> parameters);

    @Nullable
    @Query("SELECT * FROM evaluation_runs WHERE run_id = :runId LIMIT 1")
    EvaluationRunEntity findRun(String runId);

    @Query("SELECT * FROM evaluation_sample_cases WHERE run_id = :runId ORDER BY ordinal ASC")
    List<EvaluationSampleCaseEntity> sampleCases(String runId);

    @Query("SELECT * FROM evaluation_category_scores WHERE run_id = :runId ORDER BY ordinal ASC")
    List<EvaluationCategoryScoreEntity> categoryScores(String runId);

    @Query(
            "SELECT r.* FROM evaluation_case_results r "
                    + "JOIN evaluation_sample_cases s ON s.run_id = r.run_id AND s.case_id = r.case_id "
                    + "WHERE r.run_id = :runId ORDER BY s.ordinal ASC")
    List<EvaluationCaseResultEntity> caseResults(String runId);

    @Query(
            "SELECT * FROM evaluation_evaluator_parameters WHERE run_id = :runId "
                    + "ORDER BY case_id ASC, parameter_key ASC")
    List<EvaluationEvaluatorParameterEntity> evaluatorParameters(String runId);

    @Query(
            "SELECT COUNT(*) FROM evaluation_sample_cases "
                    + "WHERE run_id = :runId AND case_id = :caseId")
    int sampleCaseCount(String runId, String caseId);

    @Query("SELECT COUNT(*) FROM evaluation_runs")
    int runCount();

    @RawQuery
    List<EvaluationRunEntity> queryRuns(SupportSQLiteQuery query);

    @Query(
            "SELECT * FROM evaluation_runs WHERE state IN ('COMPLETED','CANCELLED','FAILED') "
                    + "ORDER BY started_at_epoch_ms DESC, run_id ASC")
    List<EvaluationRunEntity> terminalRunsNewestFirst();

    @Query("DELETE FROM evaluation_category_scores WHERE run_id = :runId")
    void deleteCategoryScores(String runId);

    @Query(
            "DELETE FROM evaluation_evaluator_parameters "
                    + "WHERE run_id = :runId AND case_id = :caseId")
    void deleteEvaluatorParameters(String runId, String caseId);

    @Query("DELETE FROM evaluation_runs WHERE run_id = :runId")
    int deleteRunRow(String runId);

    @Query("DELETE FROM evaluation_runs WHERE run_id IN (:runIds)")
    int deleteRunRows(List<String> runIds);

    @Transaction
    default void createRunGraph(
            EvaluationRunEntity run,
            List<EvaluationSampleCaseEntity> samples,
            List<EvaluationCategoryScoreEntity> scores) {
        insertRun(run);
        insertSamples(samples);
        if (!scores.isEmpty()) {
            insertCategoryScores(scores);
        }
    }

    @Transaction
    default void updateRunGraph(
            EvaluationRunEntity run,
            List<EvaluationCategoryScoreEntity> scores) {
        if (updateRun(run) != 1) {
            throw new IllegalStateException("Evaluation run does not exist");
        }
        deleteCategoryScores(run.getRunId());
        if (!scores.isEmpty()) {
            insertCategoryScores(scores);
        }
    }

    @Transaction
    default void upsertCaseResultGraph(
            EvaluationCaseResultEntity result,
            List<EvaluationEvaluatorParameterEntity> parameters) {
        upsertCaseResult(result);
        deleteEvaluatorParameters(result.getRunId(), result.getCaseId());
        if (!parameters.isEmpty()) {
            insertEvaluatorParameters(parameters);
        }
    }

    @Nullable
    @Transaction
    default EvaluationStoredRun loadStoredRun(String runId) {
        EvaluationRunEntity run = findRun(runId);
        if (run == null) {
            return null;
        }
        return new EvaluationStoredRun(
                run,
                sampleCases(runId),
                categoryScores(runId),
                caseResults(runId),
                evaluatorParameters(runId));
    }
}
