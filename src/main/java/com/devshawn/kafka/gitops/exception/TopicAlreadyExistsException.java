package com.devshawn.kafka.gitops.exception;

public class TopicAlreadyExistsException extends KafkaExecutionException {

    public TopicAlreadyExistsException(String topicName, String exceptionMessage) {
        super(String.format("Kafka topic already exists: %s", topicName), exceptionMessage);
    }
}
