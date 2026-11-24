package com.beavent.backend.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Selects the {@code beavent_identity} database role: the identity module only (credentials, sessions, OTP
 * challenges). Qualifies {@code DataSource}, {@code DSLContext}, {@code PlatformTransactionManager} and
 * {@code TransactionTemplate} beans.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface IdentityDb {}
