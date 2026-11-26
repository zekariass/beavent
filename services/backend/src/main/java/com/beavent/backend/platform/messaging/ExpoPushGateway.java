package com.beavent.backend.platform.messaging;

import com.beavent.backend.platform.PushGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends pushes through the Expo push service ({@code beavent.push.provider=expo}), which delivers to Apple and Google
 * with the credentials kept in the Expo project (no keys in this service; an access token is only needed when the
 * project turns on enhanced push security: {@code EXPO_PUSH_ACCESS_TOKEN}). Tokens the service reports as
 * {@code DeviceNotRegistered} are returned as gone. Receipts are not polled yet: a device that went away after Expo
 * accepted the message is found by the next send.
 */
@Component
@ConditionalOnProperty(name = "beavent.push.provider", havingValue = "expo")
public class ExpoPushGateway implements PushGateway {

    private static final int BATCH = 100;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String url;
    private final String accessToken;

    ExpoPushGateway(
            JsonMapper json,
            @Value("${beavent.push.expo-url:https://exp.host/--/api/v2/push/send}") String url,
            @Value("${beavent.push.expo-access-token:}") String accessToken) {
        this.json = json;
        this.url = url;
        this.accessToken = accessToken;
    }

    @Override
    public Map<String, Outcome> send(List<String> tokens, Message message) {
        Map<String, Outcome> result = new HashMap<>();
        List<String> valid = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith("ExponentPushToken[") || token.startsWith("ExpoPushToken[")) {
                valid.add(token);
            } else {
                result.put(token, Outcome.DEVICE_GONE);
            }
        }
        for (int from = 0; from < valid.size(); from += BATCH) {
            List<String> batch = valid.subList(from, Math.min(from + BATCH, valid.size()));
            List<Map<String, Object>> body = new ArrayList<>();
            for (String token : batch) {
                Map<String, Object> m = new HashMap<>();
                m.put("to", token);
                m.put("title", message.title());
                m.put("body", message.body());
                m.put("data", message.data());
                m.put("sound", "default");
                m.put("channelId", "default");
                body.add(m);
            }
            JsonNode tickets = post(body);
            for (int i = 0; i < batch.size(); i++) {
                JsonNode ticket = tickets.get(i);
                boolean gone = ticket != null
                        && "error".equals(ticket.path("status").asString())
                        && "DeviceNotRegistered"
                                .equals(ticket.path("details").path("error").asString());
                result.put(batch.get(i), gone ? Outcome.DEVICE_GONE : Outcome.SENT);
            }
        }
        return result;
    }

    private JsonNode post(List<Map<String, Object>> body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (!accessToken.isBlank()) {
                request.header("Authorization", "Bearer " + accessToken);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Expo push service answered " + response.statusCode());
            }
            JsonNode data = json.readTree(response.body()).path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("Expo push service sent no tickets");
            }
            return data;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Expo push service unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending a push", e);
        }
    }
}
