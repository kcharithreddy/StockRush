package com.flashsale.order.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API. Orders need the {@code buyer} role (and only ever show the caller's
 * own orders); {@code /admin/**} needs the {@code admin} role; actuator is reachable only on the
 * internal management port; everything else is denied.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/orders/**").hasRole("buyer")
                        .requestMatchers("/admin/**").hasRole("admin")
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(new RealmRoles())))
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties props) {
        return TokenDecoders.create(props);
    }
}
