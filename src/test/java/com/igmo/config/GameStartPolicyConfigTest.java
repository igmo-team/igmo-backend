package com.igmo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.igmo.domain.GameStartPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GameStartPolicyConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GameStartPolicyConfig.class);

    @Test
    @DisplayName("local 프로파일에서는 1명 시작 정책을 등록한다.")
    void local_프로파일에서는_1명_시작_정책을_등록한다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    GameStartPolicy policy = context.getBean(GameStartPolicy.class);

                    assertThat(policy.minimumPlayers()).isOne();
                });
    }

    @Test
    @DisplayName("prod 프로파일에서는 3명 시작 정책을 등록한다.")
    void prod_프로파일에서는_3명_시작_정책을_등록한다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    GameStartPolicy policy = context.getBean(GameStartPolicy.class);

                    assertThat(policy.minimumPlayers()).isEqualTo(3);
                });
    }
}
