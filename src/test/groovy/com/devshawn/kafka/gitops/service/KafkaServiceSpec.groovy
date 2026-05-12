package com.devshawn.kafka.gitops.service

import com.devshawn.kafka.gitops.config.KafkaGitopsConfig
import com.devshawn.kafka.gitops.exception.ValidationException
import spock.lang.Specification

class KafkaServiceSpec extends Specification {

    void 'describeException prefers nested cause message'() {
        expect:
        KafkaService.describeException(new RuntimeException('outer', new IllegalStateException('inner failure'))) == 'inner failure'
    }

    void 'describeException falls back to outer message when cause is missing'() {
        expect:
        KafkaService.describeException(new RuntimeException('outer failure')) == 'outer failure'
    }

    void 'describeException falls back to throwable toString when messages are blank'() {
        expect:
        KafkaService.describeException(new RuntimeException('')) == 'java.lang.RuntimeException: '
    }

    void 'updateTopicReplication throws ValidationException when replication factor is null'() {
        given:
        KafkaService sut = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build())

        when:
        sut.updateTopicReplication([], 'my-topic', null)

        then:
        ValidationException ex = thrown()
        ex.message.contains('Replication factor must be a positive integer')
    }

    void 'updateTopicReplication throws ValidationException when replication factor is #value'() {
        given:
        KafkaService sut = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build())

        when:
        sut.updateTopicReplication([], 'my-topic', value)

        then:
        ValidationException ex = thrown()
        ex.message.contains('Replication factor must be a positive integer')

        where:
        value << [0, -1, -100]
    }

    void 'addTopicPartition throws IllegalArgumentException when partition count is #value'() {
        given:
        KafkaService sut = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build())

        when:
        sut.addTopicPartition('my-topic', value)

        then:
        thrown(IllegalArgumentException)

        where:
        value << [0, -1, -100]
    }
}
