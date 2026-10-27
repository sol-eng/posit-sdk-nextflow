package com.posit.nextflow.workbench

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException
import nextflow.executor.AbstractGridExecutor
import nextflow.executor.GridTaskHandler
import nextflow.processor.TaskRun
import nextflow.util.Escape
import nextflow.util.ServiceName

/**
 * Executor that runs each Nextflow task as a Posit Workbench Job, through the Workbench
 * Launcher API ({@link WorkbenchApiClient}). It is a grid executor like Nextflow's Slurm or LSF
 * executors, except that it calls the API where those run {@code sbatch}/{@code squeue}/
 * {@code scancel}: {@link WorkbenchTaskHandler} launches a task's job, and
 * {@link #getQueueStatus0} and {@link #killTask} list and stop jobs.
 *
 * Users select this executor with {@code process.executor = 'workbench'} and must set the
 * target cluster in the plugin's own {@code workbench} scope (see {@link WorkbenchConfig}):
 * <pre>
 * workbench {
 *     cluster = 'my-cluster'      // required
 *     resourceProfile = 'small'   // optional, cluster's default if unset
 * }
 * </pre>
 * A single process can override the resource profile with {@code ext.resourceProfile = 'large'}.
 *
 * Assumes a shared filesystem between wherever Nextflow is driven from and wherever Workbench
 * runs jobs (the same assumption the sibling posit-sdk-snakemake integration makes), since task
 * staging, the generated {@code .command.run} wrapper script, and completion detection (via the
 * {@code .exitcode} file -- see {@code GridTaskHandler}) all go through {@code task.workDir}.
 *
 * Credentials come from this JVM's environment: inside a Workbench session or job, the
 * session's RPC cookie (interactive use from Positron or RStudio Pro, and a {@code nextflow run}
 * that is itself a Workbench Job); anywhere else {@code WORKBENCH_SERVER}/{@code WORKBENCH_API_KEY}.
 */
@Slf4j
@CompileStatic
@ServiceName('workbench')
class WorkbenchExecutor extends AbstractGridExecutor {

    @PackageScope String cluster
    @PackageScope String resourceProfile
    /** made from the environment when first needed */
    private WorkbenchApiClient client

    @Override
    void register() {
        super.register()
        final opts = new WorkbenchConfig((session.config.get('workbench') ?: [:]) as Map)
        cluster = opts.cluster
        resourceProfile = opts.resourceProfile
        if( !cluster )
            throw new AbortOperationException(
                "Missing required config `workbench.cluster` -- set it to a valid " +
                "Workbench compute env/cluster name (see `get_compute_envs` in the Workbench API)"
            )
        if( opts.launcherCli )
            log.warn "[WORKBENCH] `workbench.launcherCli` is no longer used: the plugin calls the Workbench API itself"
        log.debug "[WORKBENCH] cluster=$cluster resourceProfile=$resourceProfile"
    }

    @PackageScope WorkbenchApiClient client() {
        if( client == null )
            client = new WorkbenchApiClient()
        return client
    }

    /** for tests */
    @PackageScope void setClient(WorkbenchApiClient client) {
        this.client = client
    }

    @Override
    GridTaskHandler createTaskHandler(TaskRun task) {
        return new WorkbenchTaskHandler(task, this)
    }

    /**
     * Resources are passed as structured fields of the job (see {@link #jobSpec}), not via
     * embedded job-script header directives. Nothing to add here.
     */
    @Override
    protected List<String> getDirectives(TaskRun task, List<String> initial) {
        return initial
    }

    @Override
    protected String getHeaderToken() { '#' }

    @Override
    protected String getHeaderScript(TaskRun task) {
        final base = super.getHeaderScript(task)
        // Workbench job containers running as a non-root uid can lack $HOME/$USER/$LOGNAME
        // entirely (no /etc/passwd entry for the LDAP-resolved uid) -- confirmed in the sibling
        // Snakemake integration (posit-sdk-snakemake) to break Python's cache-dir resolution and
        // similar user-lookup calls inside the task container. Exporting the driving process's
        // own values is safe since task.workDir is a shared mount either way.
        final home = System.getProperty('user.home')
        final user = System.getProperty('user.name')
        return "export HOME=${Escape.path(home)} USER=${user} LOGNAME=${user}\n" + base
    }

