package com.beavent.backend.payments;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ArifPay's checkout inside the integration tests: the real adapter talks to it over HTTP, in ArifPay's documented
 * shapes (SDK types, production Postman paths). Tests decide what the payer did ({@link #pay}, {@link #decline}) and
 * can make it misbehave (create refused, wrong amount).
 */
public class FakeArifPay implements AutoCloseable {

    public static final String KEY = "test-arifpay-key";

    public static final class Session {
        public final String sessionId =
                UUID.randomUUID().toString().replace("-", "").toUpperCase();
        public final String nonce;
        public final BigDecimal total;
        public final String notifyUrl;
        public volatile String status = "NONE"; // NONE until the payer acts, then SUCCESS, FAILED or CANCELLED
        public volatile BigDecimal paidAs; // what the lookup reports instead of the total, when set
        public volatile boolean cancelled;

        Session(String nonce, BigDecimal total, String notifyUrl) {
            this.nonce = nonce;
            this.total = total;
            this.notifyUrl = notifyUrl;
        }

        public long totalMinor() {
            return total.movePointRight(2).longValueExact();
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    private final Map<String, Session> bySession = new ConcurrentHashMap<>();
    private final Map<String, Session> byNonce = new ConcurrentHashMap<>();
    /** The HTTP status create returns instead of opening a session (0: normal). */
    public volatile int failCreateWith = 0;

    public final List<String> calls = new CopyOnWriteArrayList<>();

    public FakeArifPay() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public Session session(String nonce) {
        return byNonce.get(nonce);
    }

    public List<Session> sessions() {
        return new ArrayList<>(bySession.values());
    }

    public long creates() {
        return calls.stream()
                .filter(c -> c.startsWith("POST /api/checkout/session "))
                .count();
    }

    /** The payer approves on ArifPay's page. */
    public void pay(String nonce) {
        Session s = byNonce.get(nonce);
        if (s != null && s.status.equals("NONE")) s.status = "SUCCESS";
    }

    public void decline(String nonce) {
        Session s = byNonce.get(nonce);
        if (s != null && s.status.equals("NONE")) s.status = "FAILED";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(method + " " + path + " " + (body.length() > 0 ? "body" : ""));
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("x-arifpay-key"))) {
            send(exchange, 401, "{\"error\":true,\"msg\":\"Invalid authentication credentials\"}");
            return;
        }
        if (method.equals("POST") && path.equals("/api/checkout/session")) {
            if (failCreateWith > 0) {
                send(exchange, failCreateWith, "failure to get a peer from the ring-balancer");
                return;
            }
            JsonNode in = json.readTree(body);
            String nonce = in.path("nonce").asString();
            if (byNonce.containsKey(nonce)) {
                send(exchange, 200, "{\"error\":true,\"msg\":\"session already exist\",\"data\":null}");
                return;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode item : in.path("items")) {
                total = total.add(item.path("price")
                        .decimalValue()
                        .multiply(BigDecimal.valueOf(item.path("quantity").asInt(1))));
            }
            Session s = new Session(nonce, total, in.path("notifyUrl").asString());
            bySession.put(s.sessionId, s);
            byNonce.put(nonce, s);
            send(
                    exchange,
                    200,
                    "{\"error\":false,\"msg\":\"Created\",\"data\":{\"sessionId\":\"" + s.sessionId
                            + "\",\"paymentUrl\":\"" + url() + "/pay/" + s.sessionId
                            + "\",\"cancelUrl\":\"x\",\"totalAmount\":" + total.toPlainString() + "}}");
            return;
        }
        Session s = null;
        if (method.equals("GET") && path.startsWith("/api/checkout/session/")) {
            s = bySession.get(path.substring("/api/checkout/session/".length()));
        } else if (method.equals("GET") && path.startsWith("/api/checkout/getSessionByNonce/")) {
            s = byNonce.get(path.substring("/api/checkout/getSessionByNonce/".length()));
        } else if (method.equals("POST") && path.startsWith("/api/checkout/session/cancel/")) {
            s = bySession.get(path.substring("/api/checkout/session/cancel/".length()));
            if (s != null && s.status.equals("NONE")) {
                s.cancelled = true;
                s.status = "CANCELLED";
            }
        }
        if (s == null) {
            send(exchange, 404, "{\"error\":true,\"msg\":\"Session not found\",\"data\":null}");
            return;
        }
        BigDecimal reported = s.paidAs != null ? s.paidAs : s.total;
        String transaction = s.status.equals("NONE")
                ? "null"
                : "{\"id\":1,\"transactionId\":\"ATX" + s.nonce.substring(0, 8) + "\",\"transactionStatus\":\""
                        + s.status + "\",\"paymentType\":\"TELEBIRR\"}";
        send(
                exchange,
                200,
                "{\"error\":false,\"msg\":\"Session\",\"data\":{\"uuid\":\"" + s.sessionId + "\",\"sessionId\":\""
                        + s.sessionId + "\",\"nonce\":\"" + s.nonce + "\",\"totalAmount\":" + reported.toPlainString()
                        + ",\"test\":true,\"transaction\":" + transaction + "}}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
