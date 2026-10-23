package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 采集任务响应 VO")
@Data
public class SourceTaskRespVO {

    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "筛选最低价（分）")
    private Integer filterMinPrice;

    @Schema(description = "筛选最高价（分）")
    private Integer filterMaxPrice;

    @Schema(description = "获取条数")
    private Integer fetchCount;

    @Schema(description = "已执行次数")
    private Integer executedCount;

    @Schema(description = "任务状态（0=运行中 1=已暂停 2=已完成 3=提交失败 4=失败）")
    private Integer status;

    @Schema(description = "提交失败原因")
    private String errorMessage;

    @Schema(description = "执行方式")
    private Integer triggerMode;

    @Schema(description = "执行时间（HH:mm）")
    private String scheduleTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "是否可重试")
    private Boolean retryable;

    @Schema(description = "最近一批结果是否为空")
    private Boolean latestExecutionEmpty;
}
