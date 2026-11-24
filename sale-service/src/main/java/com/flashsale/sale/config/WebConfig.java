package com.flashsale.sale.config;

import com.flashsale.sale.web.UserRateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UserRateLimiter userRateLimiter;

    public WebConfig(UserRateLimiter userRateLimiter) {
        this.userRateLimiter = userRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userRateLimiter).addPathPatterns("/sales/*/buy");
    }
}
