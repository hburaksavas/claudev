package dev.claudev.adapter.fepipeline;

import java.util.Map;

/** The interpreter for one entry in the fixed step-type registry (docs/FE_PIPELINE_STEPS.md). */
@FunctionalInterface
interface StepExecutor {

    void execute(Map<String, Object> params) throws StepExecutionException;
}
