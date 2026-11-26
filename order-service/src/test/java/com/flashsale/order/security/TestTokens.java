package com.flashsale.order.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real signed bearer tokens for web tests, from the load-test issuer. Tests set
 * flashsale.security.loadtest-secret to {@link #SECRET}, so requests go through the actual decoder,
 * role mapping and CSRF handling exactly as a client's would.
 */
public final class TestTokens {

    public static final String SECRET = "test-secret-test-secret-test-secret-42";
    public static final String SECRET_PROPERTY = "flashsale.security.loadtest-secret=" + SECRET;

    private TestTokens() {
    }

    public static String token(UUID user, String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(TokenDecoders.LOADTEST_ISSUER)
                    .audience("stockrush-api")
                    .subject(user.toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adds {@code Authorization: Bearer <token>} for the given user and realm roles. */
    public static RequestPostProcessor bearer(UUID user, String... roles) {
        String token = token(user, roles);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
