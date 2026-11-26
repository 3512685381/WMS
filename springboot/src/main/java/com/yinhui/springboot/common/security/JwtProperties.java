package com.yinhui.springboot.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("wms.jwt")
public record JwtProperties(@NotBlank String secret, @Positive long expireSeconds) {

    @Override
    public String toString() {
        return "JwtProperties[expireSeconds=" + expireSeconds + "]";
    }
}
