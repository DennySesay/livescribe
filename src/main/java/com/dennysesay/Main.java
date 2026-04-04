package com.dennysesay;

import java.io.IOException;
import java.net.http.HttpResponse;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        TwitchClient client = new TwitchClient();
        HttpResponse<String> response = client.isLive("zhuvely95");

        System.out.println("HTTP " + response.statusCode());
        System.out.println(response.body());
    }
}
