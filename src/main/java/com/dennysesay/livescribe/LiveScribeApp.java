package com.dennysesay.livescribe;

import picocli.CommandLine;

public class LiveScribeApp {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new LiveScribeCommand()).execute(args);
        System.exit(exitCode);
    }
}
