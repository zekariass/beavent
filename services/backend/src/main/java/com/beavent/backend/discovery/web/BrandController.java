package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Brands;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A business's brand page: who it is and how many follow it. Public. */
@RestController
class BrandController {

    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final Brands brands;
    private final RateLimiter rateLimiter;

    BrandController(Brands brands, RateLimiter rateLimiter) {
        this.brands = brands;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/v1/public/businesses/{businessSlug}")
    ResponseEntity<Brands.Brand> brand(@PathVariable String businessSlug, HttpServletRequest request) {
        rateLimiter.hit("brand:ip:" + request.getRemoteAddr(), 240, Duration.ofMinutes(1));
        Brands.Brand brand = brands.brand(businessSlug)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This business is not on Beavent."));
        return ResponseEntity.ok().cacheControl(CACHE).body(brand);
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints brandPublicEndpoints() {
            return () -> List.of("/v1/public/businesses/**");
        }
    }
}
