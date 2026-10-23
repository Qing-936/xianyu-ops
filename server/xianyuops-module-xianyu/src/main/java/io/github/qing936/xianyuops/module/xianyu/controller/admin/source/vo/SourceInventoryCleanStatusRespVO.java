package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 货源库 AI 清洗状态响应 VO")
@Data
public class SourceInventoryCleanStatusRespVO {

    @Schema(description = "任务ID", example = "12345")
    private Long taskId;

    @Schema(description = "状态：0待处理/1进行中/2已完成/3失败", example = "1")
    private Integer status;

    @Schema(description = "进度百分比", example = "66")
    private Integer progress;

    @Schema(description = "总数", example = "3")
    private Integer totalCount;

    @Schema(description = "已完成数量", example = "2")
    private Integer completedCount;

    @Schema(description = "失败数量", example = "0")
    private Integer failedCount;

    @Schema(description = "错误信息", example = "null")
    private String errorMessage;

    @Schema(description = "以货源ID为键的逐项结果，包含needs_review、warnings和持久化图片映射")
    private com.fasterxml.jackson.databind.JsonNode results;
}
