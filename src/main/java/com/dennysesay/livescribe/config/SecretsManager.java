package com.dennysesay.livescribe.config;

public final class SecretsManager {
    private SecretsManager() {}

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (isBlank(value)) {
            throw new IllegalStateException("Environment variable " + name + " is missing or blank.");
        }
        return value;
    }

    public static String getTwitchClientId(AppConfigReader configReader) {
        String fromConfig = configReader.get("twitch.clientId");
        return !isBlank(fromConfig) ? fromConfig : requireEnv("LIVESCRIBE_TWITCH_ID");
    }

    public static String getTwitchClientSecret(AppConfigReader configReader) {
        String fromConfig = configReader.get("twitch.clientSecret");
        return !isBlank(fromConfig) ? fromConfig : requireEnv("LIVESCRIBE_TWITCH_SECRET");
    }
}
