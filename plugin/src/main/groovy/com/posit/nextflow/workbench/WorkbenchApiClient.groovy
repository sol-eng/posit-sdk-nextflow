package com.posit.nextflow.workbench

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * A minimal client for the Posit Workbench Launcher API, for the calls the executor makes:
 * launching, listing and stopping jobs. No dependencies beyond the JDK and Groovy.
 *
 * The API is JSON-RPC over HTTP: {@code POST <server>/api/<method>} with
 * {@code {"method", "kwparams"}}, answered with {@code {"result": ...}} or
 * {@code {"error": {"code", "message"}}}. Credentials, in order:
 * <ul>
 * <li>Inside a Workbench session or job ({@code POSIT_PRODUCT=WORKBENCH} or
 * {@code RS_SERVER_ADDRESS} set): the session's RPC cookie, from
 * {@code $PWB_SESSION_RUNTIME_DIR/rpc_cookie} or else {@code RS_SESSION_RPC_COOKIE} (which is
 * what a Workbench job has), sent as {@code X-RS-Session-Server-RPC-Cookie}, against
 * {@code RS_SERVER_ADDRESS}.</li>
 * <li>Anywhere else: {@code Authorization: Bearer $WORKBENCH_API_KEY} against
 * {@code $WORKBENCH_SERVER}.</li>
 * </ul>
 * The cookie file is read again for every call: the session renews it. A server certificate
 * from a private CA needs the JVM's trust store, e.g. {@code NXF_OPTS=-Djavax.net.ssl.trustStore=...}.
 *
 * The same contract as the Python client in posit-sdk-snakemake
 * ({@code posit_workbench_smk_launcher/workbench.py}).
 */
@CompileStatic
class WorkbenchApiClient {

    static final String COOKIE_HEADER = 'X-RS-Session-Server-RPC-Cookie'
    static final Duration TIMEOUT = Duration.ofSeconds(30)

    static class WorkbenchApiException extends RuntimeException {
        /** the HTTP status, or 0 for an error the API itself reported */
        final int statusCode
        WorkbenchApiException(String message, int statusCode = 0) {
            super(message)
            this.statusCode = statusCode
        }
    }

    /** {@code session} or {@code api-key} */
    final String auth
    final String server
    private final Map<String, String> env
    private final HttpClient http

    WorkbenchApiClient(Map<String, String> env = System.getenv()) {
        this.env = env
        final inSession = env.get('POSIT_PRODUCT') == 'WORKBENCH' || env.get('RS_SERVER_ADDRESS')
        if( inSession && env.get('RS_SERVER_ADDRESS') && sessionCookie() ) {
            auth = 'session'
            server = stripSlash(env.get('RS_SERVER_ADDRESS'))
        }
        else if( env.get('WORKBENCH_SERVER') && env.get('WORKBENCH_API_KEY') ) {
            auth = 'api-key'
            server = stripSlash(env.get('WORKBENCH_SERVER'))
        }
        else {
            throw new WorkbenchApiException(
                'No Workbench credentials: run inside a Workbench session or job, or set ' +
                'WORKBENCH_SERVER and WORKBENCH_API_KEY')
        }
        if( !server.contains('://') )
            throw new WorkbenchApiException("Not a URL: `$server`")
        // HTTP/1.1, as posit-sdk speaks: no h2c upgrade attempt on a plain-http server address
        http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build()
    }

    private static String stripSlash(String url) {
        return url.endsWith('/') ? url.substring(0, url.length() - 1) : url
    }

    private String sessionCookie() {
        final dir = env.get('PWB_SESSION_RUNTIME_DIR')
        if( dir ) {
            try {
                final cookie = Files.readString(Path.of(dir, 'rpc_cookie')).trim()
                if( cookie )
                    return cookie
            }
            catch( IOException ignored ) {
                // no cookie file: e.g. inside a job, which has the cookie in its environment
            }
        }
        return env.get('RS_SESSION_RPC_COOKIE') ?: null
    }

    /** Call a Launcher API method and return its {@code result}. */
    Object call(String method, Map<String, Object> kwparams = [:]) {
        final params = kwparams.findAll { k, v -> v != null }
        final body = JsonOutput.toJson([method: method, kwparams: params])
        final builder = HttpRequest.newBuilder(URI.create("$server/api/$method"))
            .timeout(TIMEOUT)
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if( auth == 'session' ) {
            final cookie = sessionCookie()
            if( !cookie )
                throw new WorkbenchApiException("The session's RPC cookie is no longer available")
            builder.header(COOKIE_HEADER, cookie)
        }
        else {
            builder.header('Authorization', "Bearer ${env.get('WORKBENCH_API_KEY')}")
        }
        final HttpResponse<String> response
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        catch( IOException e ) {
            throw new WorkbenchApiException("Cannot reach $server: ${e.message}")
        }
        if( response.statusCode() < 200 || response.statusCode() >= 300 ) {
            final text = response.body() ?: ''
            throw new WorkbenchApiException(
                "Workbench API `$method` failed: HTTP ${response.statusCode()}: ${text.take(500)}",
                response.statusCode())
        }
        final payload = response.body() ? new JsonSlurper().parseText(response.body()) : [:]
        if( payload instanceof Map ) {
            final map = (Map) payload
            if( map.containsKey('error') ) {
                final error = (map.error ?: [:]) as Map
                throw new WorkbenchApiException(
                    "Workbench API `$method` failed: ${error.message ?: 'error'} (code ${error.code})")
            }
            return map.result
        }
        return null
    }

    /** Launch a job from its camelCase Launcher API spec, and return its id. */
    String launchJob(Map<String, Object> spec) {
        final result = call('launch_job', [job: (Object) spec]) as Map
        final job = result?.job as Map
        final id = job?.id
        if( !id )
            throw new WorkbenchApiException('Workbench did not return a job id')
        return id.toString()
    }

    /**
     * All jobs visible to the credentials, by id. Historical jobs need broader privileges than
     * a regular user's session cookie has: a 401/403 there leaves just the active jobs, which
     * win over historical ones.
     */
    Map<String, Map> statusMap() {
        final jobs = new LinkedHashMap<String, Map>()
        try {
            addJobs(jobs, call('get_historical_session', [include_jobs: (Object) true]))
        }
        catch( WorkbenchApiException e ) {
            if( e.statusCode != 401 && e.statusCode != 403 )
                throw e
        }
        addJobs(jobs, call('get_session', [include_all_jobs: (Object) true]))
        return jobs
    }

    private static void addJobs(Map<String, Map> into, Object result) {
        for( Object job : ((result as Map)?.jobs ?: []) as List ) {
            final map = job as Map
            if( map?.id != null )
                into.put(map.id.toString(), map)
        }
    }

    void stopJob(String jobId, boolean forceQuit) {
        call('stop_job', [job_id: (Object) jobId, force_quit: (Object) forceQuit])
    }

    Map computeEnvs() {
        return (call('get_compute_envs') ?: [:]) as Map
    }
}
