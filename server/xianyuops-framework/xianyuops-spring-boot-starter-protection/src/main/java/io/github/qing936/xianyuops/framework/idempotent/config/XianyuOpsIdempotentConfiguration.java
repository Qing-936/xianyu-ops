package io.github.qing936.xianyuops.framework.idempotent.config;

import io.github.qing936.xianyuops.framework.idempotent.core.aop.IdempotentAspect;
import io.github.qing936.xianyuops.framework.idempotent.core.keyresolver.impl.DefaultIdempotentKeyResolver;
import io.github.qing936.xianyuops.framework.idempotent.core.keyresolver.impl.ExpressionIdempotentKeyResolver;
import io.github.qing936.xianyuops.framework.idempotent.core.keyresolver.IdempotentKeyResolver;
import io.github.qing936.xianyuops.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import io.github.qing936.xianyuops.framework.idempotent.core.redis.IdempotentRedisDAO;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import io.github.qing936.xianyuops.framework.redis.config.XianyuOpsRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@AutoConfiguration(after = XianyuOpsRedisAutoConfiguration.class)
public class XianyuOpsIdempotentConfiguration {

    @Bean
    public IdempotentAspect idempotentAspect(List<IdempotentKeyResolver> keyResolvers, IdempotentRedisDAO idempotentRedisDAO) {
        return new IdempotentAspect(keyResolvers, idempotentRedisDAO);
    }

    @Bean
    public IdempotentRedisDAO idempotentRedisDAO(StringRedisTemplate stringRedisTemplate) {
        return new IdempotentRedisDAO(stringRedisTemplate);
    }

    // ========== 各种 IdempotentKeyResolver Bean ==========

    @Bean
    public DefaultIdempotentKeyResolver defaultIdempotentKeyResolver() {
        return new DefaultIdempotentKeyResolver();
    }

    @Bean
    public UserIdempotentKeyResolver userIdempotentKeyResolver() {
        return new UserIdempotentKeyResolver();
    }

    @Bean
    public ExpressionIdempotentKeyResolver expressionIdempotentKeyResolver() {
        return new ExpressionIdempotentKeyResolver();
    }

}
