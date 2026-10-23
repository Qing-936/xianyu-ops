package io.github.qing936.xianyuops.module.infra.framework.file.config;

import io.github.qing936.xianyuops.module.infra.framework.file.core.client.FileClientFactory;
import io.github.qing936.xianyuops.module.infra.framework.file.core.client.FileClientFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件配置类
 *
 * @author XianyuOps系统
 */
@Configuration(proxyBeanMethods = false)
public class XianyuOpsFileAutoConfiguration {

    @Bean
    public FileClientFactory fileClientFactory() {
        return new FileClientFactoryImpl();
    }

}
