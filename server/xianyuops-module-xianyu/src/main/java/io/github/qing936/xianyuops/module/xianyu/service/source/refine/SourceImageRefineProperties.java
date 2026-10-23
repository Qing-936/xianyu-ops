package io.github.qing936.xianyuops.module.xianyu.service.source.refine;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 货源图片微调（Dify 工作流）配置属性
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "xianyuops.source.refine")
public class SourceImageRefineProperties {

    private boolean enabled = true;

    /** Dify 基础地址，不包含 /v1（代码自动补）。 */
    @NotBlank
    private String baseUrl = "";

    @ToString.Exclude
    private String apiKey;

    @Min(10)
    @Max(1800)
    private int timeoutSeconds = 300;
}
