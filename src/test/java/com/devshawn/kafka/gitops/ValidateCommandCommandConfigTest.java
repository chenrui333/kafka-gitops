package com.devshawn.kafka.gitops;

import org.junit.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ValidateCommandCommandConfigTest {

    @Test
    public void validateSucceedsWithoutCommandConfig() throws Exception {
        assertValidateExitCode(resourceFile("/plans/simple.yaml"), null);
    }

    @Test
    public void validateSucceedsWithCommandConfig() throws Exception {
        assertValidateExitCode(resourceFile("/plans/simple.yaml"), resourceFile("/command.properties"));
    }

    private void assertValidateExitCode(String stateFile, String commandConfigFile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(out));

        try {
            MainCommand mainCommand = new MainCommand();
            CommandLine cmd = new CommandLine(mainCommand);
            int exitCode;

            if (commandConfigFile == null) {
                exitCode = cmd.execute("-f", stateFile, "validate");
            } else {
                exitCode = cmd.execute("-c", commandConfigFile, "-f", stateFile, "validate");
            }

            assertEquals(0, exitCode);
            assertEquals("[VALID] Successfully validated the desired state file.\n", out.toString());
        } finally {
            System.setOut(oldOut);
        }
    }

    private String resourceFile(String resourcePath) throws URISyntaxException {
        URL resource = getClass().getResource(resourcePath);
        assertNotNull(resourcePath + " should exist", resource);
        File file = Paths.get(resource.toURI()).toFile();
        return file.getAbsolutePath();
    }
}
