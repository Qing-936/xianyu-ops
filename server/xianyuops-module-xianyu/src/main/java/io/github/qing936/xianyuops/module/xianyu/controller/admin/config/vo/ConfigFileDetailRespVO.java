package io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 配置文件详情响应 VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigFileDetailRespVO extends ConfigFileRespVO {

    @Schema(description = "当前内容")
    private String content;

}
