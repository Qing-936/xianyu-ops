package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 待入库货源任务响应 VO")
@Data
public class SourceTaskPendingInventoryRespVO {

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "该任务最新有数据批次的待入库结果数")
    private Long pendingResultCount;

    @Schema(description = "最新有数据批次号")
    private Integer latestExecutionNo;

    @Schema(description = "最新批次采集时间")
    private LocalDateTime latestExecutionTime;

    @Schema(description = "最后查看的批次号")
    private Integer lastViewedExecutionNo;

    @Schema(description = "全部未入库任务的最新批次待入库结果数合计")
    private Long totalPendingResultCount;
}
