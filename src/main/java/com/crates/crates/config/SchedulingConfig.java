package com.crates.crates.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업(취향 벡터 백필, 만료 토큰 정리)을 켠다.
 *
 * <p>일회성 작업(예: ImageBackfillJob)으로 같은 앱을 띄울 때는 scheduler.enabled=false로 끈다.
 * 운영 backend와 같은 DB에서 같은 주기 작업이 두 벌 돌면 안 되기 때문이다.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
