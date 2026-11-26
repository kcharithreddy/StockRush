package com.flashsale.sale.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API.
 *
 * <p>CSRF protection stays on for every state-changing request that carries cookies, because cookies are
 * what a browser attaches automatically to a forged cross-site request. This API uses no cookies or
 * sessions: callers authenticate with an {@code Authorization: Bearer} header, which a browser never adds
 * on its own (and the resource server exempts bearer requests from CSRF anyway). Requests without cookies
 * therefore skip the CSRF check and get the proper 401/403 from authorization instead of a CSRF 403.
 * Buying needs a valid Keycloak token with the {@code buyer} role;
 * stock is public; actuator is reachable only on the internal management port; everything else
 * is denied.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(SecurityConfig::carriesCookies))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/sales/*/buy").hasRole("buyer")
                        .requestMatchers(HttpMethod.GET, "/sales/*/stock").permitAll()
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(new RealmRoles())))
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties props) {
        return TokenDecoders.create(props);
    }

    private static boolean carriesCookies(jakarta.servlet.http.HttpServletRequest request) {
        String method = request.getMethod();
        boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method);
        return !safe && request.getHeader("Cookie") != null;
    }
}
