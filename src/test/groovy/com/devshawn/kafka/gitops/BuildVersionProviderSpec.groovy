package com.devshawn.kafka.gitops

import spock.lang.Specification

import java.util.Properties

class BuildVersionProviderSpec extends Specification {

    void 'version help uses generated build metadata'() {
        setup:
        Properties properties = new Properties()
        getClass().classLoader.getResourceAsStream('kafka-gitops-version.properties').withCloseable { inputStream ->
            assert inputStream != null
            properties.load(inputStream)
        }
        String[] version = new BuildVersionProvider().getVersion()

        expect:
        version as List == [properties.getProperty('version')]
    }
}
