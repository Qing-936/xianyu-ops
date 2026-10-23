package io.github.qing936.xianyuops.framework.banner.config;

import io.github.qing936.xianyuops.framework.banner.core.BannerApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Banner 的自动配置类
 *
 * @author XianyuOps系统
 */
@AutoConfiguration
public class XianyuOpsBannerAutoConfiguration {

    @Bean
    public BannerApplicationRunner bannerApplicationRunner() {
        return new BannerApplicationRunner();
    }

}
