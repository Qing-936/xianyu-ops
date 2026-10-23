package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 货源库 AI 清洗提交响应 VO")
@Data
public class SourceInventoryCleanSubmitRespVO {

    @Schema(description = "任务ID", example = "12345")
    private Long taskId;

    @Schema(description = "总数", example = "3")
    private Integer totalCount;

    @Schema(description = "消息", example = "清洗任务已提交")
    private String message;
}
