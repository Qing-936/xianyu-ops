package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "xianyuops.source.clean")
public class SourceCleanProperties {
    private boolean enabled = false;
    /** Dify 基础地址，不包含 /v1（代码自动补），与其他 Dify 配置保持一致。 */
    @NotBlank
    private String baseUrl = "";
    @ToString.Exclude
    private String apiKey;
    /** 独立 Skill 推荐工作流 API Key；为空时推荐接口降级为人工选择。 */
    @ToString.Exclude
    private String recommendationApiKey;
    @Min(10)
    @Max(1800)
    private int timeoutSeconds = 300;
    /** 只允许下载可信生成服务的图片，不跟随重定向。 */
    private List<String> imageHosts = List.of("ark-acg-cn-beijing.tos-cn-beijing.volces.com");
}
