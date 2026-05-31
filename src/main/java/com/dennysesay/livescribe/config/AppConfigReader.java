package com.dennysesay.livescribe.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfigReader {
    private final Properties properties = new Properties();

    public AppConfigReader() {
        String resourcePath = "config.properties";

        File localFile = new File(resourcePath);
        if (localFile.exists() && localFile.isFile()) {
            try (InputStream is = new FileInputStream(localFile);
                 var reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
                properties.load(reader);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load configuration from local file: " + localFile.getAbsolutePath(), e);
            }
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AppConfigReader.class.getClassLoader();
        }

        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Configuration resource not found on classpath or working directory: " + resourcePath);
            }
            try (var reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load configuration from classpath resource: " + resourcePath, e);
        }
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
}
