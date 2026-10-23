package io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 服务配置服务响应 VO")
@Data
public class ConfigServiceRespVO {

    @Schema(description = "服务编码")
    private String serviceCode;

    @Schema(description = "服务名称")
    private String serviceName;

    @Schema(description = "入口描述")
    private String entryDesc;

    @Schema(description = "图标标识")
    private String icon;

    @Schema(description = "配置文件数量")
    private Integer fileCount;

    @Schema(description = "是否启用（false 表示占位，暂不可编辑）")
    private Boolean enabled;

}
