package com.igmo.config;

import com.igmo.domain.GameStartPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class GameStartPolicyConfig {

    @Bean
    @Profile("local")
    public GameStartPolicy localGameStartPolicy() {
        return GameStartPolicy.local();
    }

    @Bean
    @Profile("!local")
    public GameStartPolicy standardGameStartPolicy() {
        return GameStartPolicy.standard();
    }
}
