package com.flashsale.order.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** The caller's user id is the token subject, already validated as a UUID by {@link TokenDecoders}. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
