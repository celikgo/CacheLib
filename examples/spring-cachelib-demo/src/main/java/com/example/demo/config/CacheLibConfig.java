package com.example.demo.config;

import com.example.demo.client.CacheLibClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheLibConfig {

    @Value("${cachelib.host:localhost}")
    private String host;

    @Value("${cachelib.port:50051}")
    private int port;

    @Bean
    public CacheLibClient cacheLibClient() {
        return new CacheLibClient(host, port);
    }
}
