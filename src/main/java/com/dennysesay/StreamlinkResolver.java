package com.dennysesay;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class StreamlinkResolver {
    public void resolve(String streamer, String filename) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("streamlink", "twitch.tv/", streamer, "best", "-o", filename + ".ts");
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            int exitCode = process.waitFor();
            System.out.println("\n Exited with code: " + exitCode);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
