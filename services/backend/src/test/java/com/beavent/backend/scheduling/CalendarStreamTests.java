package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Live calendar updates (plan step 2.5): a screen watching a location hears about every change there. */
class CalendarStreamTests extends SchedulingTestSupport {

    static void waitUntil(String what, java.util.function.BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    @Test
    void aWatchingScreenHearsAboutBookingsAndBlocks() throws Exception {
        Shop shop = shop();
        Person desk = newMember(shop.salon(), "RECEPTIONIST");
        MvcResult stream = mvc.perform(get(shop.salon()
                                .path("/calendar/stream?locationId="
                                        + shop.salon().location1()))
                        .header("Authorization", "Bearer " + desk.token()))
                .andExpect(request().asyncStarted())
                .andReturn();
        waitUntil("ready", () -> content(stream).contains("event:ready"));

        // A booking made online for this location reaches the stream.
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), shop.abebe())));
        body.put("start", at(day(), "10:00").toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), body, null);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", held.text("holdId"),
                        "holderKey", held.text("holderKey")),
                newPerson().token());
        assertThat(booked.status()).isEqualTo(201);
        waitUntil("the booking", () -> content(stream).contains("appointment.booked"));

        // So do blocks, added and removed.
        Call block = call(
                post(shop.salon().path("/blocks")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "staffId", shop.sara(),
                        "start", at(day(), "12:00").toInstant().toString(),
                        "end", at(day(), "13:00").toInstant().toString(),
                        "reason", "BREAK"),
                desk.token());
        assertThat(block.status()).isEqualTo(201);
        waitUntil("the block", () -> content(stream).contains("calendar.block_changed"));
        int before = content(stream).split("calendar.block_changed", -1).length;
        call(delete(shop.salon().path("/blocks/" + block.text("id"))), null, desk.token());
        waitUntil("the removal", () -> content(stream).split("calendar.block_changed", -1).length > before);

        // Another business's location is not there to watch.
        Salon other = newSalon("Elsewhere");
        assertThat(mvc.perform(get(other.path("/calendar/stream?locationId="
                                        + shop.salon().location1()))
                                .header(
                                        "Authorization",
                                        "Bearer " + other.owner().token()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    static String content(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
