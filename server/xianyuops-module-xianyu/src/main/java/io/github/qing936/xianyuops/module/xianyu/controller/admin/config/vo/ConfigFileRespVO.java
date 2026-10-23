package io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 配置文件列表项响应 VO")
@Data
public class ConfigFileRespVO {

    @Schema(description = "服务编码")
    private String serviceCode;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "标签：prompt/config/template/data")
    private String tag;

    @Schema(description = "文件用途说明")
    private String description;

    @Schema(description = "是否被人工修改")
    private Boolean modified;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
