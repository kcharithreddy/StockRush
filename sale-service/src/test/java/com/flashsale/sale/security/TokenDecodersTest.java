package com.flashsale.sale.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every rule a token must pass, exercised through the load-test issuer (HMAC, no Keycloak needed). */
class TokenDecodersTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final SecurityProperties WITH_LOADTEST = new SecurityProperties(
            "http://localhost:8180/realms/stockrush", "http://127.0.0.1:1/certs", "stockrush-api", SECRET);

    private final JwtDecoder decoder = TokenDecoders.create(WITH_LOADTEST);

    private static String token(String secret, Consumer<JWTClaimsSet.Builder> customise) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(TokenDecoders.LOADTEST_ISSUER)
                .audience("stockrush-api")
                .subject(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        customise.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void acceptsAValidLoadTestToken() throws Exception {
        UUID user = UUID.randomUUID();
        Jwt jwt = decoder.decode(token(SECRET, c -> c.subject(user.toString())));
        assertThat(CurrentUser.id(jwt)).isEqualTo(user);
    }

    @Test
    void rejectsWrongSignature() {
        assertThatThrownBy(() -> decoder.decode(token("another-secret-another-secret-xx", c -> { })))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudience() {
        assertThatThrownBy(() -> decoder.decode(token(SECRET, c -> c.audience("account"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        assertThatThrownBy(() -> decoder.decode(token(SECRET,
                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(600))))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSubjectThatIsNotAUserId() {
        assertThatThrownBy(() -> decoder.decode(token(SECRET, c -> c.subject("42"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnknownIssuer() {
        assertThatThrownBy(() -> decoder.decode(token(SECRET, c -> c.issuer("https://evil.example"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
    }

    @Test
    void loadTestIssuerIsOffWithoutASecret() throws Exception {
        JwtDecoder keycloakOnly = TokenDecoders.create(new SecurityProperties(
                "http://localhost:8180/realms/stockrush", "http://127.0.0.1:1/certs", "stockrush-api", ""));
        String loadtestToken = token(SECRET, c -> { });
        assertThatThrownBy(() -> keycloakOnly.decode(loadtestToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesAShortSecret() {
        assertThatThrownBy(() -> TokenDecoders.create(new SecurityProperties(
                "http://localhost:8180/realms/stockrush", "http://127.0.0.1:1/certs", "stockrush-api", "short")))
                .isInstanceOf(IllegalStateException.class);
    }
}
