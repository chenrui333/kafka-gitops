package com.devshawn.kafka.gitops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import picocli.CommandLine
import spock.lang.Specification

import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SchemaRegistryIntegrationSpec extends Specification {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
    private static final String PERSON_SCHEMA = '''
{
  "type": "record",
  "name": "Person",
  "namespace": "example",
  "fields": [
    { "name": "id", "type": "string" }
  ]
}
'''.trim()
    private static final String PERSON_SCHEMA_V2 = '''
{
  "type": "record",
  "name": "Person",
  "namespace": "example",
  "fields": [
    { "name": "id", "type": "string" },
    { "name": "email", "type": "string", "default": "" }
  ]
}
'''.trim()

    HttpServer schemaRegistryServer
    Map<String, Map<String, Object>> latestSchemas = [:]
    List<Map<String, Object>> registrations = []

    void setup() {
        TestUtils.cleanUpCluster()
        schemaRegistryServer = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        schemaRegistryServer.createContext('/subjects', this::handleSchemaRegistryRequest)
        schemaRegistryServer.start()
    }

    void cleanup() {
        schemaRegistryServer?.stop(0)
        TestUtils.cleanUpCluster()
    }

    void 'plans and applies schema subjects from relative schema files'() {
        setup:
        File tempDir = Files.createTempDirectory('schema-registry-plan-').toFile()
        File schemaFile = new File(tempDir, 'person.avsc')
        schemaFile.text = PERSON_SCHEMA
        File stateFile = new File(tempDir, 'state.yaml')
        stateFile.text = '''
settings:
  schemaRegistry:
    url: SCHEMA_REGISTRY_URL
schemas:
  person:
    relativeLocation: person.avsc
'''.replace('SCHEMA_REGISTRY_URL', schemaRegistryUrl())
        File planOutputFile = File.createTempFile('schema-registry-plan-', '.json')

        when:
        int planExitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'plan', '-o', planOutputFile.absolutePath)
        JsonNode plan = OBJECT_MAPPER.readTree(planOutputFile)

        then:
        planExitCode == 0
        plan.path('topicPlans').size() == 0
        plan.path('aclPlans').size() == 0
        plan.path('schemaPlans').size() == 1
        plan.path('schemaPlans').get(0).path('subject').asText() == 'person-value'
        plan.path('schemaPlans').get(0).path('schemaType').asText() == 'AVRO'
        plan.path('schemaPlans').get(0).path('action').asText() == 'ADD'

        when:
        int applyExitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'apply', '-p', planOutputFile.absolutePath)

        then:
        applyExitCode == 0
        registrations.size() == 1
        registrations[0].subject == 'person-value'
        registrations[0].schemaType == 'AVRO'
        normalizeSchema(registrations[0].schema as String) == normalizeSchema(PERSON_SCHEMA)
        (registrations[0].references as List).isEmpty()

        cleanup:
        planOutputFile?.delete()
        tempDir?.deleteDir()
    }

    void 'plans schema updates and registers references'() {
        setup:
        latestSchemas['person-value'] = [
                schemaType: 'AVRO',
                schema    : PERSON_SCHEMA,
                references: [[name: 'common.avsc', subject: 'common-value', version: 3]]
        ]

        File tempDir = Files.createTempDirectory('schema-registry-update-').toFile()
        File schemaFile = new File(tempDir, 'person.avsc')
        schemaFile.text = PERSON_SCHEMA_V2
        File stateFile = new File(tempDir, 'state.yaml')
        stateFile.text = '''
settings:
  schemaRegistry:
    url: SCHEMA_REGISTRY_URL
schemas:
  person:
    relativeLocation: person.avsc
    subjects:
      - person-value
    references:
      - name: common.avsc
        subject: common-value
        version: 3
'''.replace('SCHEMA_REGISTRY_URL', schemaRegistryUrl())
        File planOutputFile = File.createTempFile('schema-registry-update-', '.json')

        when:
        int planExitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'plan', '-o', planOutputFile.absolutePath)
        JsonNode plan = OBJECT_MAPPER.readTree(planOutputFile)

        then:
        planExitCode == 0
        plan.path('schemaPlans').size() == 1
        plan.path('schemaPlans').get(0).path('action').asText() == 'UPDATE'
        plan.path('schemaPlans').get(0).path('references').size() == 1

        when:
        int applyExitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'apply', '-p', planOutputFile.absolutePath)

        then:
        applyExitCode == 0
        registrations.size() == 1
        registrations[0].subject == 'person-value'
        normalizeSchema(registrations[0].schema as String) == normalizeSchema(PERSON_SCHEMA_V2)
        (registrations[0].references as List).size() == 1
        (registrations[0].references as List)[0].subject == 'common-value'
        (registrations[0].references as List)[0].version == 3

        cleanup:
        planOutputFile?.delete()
        tempDir?.deleteDir()
    }

    void 'validate rejects schemas without schema registry settings'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        File tempDir = Files.createTempDirectory('schema-registry-invalid-').toFile()
        File schemaFile = new File(tempDir, 'person.avsc')
        schemaFile.text = PERSON_SCHEMA
        File stateFile = new File(tempDir, 'state.yaml')
        stateFile.text = '''
schemas:
  person:
    relativeLocation: person.avsc
'''

        when:
        int exitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'validate')

        then:
        exitCode == 2
        out.toString().contains('Schema Registry settings are required when schemas are defined.')

        cleanup:
        System.setOut(oldOut)
        tempDir?.deleteDir()
    }

    private void handleSchemaRegistryRequest(HttpExchange exchange) throws IOException {
        String prefix = '/subjects/'
        String path = exchange.requestURI.path
        String suffix = path.substring(prefix.length())

        if (exchange.requestMethod == 'GET' && suffix.endsWith('/versions/latest')) {
            String subject = URLDecoder.decode(suffix.substring(0, suffix.length() - '/versions/latest'.length()), StandardCharsets.UTF_8)
            Map<String, Object> latest = latestSchemas.get(subject)
            if (latest == null) {
                writeJson(exchange, 404, [error_code: 40401, message: 'Subject not found'])
                return
            }

            writeJson(exchange, 200, [
                    subject    : subject,
                    version    : 1,
                    id         : 1,
                    schemaType : latest.schemaType,
                    schema     : latest.schema,
                    references : latest.references,
            ])
            return
        }

        if (exchange.requestMethod == 'POST' && suffix.endsWith('/versions')) {
            String subject = URLDecoder.decode(suffix.substring(0, suffix.length() - '/versions'.length()), StandardCharsets.UTF_8)
            JsonNode requestBody = OBJECT_MAPPER.readTree(exchange.requestBody)
            List<Map<String, Object>> references = []
            requestBody.path('references').forEach(referenceNode -> references.add([
                    name   : referenceNode.path('name').asText(),
                    subject: referenceNode.path('subject').asText(),
                    version: referenceNode.path('version').asInt(),
            ]))

            Map<String, Object> registration = [
                    subject    : subject,
                    schemaType : requestBody.path('schemaType').asText('AVRO'),
                    schema     : requestBody.path('schema').asText(),
                    references : references,
            ]
            registrations.add(registration)
            latestSchemas[subject] = registration
            writeJson(exchange, 200, [id: registrations.size()])
            return
        }

        writeJson(exchange, 404, [message: 'Not found'])
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] response = OBJECT_MAPPER.writeValueAsBytes(body)
        exchange.responseHeaders.add('Content-Type', 'application/vnd.schemaregistry.v1+json')
        exchange.sendResponseHeaders(statusCode, response.length)
        exchange.responseBody.write(response)
        exchange.close()
    }

    private String schemaRegistryUrl() {
        return "http://127.0.0.1:${schemaRegistryServer.address.port}"
    }

    private static String normalizeSchema(String schema) {
        return schema.replace('\r\n', '\n').trim()
    }
}
