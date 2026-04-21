package com.dennysesay;

import java.io.IOException;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        String streamer = "fuslie";
        TwitchClient client = new TwitchClient();
        StreamlinkResolver resolver = new StreamlinkResolver(streamer, streamer + "test");
        boolean response = client.isLive(streamer);

        if (response) {
            System.out.println(streamer + " is live proceeding with download");
            resolver.resolve();
        } else {
            System.out.println("streamer is not live try again later");
        }

//        while (true) {
//        }
    }
}
