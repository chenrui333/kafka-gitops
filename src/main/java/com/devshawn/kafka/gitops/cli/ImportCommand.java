package com.devshawn.kafka.gitops.cli;

import com.devshawn.kafka.gitops.MainCommand;
import com.devshawn.kafka.gitops.StateManager;
import com.devshawn.kafka.gitops.config.ManagerConfig;
import com.devshawn.kafka.gitops.domain.state.DesiredStateFile;
import com.devshawn.kafka.gitops.exception.KafkaExecutionException;
import com.devshawn.kafka.gitops.exception.MissingConfigurationException;
import com.devshawn.kafka.gitops.exception.ValidationException;
import com.devshawn.kafka.gitops.service.ParserService;
import com.devshawn.kafka.gitops.util.LogUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "import", description = "Import current Kafka topics and ACLs into a bootstrap state file.")
public class ImportCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Write the imported state file to this path instead of stdout.")
    private File outputFile;

    @CommandLine.ParentCommand
    private MainCommand parent;

    @Override
    public Integer call() {
        try {
            StateManager stateManager = new StateManager(generateStateManagerConfig(), new ParserService(parent.getStateFile()));
            DesiredStateFile desiredStateFile = stateManager.importState();
            String yaml = toYaml(desiredStateFile);

            if (outputFile != null) {
                Files.writeString(outputFile.toPath(), yaml);
                LogUtil.printSimpleSuccess(String.format("Wrote imported state file to: %s", outputFile));
            } else {
                System.out.print(yaml);
            }
            return 0;
        } catch (MissingConfigurationException ex) {
            LogUtil.printGenericError(ex);
        } catch (ValidationException ex) {
            LogUtil.printValidationResult(ex.getMessage(), false);
        } catch (KafkaExecutionException ex) {
            LogUtil.printKafkaExecutionError(ex);
        } catch (IOException ex) {
            LogUtil.printSimpleError(String.format("Could not write imported state file: %s", ex.getMessage()));
        }
        return 2;
    }

    private ManagerConfig generateStateManagerConfig() {
        return new ManagerConfig.Builder()
                .setVerboseRequested(parent.isVerboseRequested())
                .setDeleteDisabled(parent.isDeleteDisabled())
                .setIncludeUnchangedEnabled(false)
                .setSkipAclsDisabled(parent.areAclsDisabled())
                .setNullableConfigFile(parent.getConfigFile())
                .setStateFile(parent.getStateFile())
                .build();
    }

    private String toYaml(DesiredStateFile desiredStateFile) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return objectMapper.writeValueAsString(desiredStateFile);
    }
}
