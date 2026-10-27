package com.posit.nextflow.workbench

import java.nio.file.Files

import spock.lang.Specification

class WorkbenchApiClientTest extends Specification {

    StubWorkbench stub = new StubWorkbench()

    def cleanup() { stub.stop() }

    def 'should send the envelope with the API key'() {
        given:
        stub.replies.get_compute_envs = [200, [result: [clusters: [[name: 'k8s']]]]]

        when:
        final envs = new WorkbenchApiClient(stub.apiKeyEnv()).computeEnvs()

        then:
        envs == [clusters: [[name: 'k8s']]]
        with(stub.requests[0]) {
            path == '/api/get_compute_envs'
            method == 'get_compute_envs'
            kwparams == [:]
            headers.getFirst('Authorization') == 'Bearer secret'
        }
    }

    def 'should keep the base path'() {
        when:
        new WorkbenchApiClient(stub.apiKeyEnv('/rstudio/')).computeEnvs()

        then:
        stub.requests[0].path == '/rstudio/api/get_compute_envs'
    }

    def 'should use the session cookie from the runtime dir, then the environment'() {
        given:
        final dir = Files.createTempDirectory('pwb')
        dir.resolve('rpc_cookie').text = 'from-file\n'
        final env = [POSIT_PRODUCT: 'WORKBENCH', RS_SERVER_ADDRESS: stub.url]

        when:
        new WorkbenchApiClient(env + [PWB_SESSION_RUNTIME_DIR: dir.toString()]).computeEnvs()
        new WorkbenchApiClient(env + [RS_SESSION_RPC_COOKIE: 'from-env']).computeEnvs()

        then:
        stub.requests*.headers*.getFirst(WorkbenchApiClient.COOKIE_HEADER) == ['from-file', 'from-env']
    }

    def 'should fall back to the API key in a session without a cookie'() {
        expect:
        new WorkbenchApiClient([POSIT_PRODUCT: 'WORKBENCH', RS_SERVER_ADDRESS: 'http://unused'] + stub.apiKeyEnv()).auth == 'api-key'
    }

    def 'should fail without credentials'() {
        when:
        new WorkbenchApiClient([:])

        then:
        thrown(WorkbenchApiClient.WorkbenchApiException)
    }

    def 'should launch a job from its spec'() {
        given:
        stub.replies.launch_job = [200, [result: [job: [id: '42']]]]

        expect:
        new WorkbenchApiClient(stub.apiKeyEnv()).launchJob([cluster: 'k8s', name: 'nf-x']) == '42'
        stub.requests[0].kwparams == [job: [cluster: 'k8s', name: 'nf-x']]
    }

    def 'should list jobs without history for a regular user, active ones winning'() {
        given:
        final client = new WorkbenchApiClient(stub.apiKeyEnv())
        stub.replies.get_historical_session = [401, 'unauthorized']
        stub.replies.get_session = [200, [result: [jobs: [[id: 'a', status: 'Running']]]]]

        expect:
        client.statusMap() == [a: [id: 'a', status: 'Running']]

        when:
        stub.replies.get_historical_session = [200, [result: [jobs: [[id: 'a', status: 'Pending'], [id: 'b', status: 'Finished']]]]]
        final jobs = client.statusMap()

        then:
        jobs.a.status == 'Running'
        jobs.b.status == 'Finished'
        stub.requests[-1].kwparams == [include_all_jobs: true]
    }

    def 'should raise other HTTP errors and API errors'() {
        given:
        final client = new WorkbenchApiClient(stub.apiKeyEnv())
        stub.replies.get_historical_session = [500, 'boom']
        stub.replies.stop_job = [200, [error: [code: 4, message: 'no such job']]]

        when:
        client.statusMap()

        then:
        def e = thrown(WorkbenchApiClient.WorkbenchApiException)
        e.statusCode == 500

        when:
        client.stopJob('x', true)

        then:
        def e2 = thrown(WorkbenchApiClient.WorkbenchApiException)
        e2.message.contains('no such job')
        stub.requests[-1].kwparams == [job_id: 'x', force_quit: true]
    }
}
