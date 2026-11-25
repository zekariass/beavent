package com.beavent.backend.payments.internal.provider;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A provider stand-in inside the test (the JDK's HTTP server): answers by "METHOD path" with canned JSON in the
 * provider's documented shape and records every request, so adapter tests check exactly what goes over the wire.
 */
final class FakeProvider implements AutoCloseable {

    record Request(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    record Answer(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key =
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(),
                    body));
            Answer answer = answers.getOrDefault(key, new Answer(404, "{\"error\":true,\"msg\":\"Not found\"}"));
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeProvider on(String methodAndPath, int status, String body) {
        answers.put(methodAndPath, new Answer(status, body));
        return this;
    }

    Request last() {
        return requests.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
