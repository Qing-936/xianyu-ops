package io.github.qing936.xianyuops.module.xianyu.framework.security.config;

import io.github.qing936.xianyuops.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * App 模块的 Security 配置
 */
@Configuration(proxyBeanMethods = false, value = "xianyuSecurityConfiguration")
public class XianyuSecurityConfiguration {

    @Bean("xianyuAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer authorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {

            @Override
            public void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                // 闲鱼机器 Agent 专用接口，X-Agent-Token 校验由 XianyuAgentTokenFilter 负责
                registry.requestMatchers("/agent-api/**").permitAll();
            }

        };
    }

}
