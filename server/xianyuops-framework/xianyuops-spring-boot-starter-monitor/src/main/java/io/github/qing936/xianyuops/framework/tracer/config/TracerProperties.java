package io.github.qing936.xianyuops.framework.tracer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BizTracer配置类
 *
 * @author 麻薯
 */
@ConfigurationProperties("xianyuops.tracer")
@Data
public class TracerProperties {
}
