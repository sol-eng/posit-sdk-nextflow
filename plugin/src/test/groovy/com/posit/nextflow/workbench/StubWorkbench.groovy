package com.posit.nextflow.workbench

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** A Launcher API stand-in for tests: records requests, answers from `replies` by method. */
class StubWorkbench {

    final List<Map> requests = []
    /** method -> [status, body]; the body is JSON-encoded unless it is a String */
    final Map<String, List> replies = [:]
    private final HttpServer server

    StubWorkbench() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            final body = new JsonSlurper().parse(exchange.requestBody) as Map
            requests << [path: exchange.requestURI.path, headers: exchange.requestHeaders, *: body]
            final reply = replies.get(body.method) ?: [200, [result: null]]
            final bytes = (reply[1] instanceof String ? reply[1] : JsonOutput.toJson(reply[1])).toString().bytes
            exchange.sendResponseHeaders(reply[0] as int, bytes.length)
            exchange.responseBody.withStream { it.write(bytes) }
        }
        server.start()
    }

    String getUrl() { "http://127.0.0.1:${server.address.port}" }

    Map<String, String> apiKeyEnv(String base = '') {
        [WORKBENCH_SERVER: url + base, WORKBENCH_API_KEY: 'secret']
    }

    void stop() { server.stop(0) }
}
