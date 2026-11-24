package com.beavent.backend.admin.internal;

import com.beavent.backend.platform.SystemDb;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class AdminConfiguration implements WebMvcConfigurer {

    private final DSLContext system;

    AdminConfiguration(@SystemDb DSLContext system) {
        this.system = system;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StaffInterceptor(system)).addPathPatterns("/v1/admin/**");
    }
}