    /**
     * The Launcher API job for a task: {@code /bin/bash .command.run} in the task's work
     * directory, with its cpus, memory, container and resource profile.
     */
    @PackageScope Map<String, Object> jobSpec(TaskRun task, Path scriptFile) {
        final spec = new LinkedHashMap<String, Object>()
        spec.cluster = cluster
        spec.name = getJobNameFor(task)
        spec.exe = '/bin/bash'
        spec.args = [scriptFile.toAbsolutePath().toString()]
        spec.workingDirectory = task.workDir.toAbsolutePath().toString()
        final limits = []
        final cpus = task.config.getCpus()
        if( cpus > 1 )
            limits << [type: 'cpuCount', value: cpus.toString()]
        final mem = task.config.getMemory()
        if( mem )
            limits << [type: 'memory', value: mem.toMega().toString()]
        if( limits )
            spec.resourceLimits = limits
        final container = task.getContainer()
        if( container )
            spec.container = [image: container]
        final profile = resourceProfileFor(task)
        if( profile )
            spec.resourceProfile = profile
        return spec
    }

    /** Launch a task's job; returns its id. Called by {@link WorkbenchTaskHandler}. */
    @PackageScope String submitJob(TaskRun task, Path scriptFile) {
        return client().launchJob(jobSpec(task, scriptFile))
    }

    /**
     * Per-process {@code ext.resourceProfile} wins over the executor-wide
     * {@code workbench.resourceProfile}; neither set means the cluster's default profile.
     */
    @PackageScope String resourceProfileFor(TaskRun task) {
        final ext = task.config.get('ext') as Map
        return (ext?.get('resourceProfile') ?: resourceProfile) as String
    }

    /**
     * Not run: {@link WorkbenchTaskHandler} launches jobs with the API. Describes the submit for
     * Nextflow's logs and error messages.
     */
    @Override
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile) {
        return ['workbench', 'launch_job', '--cluster', cluster, '--name', getJobNameFor(task),
                scriptFile.getName()]
    }

    @Override
    def parseJobId(String text) {
        final id = text?.trim()
        if( !id )
            throw new IllegalStateException("Invalid Workbench submit response: empty job id")
        return id
    }

    /** How a Workbench job's status maps to Nextflow's. */
    static QueueStatus queueStatusOf(Map job) {
        switch( job.status as String ) {
            case 'Finished':
                return job.exitCode in [0, null] ? QueueStatus.DONE : QueueStatus.ERROR
            case 'Failed':
            case 'Killed':
            case 'Canceled':
                return QueueStatus.ERROR
            case 'Running':
                return QueueStatus.RUNNING
            case 'Suspended':
                return QueueStatus.HOLD
            case 'Pending':
                return QueueStatus.PENDING
            default:
                return QueueStatus.UNKNOWN
        }
    }

    /**
     * Every job visible to the credentials, with one API call (two with job history); there is
     * no queue or partition in the Workbench Jobs API. Null if Workbench can't be asked, as for
     * a failed status command, so Nextflow keeps the last known states.
     */
    @Override
    protected Map<String, QueueStatus> getQueueStatus0(Object queue) {
        try {
            final result = new LinkedHashMap<String, QueueStatus>()
            client().statusMap().each { String id, Map job -> result.put(id, queueStatusOf(job)) }
            return result
        }
        catch( Exception e ) {
            log.warn "[WORKBENCH] cannot get the status of Workbench jobs: ${e.message}"
            return null
        }
    }

    /** Stop jobs at once (SIGKILL), best-effort: a job may have ended already. */
    @Override
    void killTask(Object jobId) {
        final ids = jobId instanceof Collection ? (Collection) jobId : [jobId]
        for( Object id : ids ) {
            try {
                client().stopJob(id.toString(), true)
            }
            catch( Exception e ) {
                log.debug "[WORKBENCH] cannot stop job $id: ${e.message}"
            }
        }
    }

    /** Not run: {@link #killTask} stops jobs with the API. */
    @Override
    protected List<String> getKillCommand() {
        return ['workbench', 'stop_job']
    }

    /** Not run: {@link #getQueueStatus0} lists jobs with the API. */
    @Override
    protected List<String> queueStatusCommand(Object queue) {
        return ['workbench', 'get_session']
    }

    /** Not used: {@link #getQueueStatus0} maps the API's job records directly. */
    @Override
    protected Map<String, QueueStatus> parseQueueStatus(String text) {
        return [:]
    }
}
