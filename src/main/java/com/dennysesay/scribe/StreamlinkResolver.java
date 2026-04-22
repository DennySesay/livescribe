package com.dennysesay.scribe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class StreamlinkResolver {
    private final String streamer;
    private final String filename;

    public StreamlinkResolver(String streamer, String filename) {
        this.streamer = streamer;
        this.filename = filename;
    }

    private int runCommand(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            System.out.println("\nExited with code: " + exitCode);
            return exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Process was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + String.join(" ", command), e);
        }
    }

    public void resolve() {
        int exitCode = runCommand(List.of(
                "streamlink",
                "twitch.tv/" + streamer,
                "best",
                "-o",
                filename + ".ts"
        ));
        if (exitCode == 0) {
            convertToMp4();
        }
    }

    public void convertToMp4() {
        int exitCode = runCommand(List.of(
                "ffmpeg",
                "-err_detect", "ignore_err",
                "-i", filename + ".ts",
                "-c", "copy",
                filename + ".mp4"
        ));

        System.out.println("\nExited with code: " + exitCode);
    }

    public void deleteTsFile() {
        // implement deletion if desired after successful conversion
        // Files.deleteIfExists(Path.of(filename + ".ts"));
    }
}
