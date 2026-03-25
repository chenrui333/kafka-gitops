package com.devshawn.kafka.gitops.service;

import com.devshawn.kafka.gitops.domain.state.SchemaReference;
import com.devshawn.kafka.gitops.domain.state.settings.SettingsSchemaRegistry;
import com.devshawn.kafka.gitops.exception.SchemaRegistryExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SchemaRegistryService {

    private static final String JSON_CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Optional<String> authorizationHeader;

    public SchemaRegistryService(ObjectMapper objectMapper, SettingsSchemaRegistry settingsSchemaRegistry) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(settingsSchemaRegistry.getUrl());
        this.authorizationHeader = buildAuthorizationHeader(settingsSchemaRegistry);
    }

    public Optional<RegisteredSchema> getLatestSchema(String subject) {
        HttpRequest request = baseRequest(subjectPath(subject) + "/versions/latest")
                .GET()
                .build();

        HttpResponse<String> response = send(request, String.format("Error reading schema subject: %s", subject));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SchemaRegistryExecutionException(
                    String.format("Schema Registry returned %s while reading schema subject: %s", response.statusCode(), subject),
                    response.body());
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            return Optional.of(new RegisteredSchema(
                    subject,
                    node.path("schemaType").asText("AVRO"),
                    node.path("schema").asText(),
                    parseReferences(node.path("references"))));
        } catch (JsonProcessingException ex) {
            throw new SchemaRegistryExecutionException(
                    String.format("Schema Registry returned invalid JSON while reading schema subject: %s", subject),
                    response.body());
        }
    }

    public void registerSchema(String subject, String schemaType, String schema, List<SchemaReference> references) {
        HttpRequest request = baseRequest(subjectPath(subject) + "/versions")
                .POST(HttpRequest.BodyPublishers.ofString(toRegistrationBody(schemaType, schema, references)))
                .build();

        HttpResponse<String> response = send(request, String.format("Error registering schema subject: %s", subject));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SchemaRegistryExecutionException(
                    String.format("Schema Registry returned %s while registering schema subject: %s", response.statusCode(), subject),
                    response.body());
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", JSON_CONTENT_TYPE)
                .header("Content-Type", JSON_CONTENT_TYPE);
        authorizationHeader.ifPresent(value -> builder.header("Authorization", value));
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request, String errorMessage) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new SchemaRegistryExecutionException(errorMessage, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SchemaRegistryExecutionException(errorMessage, ex.getMessage());
        }
    }

    private String toRegistrationBody(String schemaType, String schema, List<SchemaReference> references) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaType", schemaType);
        payload.put("schema", schema);
        if (!references.isEmpty()) {
            List<Map<String, Object>> serializedReferences = new ArrayList<>();
            references.forEach(reference -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("name", reference.getName());
                value.put("subject", reference.getSubject());
                value.put("version", reference.getVersion());
                serializedReferences.add(value);
            });
            payload.put("references", serializedReferences);
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new SchemaRegistryExecutionException("Could not serialize schema registration payload", ex.getMessage());
        }
    }

    private List<SchemaReference> parseReferences(JsonNode referencesNode) {
        if (!referencesNode.isArray()) {
            return List.of();
        }

        List<SchemaReference> references = new ArrayList<>();
        referencesNode.forEach(referenceNode -> references.add(new SchemaReference.Builder()
                .setName(referenceNode.path("name").asText())
                .setSubject(referenceNode.path("subject").asText())
                .setVersion(referenceNode.path("version").asInt())
                .build()));
        return references;
    }

    private Optional<String> buildAuthorizationHeader(SettingsSchemaRegistry settingsSchemaRegistry) {
        if (settingsSchemaRegistry.getUsername().isEmpty()) {
            return Optional.empty();
        }

        String credentials = String.format("%s:%s",
                settingsSchemaRegistry.getUsername().get(),
                settingsSchemaRegistry.getPassword().orElse(""));
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return Optional.of(String.format("Basic %s", encoded));
    }

    private String normalizeBaseUrl(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String subjectPath(String subject) {
        return "/subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8);
    }

    public record RegisteredSchema(String subject, String schemaType, String schema, List<SchemaReference> references) {
    }
}
