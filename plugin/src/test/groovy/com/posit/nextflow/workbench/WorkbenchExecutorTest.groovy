package com.posit.nextflow.workbench

import java.nio.file.Paths

import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.executor.AbstractGridExecutor.QueueStatus
import nextflow.executor.ExecutorConfig
import nextflow.processor.TaskConfig
import nextflow.processor.TaskRun
import spock.lang.Specification

class WorkbenchExecutorTest extends Specification {

    private WorkbenchExecutor newExecutor(Map workbenchOpts, WorkbenchExecutor executor = new WorkbenchExecutor()) {
        final session = Mock(Session)
        session.getConfig() >> [workbench: workbenchOpts]
        executor.name = 'workbench'
        executor.session = session
        executor.config = new ExecutorConfig([:])
        return executor
    }

    private TaskRun newTask(Map taskConfig) {
        final task = Mock(TaskRun)
        task.getConfig() >> new TaskConfig(taskConfig)
        return task
    }

    def 'should require a cluster to be configured'() {
        given:
        final executor = newExecutor([:])

        when:
        executor.register()

        then:
        thrown(AbortOperationException)
    }

    def 'should read the cluster from the workbench config scope'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', launcherCli: 'ignored'])

        when:
        executor.register()

        then:
        noExceptionThrown()
        executor.cluster == 'my-cluster'
    }

    def 'should parse a job id'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        expect:
        executor.parseJobId('42\n') == '42'
    }

    def 'should reject an empty job id'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        when:
        executor.parseJobId('   \n')

        then:
        thrown(IllegalStateException)
    }

    def 'should leave resourceProfile unset by default'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        expect:
        executor.resourceProfile == null
        executor.resourceProfileFor(newTask([:])) == null
    }

    def 'should resolve the resource profile from the executor config or ext.resourceProfile'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', resourceProfile: 'small'])
        executor.register()

        expect:
        executor.resourceProfileFor(newTask([:])) == 'small'
        executor.resourceProfileFor(newTask([ext: [resourceProfile: 'large']])) == 'large'
    }

    def 'should build the job spec with resources, container and resource profile'() {
        given:
        final executor = newExecutor([cluster: 'k8s'], Spy(WorkbenchExecutor))
        executor.register()
        executor.getJobNameFor(_) >> 'nf-foo'
        final task = newTask([cpus: 2, memory: '1 GB', ext: [resourceProfile: 'large']])
        task.getContainer() >> 'python:3.12-slim'
        task.getWorkDir() >> Paths.get('/work/ab/cd')

        expect:
        executor.jobSpec(task, Paths.get('/work/ab/cd/.command.run')) == [
            cluster: 'k8s', name: 'nf-foo', exe: '/bin/bash',
            args: ['/work/ab/cd/.command.run'], workingDirectory: '/work/ab/cd',
            resourceLimits: [[type: 'cpuCount', value: '2'], [type: 'memory', value: '1024']],
            container: [image: 'python:3.12-slim'], resourceProfile: 'large',
        ]
    }

    def 'should leave out what is not set'() {
        given:
        final executor = newExecutor([cluster: 'k8s'], Spy(WorkbenchExecutor))
        executor.register()
        executor.getJobNameFor(_) >> 'nf-foo'
        final task = newTask([:])
        task.getWorkDir() >> Paths.get('/w')

        expect:
        executor.jobSpec(task, Paths.get('/w/.command.run')).keySet() ==
            ['cluster', 'name', 'exe', 'args', 'workingDirectory'] as Set
    }

    def 'should map Workbench job statuses to Nextflow queue statuses'() {
        expect:
        WorkbenchExecutor.queueStatusOf([status: status, exitCode: exit]) == expected

        where:
        status      | exit | expected
        'Pending'   | null | QueueStatus.PENDING
        'Running'   | null | QueueStatus.RUNNING
        'Suspended' | null | QueueStatus.HOLD
        'Finished'  | 0    | QueueStatus.DONE
        'Finished'  | null | QueueStatus.DONE
        'Finished'  | 1    | QueueStatus.ERROR
        'Failed'    | null | QueueStatus.ERROR
        'Killed'    | null | QueueStatus.ERROR
        'Canceled'  | null | QueueStatus.ERROR
        'Weird'     | null | QueueStatus.UNKNOWN
    }

    def 'should submit, list and stop jobs through the Workbench API'() {
        given:
        final stub = new StubWorkbench()
        stub.replies.launch_job = [200, [result: [job: [id: '42']]]]
        stub.replies.get_session = [200, [result: [jobs: [[id: '42', status: 'Running'], [id: '7', status: 'Finished', exitCode: 2]]]]]
        final executor = newExecutor([cluster: 'k8s'], Spy(WorkbenchExecutor))
        executor.register()
        executor.client = new WorkbenchApiClient(stub.apiKeyEnv())
        executor.getJobNameFor(_) >> 'nf-foo'
        final task = newTask([:])
        task.getWorkDir() >> Paths.get('/w')

        expect:
        executor.submitJob(task, Paths.get('/w/.command.run')) == '42'
        executor.getQueueStatus0(null) == ['42': QueueStatus.RUNNING, '7': QueueStatus.ERROR]

        when:
        executor.killTask(['42', '7'])

        then:
        stub.requests.findAll { it.method == 'stop_job' }*.kwparams == [
            [job_id: '42', force_quit: true], [job_id: '7', force_quit: true],
        ]

        cleanup:
        stub.stop()
    }

    def 'should report no status when Workbench cannot be asked'() {
        given:
        final stub = new StubWorkbench()
        stub.replies.get_historical_session = [500, 'boom']
        final executor = newExecutor([cluster: 'k8s'])
        executor.register()
        executor.client = new WorkbenchApiClient(stub.apiKeyEnv())

        expect:
        executor.getQueueStatus0(null) == null

        cleanup:
        stub.stop()
    }

    def 'should create Workbench task handlers'() {
        given:
        final executor = newExecutor([cluster: 'k8s'])
        executor.register()

        final task = Mock(TaskRun)
        task.getWorkDir() >> Paths.get('/w')

        expect:
        executor.createTaskHandler(task) instanceof WorkbenchTaskHandler
    }
}
