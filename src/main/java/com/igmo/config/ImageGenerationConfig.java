package com.igmo.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageGenerationConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService imageGenerationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
