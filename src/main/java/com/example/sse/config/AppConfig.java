package com.example.sse.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 应用配置类
 */
@Configuration
@EnableCaching
public class AppConfig {

    /**
     * 提供RestTemplate用于HTTP请求
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
} 