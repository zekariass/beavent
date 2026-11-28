package com.beavent.backend.marketing.web;

import com.beavent.backend.marketing.Marketing;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a person with a message in hand can do without signing in: follow its link (counted), and stop hearing from the
 * business. The codes are long and not guessable; both calls are limited per address.
 */
@RestController
class PublicMarketingController {

    record Stop(String business) {}

    private final Marketing marketing;
    private final RateLimiter rateLimiter;

    PublicMarketingController(Marketing marketing, RateLimiter rateLimiter) {
        this.marketing = marketing;
        this.rateLimiter = rateLimiter;
    }

    /** Counts the click and says where it leads; the web platform turns it into a redirect. */
    @GetMapping("/v1/public/links/{code}")
    Marketing.Target click(@PathVariable String code, HttpServletRequest request) {
        rateLimiter.hit("link:ip:" + request.getRemoteAddr(), 120, Duration.ofMinutes(1));
        return marketing
                .click(code, Instant.now())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This link does not exist."));
    }

    /** Which business a stop link is from (the page asks before stopping, so a link preview cannot do it). */
    @GetMapping("/v1/public/marketing/stop/{token}")
    Stop stopPage(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.hit("stop:ip:" + request.getRemoteAddr(), 60, Duration.ofMinutes(1));
        return new Stop(marketing
                .stopLink(token)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This link does not exist.")));
    }

    @PostMapping("/v1/public/marketing/stop/{token}")
    ResponseEntity<Void> stop(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.hit("stop:ip:" + request.getRemoteAddr(), 60, Duration.ofMinutes(1));
        if (!marketing.stop(token, Instant.now())) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This link does not exist.");
        }
        return ResponseEntity.noContent().build();
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints marketingPublicEndpoints() {
            return () -> List.of("/v1/public/links/**", "/v1/public/marketing/**");
        }
    }
}
