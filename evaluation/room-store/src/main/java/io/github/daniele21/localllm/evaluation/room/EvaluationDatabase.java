package io.github.daniele21.localllm.evaluation.room;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {
            EvaluationRunEntity.class,
            EvaluationSampleCaseEntity.class,
            EvaluationCategoryScoreEntity.class,
            EvaluationCaseResultEntity.class,
            EvaluationEvaluatorParameterEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class EvaluationDatabase extends RoomDatabase {
    public abstract EvaluationDao evaluationDao();
}
