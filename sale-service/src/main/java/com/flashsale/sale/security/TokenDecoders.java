package com.flashsale.sale.security;

import com.nimbusds.jwt.JWTParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.UUID;

/**
 * Builds the JwtDecoder. Every token must have a valid signature, the expected issuer, the
 * {@code stockrush-api} audience, an unexpired {@code exp} and a UUID {@code sub} (the user id).
 *
 * <p>Normally only Keycloak tokens (RS256, keys from its JWKS endpoint) are accepted. When
 * {@code flashsale.security.loadtest-secret} is set, tokens from the {@code stockrush-loadtest}
 * issuer, HMAC-signed with that secret, are accepted too, so k6 can act as 20 000 distinct users.
 * That is switched on only by docker-compose.loadtest.yml.
 */
public final class TokenDecoders {

    public static final String LOADTEST_ISSUER = "stockrush-loadtest";
    private static final int MIN_SECRET_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(TokenDecoders.class);

    private TokenDecoders() {
    }

    public static JwtDecoder create(SecurityProperties props) {
        NimbusJwtDecoder keycloak = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        keycloak.setJwtValidator(validator(props.issuer(), props.audience()));

        String secret = props.loadtestSecret();
        if (secret == null || secret.isBlank()) {
            return keycloak;
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("LOADTEST_JWT_SECRET must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        log.warn("LOAD-TEST TOKEN ISSUER ENABLED: tokens from '{}' are accepted. Never enable this in production.",
                LOADTEST_ISSUER);
        NimbusJwtDecoder loadtest = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        loadtest.setJwtValidator(validator(LOADTEST_ISSUER, props.audience()));

        String keycloakIssuer = props.issuer();
        return token -> {
            String issuer = unverifiedIssuer(token);
            if (keycloakIssuer.equals(issuer)) {
                return keycloak.decode(token);
            }
            if (LOADTEST_ISSUER.equals(issuer)) {
                return loadtest.decode(token);
            }
            throw new BadJwtException("Untrusted issuer");
        };
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : failure("Token is not meant for " + audience),
                jwt -> isUuid(jwt.getSubject())
                        ? OAuth2TokenValidatorResult.success()
                        : failure("Token subject is not a user id"));
    }

    /** Only used to pick a decoder; the chosen decoder then verifies signature and issuer. */
    private static String unverifiedIssuer(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("Malformed token", e);
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
