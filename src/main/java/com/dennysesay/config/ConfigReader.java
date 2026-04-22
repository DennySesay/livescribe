package com.dennysesay.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigReader {
    private final Properties properties = new Properties();

    public ConfigReader() {
        String resourcePath = "config.properties";

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ConfigReader.class.getClassLoader();
        }

        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Configuration resource not found on classpath: " + resourcePath);
            }
            properties.load(is);
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
