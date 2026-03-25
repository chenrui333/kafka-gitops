package com.devshawn.kafka.gitops.exception;

public class SchemaRegistryExecutionException extends RuntimeException {

    private final String responseBody;

    public SchemaRegistryExecutionException(String message, String responseBody) {
        super(message);
        this.responseBody = responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
