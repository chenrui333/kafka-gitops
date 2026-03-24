package com.devshawn.kafka.gitops;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class BuildVersionProvider implements IVersionProvider {

    private static final String VERSION_RESOURCE = "kafka-gitops-version.properties";
    private static final String DEFAULT_VERSION = "dev";

    @Override
    public String[] getVersion() {
        String version = readVersionFromResource();
        if (version == null || version.isBlank()) {
            version = BuildVersionProvider.class.getPackage().getImplementationVersion();
        }
        if (version == null || version.isBlank()) {
            version = DEFAULT_VERSION;
        }
        return new String[] { version };
    }

    private String readVersionFromResource() {
        Properties properties = new Properties();
        try (InputStream inputStream = BuildVersionProvider.class.getClassLoader().getResourceAsStream(VERSION_RESOURCE)) {
            if (inputStream == null) {
                return null;
            }
            properties.load(inputStream);
            return properties.getProperty("version");
        } catch (IOException ex) {
            return null;
        }
    }
}
