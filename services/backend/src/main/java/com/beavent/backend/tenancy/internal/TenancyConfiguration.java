package com.beavent.backend.tenancy.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenancyProperties.class)
class TenancyConfiguration implements WebMvcConfigurer {

    private final Memberships memberships;

    TenancyConfiguration(Memberships memberships) {
        this.memberships = memberships;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OrgContextInterceptor(memberships)).addPathPatterns("/v1/orgs/*/**");
    }
}
