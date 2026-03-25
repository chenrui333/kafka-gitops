package com.devshawn.kafka.gitops

import com.devshawn.kafka.gitops.config.ManagerConfig
import com.devshawn.kafka.gitops.domain.confluent.ServiceAccount
import com.devshawn.kafka.gitops.domain.state.DesiredState
import com.devshawn.kafka.gitops.domain.state.DesiredStateFile
import com.devshawn.kafka.gitops.service.ConfluentCloudService
import com.devshawn.kafka.gitops.service.ParserService
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

class StateManagerSpec extends Specification {

    void 'customServiceAcls resolve existing Confluent Cloud service accounts by service name (devshawn/kafka-gitops#99)'() {
        given:
        File stateFile = File.createTempFile('state-manager-ccloud', '.yaml')
        stateFile.text = '''
settings:
  ccloud:
    enabled: true

services:
  development-SA-name:
    type: application

customServiceAcls:
  development-SA-name:
    read-only-event-feed:
      name: event.feed
      type: TOPIC
      pattern: PREFIXED
      host: "*"
      principal: User:development-event-feed
      operation: READ
      permission: ALLOW
'''.stripIndent()

        StateManager stateManager = new StateManager(managerConfig(stateFile), new ParserService(stateFile))
        setField(stateManager, 'confluentCloudService', new ConfluentCloudService(new ObjectMapper()) {
            @Override
            List<ServiceAccount> getServiceAccounts() {
                return [
                        new ServiceAccount.Builder()
                                .setId('sa-123456')
                                .setName('development-SA-name')
                                .build()
                ]
            }
        })

        when:
        DesiredState desiredState = invokeGetDesiredState(stateManager)

        then:
        desiredState.getAcls().size() == 1
        desiredState.getAcls().values().first().with {
            getName() == 'event.feed'
            getType() == 'TOPIC'
            getPattern() == 'PREFIXED'
            getHost() == '*'
            getPrincipal() == 'User:sa-123456'
            getOperation() == 'READ'
            getPermission() == 'ALLOW'
        }

        cleanup:
        stateFile.delete()
    }

    private static ManagerConfig managerConfig(File stateFile) {
        return new ManagerConfig.Builder()
                .setVerboseRequested(false)
                .setDeleteDisabled(false)
                .setIncludeUnchangedEnabled(false)
                .setSkipAclsDisabled(false)
                .setStateFile(stateFile)
                .build()
    }

    private static DesiredState invokeGetDesiredState(StateManager stateManager) {
        DesiredStateFile desiredStateFile = stateManager.getAndValidateStateFile()
        def method = StateManager.getDeclaredMethod('getDesiredState', DesiredStateFile)
        method.accessible = true
        return (DesiredState) method.invoke(stateManager, desiredStateFile)
    }

    private static void setField(StateManager stateManager, String fieldName, Object value) {
        def field = StateManager.getDeclaredField(fieldName)
        field.accessible = true
        field.set(stateManager, value)
    }
}
