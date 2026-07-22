package com.rls.gps.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the gateway-established {@link Identity} into a controller method.
 *
 * <p>With {@code required = true} (the default) a request without a complete identity is rejected
 * with 401 before the controller body runs.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentIdentity {

    boolean required() default true;
}
