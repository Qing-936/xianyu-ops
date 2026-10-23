package io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 保存配置文件请求 VO")
@Data
public class ConfigFileSaveReqVO {

    @Schema(description = "服务编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "服务编码不能为空")
    private String serviceCode;

    @Schema(description = "文件名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @Schema(description = "文件内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "文件内容不能为空")
    private String content;

}
