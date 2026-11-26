package com.beavent.backend.commerce.internal;

import com.beavent.backend.platform.PublicEndpoints;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What commerce opens to people who are not signed in. */
@Configuration
class CommerceConfiguration {

    /** A receipt's link opens its printable page: the link is the credential (plan 4.3). */
    @Bean
    PublicEndpoints receiptPublicEndpoints() {
        return () -> List.of("/v1/public/receipts/**");
    }
}
