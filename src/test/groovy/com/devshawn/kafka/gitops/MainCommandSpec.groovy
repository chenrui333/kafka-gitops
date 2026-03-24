package com.devshawn.kafka.gitops

import picocli.CommandLine
import spock.lang.Specification

class MainCommandSpec extends Specification {

    void 'unknown option exits non-zero'() {
        expect:
        new CommandLine(new MainCommand()).execute('--bad-option') == 2
    }

    void 'unmatched argument exits non-zero'() {
        expect:
        new CommandLine(new MainCommand()).execute('foo') == 2
    }

    void 'missing subcommand exits non-zero'() {
        expect:
        new CommandLine(new MainCommand()).execute() == 2
    }
}
