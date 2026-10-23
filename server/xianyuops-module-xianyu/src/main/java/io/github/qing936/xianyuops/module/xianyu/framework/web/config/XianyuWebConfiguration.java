package io.github.qing936.xianyuops.module.xianyu.framework.web.config;

import io.github.qing936.xianyuops.framework.swagger.config.XianyuOpsSwaggerAutoConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app 模块的 web 组件的 Configuration
 *
 * @author XianyuOps系统
 */
@Configuration(proxyBeanMethods = false)
public class XianyuWebConfiguration {

    /**
     * app 模块的 API 分组
     */
    @Bean
    public GroupedOpenApi xianyuGroupedOpenApi() {
        return XianyuOpsSwaggerAutoConfiguration.buildGroupedOpenApi("xianyu");
    }

}
