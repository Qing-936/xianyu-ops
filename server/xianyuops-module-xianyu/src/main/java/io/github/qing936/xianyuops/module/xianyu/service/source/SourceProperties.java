package io.github.qing936.xianyuops.module.xianyu.service.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 货源采集（Python 服务）配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "xianyuops.source")
public class SourceProperties {

    /**
     * Python 采集服务基础地址（默认 http://127.0.0.1:8002）
     */
    private String baseUrl = "http://127.0.0.1:8002";

    /**
     * HTML 渲染代理访问令牌；未配置时拒绝所有请求
     */
    private String htmlRenderApiKey = "";

    /**
     * 请求超时时间（秒）
     */
    private int timeoutSeconds = 30;
}
