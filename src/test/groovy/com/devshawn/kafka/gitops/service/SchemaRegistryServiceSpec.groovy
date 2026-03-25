package com.devshawn.kafka.gitops.service

import com.devshawn.kafka.gitops.domain.state.settings.SettingsSchemaRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64

class SchemaRegistryServiceSpec extends Specification {

    HttpServer schemaRegistryServer
    String authorizationHeader

    void setup() {
        schemaRegistryServer = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        schemaRegistryServer.createContext('/subjects', this::handleLatestSubject)
        schemaRegistryServer.start()
    }

    void cleanup() {
        schemaRegistryServer?.stop(0)
    }

    void 'sends basic auth when username and password are configured'() {
        setup:
        def settings = new SettingsSchemaRegistry.Builder()
                .setUrl("http://127.0.0.1:${schemaRegistryServer.address.port}")
                .setUsername('schema-user')
                .setPassword('schema-pass')
                .build()
        def service = new SchemaRegistryService(new ObjectMapper(), settings)

        when:
        def latest = service.getLatestSchema('person-value')

        then:
        latest.isEmpty()
        authorizationHeader == 'Basic ' + Base64.encoder.encodeToString('schema-user:schema-pass'.getBytes(StandardCharsets.UTF_8))
    }

    private void handleLatestSubject(HttpExchange exchange) throws IOException {
        authorizationHeader = exchange.requestHeaders.getFirst('Authorization')
        exchange.responseHeaders.add('Content-Type', 'application/vnd.schemaregistry.v1+json')
        byte[] body = '{"error_code":40401,"message":"Subject not found"}'.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(404, body.length)
        exchange.responseBody.write(body)
        exchange.close()
    }
}
