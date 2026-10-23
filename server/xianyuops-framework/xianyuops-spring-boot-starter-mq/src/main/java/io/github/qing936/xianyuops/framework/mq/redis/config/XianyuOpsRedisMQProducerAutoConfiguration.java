package io.github.qing936.xianyuops.framework.mq.redis.config;

import io.github.qing936.xianyuops.framework.mq.redis.core.RedisMQTemplate;
import io.github.qing936.xianyuops.framework.mq.redis.core.interceptor.RedisMessageInterceptor;
import io.github.qing936.xianyuops.framework.redis.config.XianyuOpsRedisAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Redis 消息队列 Producer 配置类
 *
 * @author XianyuOps系统
 */
@Slf4j
@AutoConfiguration(after = XianyuOpsRedisAutoConfiguration.class)
public class XianyuOpsRedisMQProducerAutoConfiguration {

    @Bean
    public RedisMQTemplate redisMQTemplate(StringRedisTemplate redisTemplate,
                                           List<RedisMessageInterceptor> interceptors) {
        RedisMQTemplate redisMQTemplate = new RedisMQTemplate(redisTemplate);
        // 添加拦截器
        interceptors.forEach(redisMQTemplate::addInterceptor);
        return redisMQTemplate;
    }

}
