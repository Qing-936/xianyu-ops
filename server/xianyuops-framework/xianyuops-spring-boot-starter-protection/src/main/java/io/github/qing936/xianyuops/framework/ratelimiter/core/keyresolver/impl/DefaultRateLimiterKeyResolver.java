package io.github.qing936.xianyuops.framework.ratelimiter.core.keyresolver.impl;

import cn.hutool.crypto.SecureUtil;
import io.github.qing936.xianyuops.framework.common.util.string.StrUtils;
import io.github.qing936.xianyuops.framework.ratelimiter.core.annotation.RateLimiter;
import io.github.qing936.xianyuops.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import org.aspectj.lang.JoinPoint;

/**
 * 默认（全局级别）限流 Key 解析器，使用方法名 + 方法参数，组装成一个 Key
 *
 * 为了避免 Key 过长，使用 MD5 进行“压缩”
 *
 * @author XianyuOps系统
 */
public class DefaultRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String methodName = joinPoint.getSignature().toString();
        String argsStr = StrUtils.joinMethodArgs(joinPoint);
        return SecureUtil.md5(methodName + argsStr);
    }

}
