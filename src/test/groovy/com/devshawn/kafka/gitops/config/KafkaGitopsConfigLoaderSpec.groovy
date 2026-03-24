package com.devshawn.kafka.gitops.config

import com.devshawn.kafka.gitops.exception.MissingConfigurationException
import com.devshawn.kafka.gitops.exception.ValidationException
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import spock.lang.Specification

class KafkaGitopsConfigLoaderSpec extends Specification {

    void 'test username and password shortcut'() {
        when:
        KafkaGitopsConfig config = KafkaGitopsConfigLoader.load(null, testEnvironment([
                KAFKA_SASL_JAAS_USERNAME: 'test',
                KAFKA_SASL_JAAS_PASSWORD: 'test-secret',
        ]))

        then:
        config
        config.config.get(SaslConfigs.SASL_JAAS_CONFIG) == "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"test\" password=\"test-secret\";"
    }

    void 'test escaping username and password shortcut'() {
        when:
        KafkaGitopsConfig config = KafkaGitopsConfigLoader.load(null, testEnvironment([
                KAFKA_SASL_JAAS_USERNAME: 'te"st',
                KAFKA_SASL_JAAS_PASSWORD: 'te"st-secr"et',
        ]))

        then:
        config
        config.config.get(SaslConfigs.SASL_JAAS_CONFIG) == "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"te\\\"st\" password=\"te\\\"st-secr\\\"et\";"
    }

    void 'test command config file'() {
        setup:
        File configFile = new File(getClass().getResource("/command.properties").toURI())

        when:
        KafkaGitopsConfig config = KafkaGitopsConfigLoader.load(configFile)

        then:
        config.config.get(CommonClientConfigs.CLIENT_ID_CONFIG) == "kafka-gitops"
        config.config.get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG) == "localhost:9092"
        config.config.get(SaslConfigs.SASL_MECHANISM) == "PLAIN"
    }

    void 'test config logging redacts sensitive values'() {
        when:
        Map<String, Object> sanitized = KafkaGitopsConfigLoader.sanitizeConfigForLogging([
                (CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG): 'localhost:9092',
                (SaslConfigs.SASL_JAAS_CONFIG)                : 'secret-value',
                'ssl.keystore.password'                      : 'keystore-secret',
                'basic.auth.user.info'                       : 'user:password',
        ])

        then:
        sanitized.get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG) == 'localhost:9092'
        sanitized.get(SaslConfigs.SASL_JAAS_CONFIG) == '[REDACTED]'
        sanitized.get('ssl.keystore.password') == '[REDACTED]'
        sanitized.get('basic.auth.user.info') == '[REDACTED]'
    }

    void 'test command config file must be readable'() {
        when:
        KafkaGitopsConfigLoader.load(new File('missing-command.properties'), testEnvironment())

        then:
        ValidationException ex = thrown(ValidationException)
        ex.message.contains('The specified command config file could not be read: missing-command.properties')
    }

    void 'test username and password shortcut requires sasl mechanism'() {
        when:
        KafkaGitopsConfigLoader.load(null, [
                KAFKA_BOOTSTRAP_SERVERS : 'localhost:9092',
                KAFKA_SECURITY_PROTOCOL : 'SASL_PLAINTEXT',
                KAFKA_SASL_JAAS_USERNAME: 'test',
                KAFKA_SASL_JAAS_PASSWORD: 'test-secret',
        ])

        then:
        MissingConfigurationException ex = thrown(MissingConfigurationException)
        ex.message == 'Missing required configuration: KAFKA_SASL_MECHANISM'
    }

    private static Map<String, String> testEnvironment(Map<String, String> overrides = [:]) {
        return [
                KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
                KAFKA_SASL_MECHANISM   : 'PLAIN',
                KAFKA_SECURITY_PROTOCOL: 'SASL_PLAINTEXT',
        ] + overrides
    }
}
