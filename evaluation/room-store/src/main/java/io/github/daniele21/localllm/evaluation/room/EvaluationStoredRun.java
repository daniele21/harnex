package io.github.daniele21.localllm.evaluation.room;

import java.util.List;

public final class EvaluationStoredRun {
    public final EvaluationRunEntity run;
    public final List<EvaluationSampleCaseEntity> samples;
    public final List<EvaluationCategoryScoreEntity> categoryScores;
    public final List<EvaluationCaseResultEntity> caseResults;
    public final List<EvaluationEvaluatorParameterEntity> evaluatorParameters;

    public EvaluationStoredRun(
            EvaluationRunEntity run,
            List<EvaluationSampleCaseEntity> samples,
            List<EvaluationCategoryScoreEntity> categoryScores,
            List<EvaluationCaseResultEntity> caseResults,
            List<EvaluationEvaluatorParameterEntity> evaluatorParameters) {
        this.run = run;
        this.samples = samples;
        this.categoryScores = categoryScores;
        this.caseResults = caseResults;
        this.evaluatorParameters = evaluatorParameters;
    }
}
