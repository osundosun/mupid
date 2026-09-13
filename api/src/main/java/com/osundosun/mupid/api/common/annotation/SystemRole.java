package com.osundosun.mupid.api.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * ROLE_SYSTEM, ROLE_ADMIN 만 접근 가능한 API 입니다.
 */
@Target ({ElementType.METHOD, ElementType.TYPE})
@Retention (RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('SYSTEM', 'ADMIN')")
public @interface SystemRole {
    
}
