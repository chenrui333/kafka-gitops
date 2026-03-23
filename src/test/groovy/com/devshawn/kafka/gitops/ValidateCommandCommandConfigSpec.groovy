package com.devshawn.kafka.gitops

import picocli.CommandLine
import spock.lang.Specification

import java.nio.file.Paths

class ValidateCommandCommandConfigSpec extends Specification {

    void 'validate succeeds without command config'() {
        expect:
        executeValidate(resourceFile('/plans/simple.yaml'), null) == 0
    }

    void 'validate succeeds with command config'() {
        expect:
        executeValidate(resourceFile('/plans/simple.yaml'), resourceFile('/command.properties')) == 0
    }

    private static int executeValidate(String stateFile, String commandConfigFile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))

        try {
            MainCommand mainCommand = new MainCommand()
            CommandLine cmd = new CommandLine(mainCommand)
            int exitCode = commandConfigFile == null
                    ? cmd.execute('-f', stateFile, 'validate')
                    : cmd.execute('-c', commandConfigFile, '-f', stateFile, 'validate')

            assert exitCode == 0
            assert out.toString() == '[VALID] Successfully validated the desired state file.\n'
            return exitCode
        } finally {
            System.setOut(oldOut)
        }
    }

    private static String resourceFile(String resourcePath) {
        URL resource = ValidateCommandCommandConfigSpec.getResource(resourcePath)
        assert resource != null : "${resourcePath} should exist"
        return Paths.get(resource.toURI()).toFile().absolutePath
    }
}
