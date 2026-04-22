package com.dennysesay.config;

public final class Secrets {
    private Secrets() {}

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

    public static String twitchClientId(ConfigReader config) {
        String fromConfig = config.get("twitch.clientId");
        return !isBlank(fromConfig) ? fromConfig : requireEnv("LIVESCRIBE_TWITCH_ID");
    }

    public static String twitchClientSecret(ConfigReader config) {
        String fromConfig = config.get("twitch.clientSecret");
        return !isBlank(fromConfig) ? fromConfig : requireEnv("LIVESCRIBE_TWITCH_SECRET");
    }
}
