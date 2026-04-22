package com.dennysesay.app;

import com.dennysesay.config.ConfigReader;
import com.dennysesay.config.Secrets;
import com.dennysesay.provider.StreamingClient;
import com.dennysesay.provider.twitch.TwitchClient;
import com.dennysesay.scribe.StreamlinkResolver;

import java.io.IOException;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        ConfigReader config = new ConfigReader();
        String streamer = "fuslie";

        String clientId = Secrets.twitchClientId(config);
        String clientSecret = Secrets.twitchClientSecret(config);

        StreamingClient client = new TwitchClient(clientId, clientSecret);
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
