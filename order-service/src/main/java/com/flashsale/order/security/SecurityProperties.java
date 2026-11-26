package com.flashsale.order.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** flashsale.security.* — see application.yml. */
@ConfigurationProperties("flashsale.security")
public record SecurityProperties(String issuer, String jwkSetUri, String audience, String loadtestSecret) {
}
