package com.example.sse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@EnableScheduling
@EnableCaching
@SpringBootApplication
public class SseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SseApplication.class, args);
    }

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/").setViewName("index");
                registry.addViewController("/map").setViewName("forward:/map.html");
            }
        };
    }

//    @Scheduled(fixedRate = 1000) // 每秒钟刷新一次数据
//    public void scheduleDataUpdate() {
//        SseEmitterHolder.sendToAll(LocalDateTime.now().toString(), "index");
//    }

}
