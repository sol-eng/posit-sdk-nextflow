package com.posit.nextflow.workbench

import groovy.transform.CompileStatic
import nextflow.executor.GridTaskHandler
import nextflow.processor.TaskRun

/**
 * A grid task handler that launches the task's job with the Workbench API instead of running a
 * submit command. Only {@link #processStart} changes: {@code GridTaskHandler.submit()} still
 * writes the task wrapper, retries a failed submit, records the job id (through the executor's
 * {@code parseJobId}) and moves the task to SUBMITTED, as for any grid executor. Completion is
 * still detected through the task's {@code .exitcode} file.
 */
@CompileStatic
class WorkbenchTaskHandler extends GridTaskHandler {

    private final WorkbenchExecutor workbench

    WorkbenchTaskHandler(TaskRun task, WorkbenchExecutor executor) {
        super(task, executor)
        this.workbench = executor
    }

    /** Launch the job; the returned text is the job id. The process builder is not started. */
    @Override
    protected String processStart(ProcessBuilder builder, String pipeScript) {
        return workbench.submitJob(task, task.workDir.resolve(TaskRun.CMD_RUN))
    }
}
