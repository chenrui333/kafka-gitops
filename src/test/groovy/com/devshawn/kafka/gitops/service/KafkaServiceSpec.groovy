package com.devshawn.kafka.gitops.service

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
}
