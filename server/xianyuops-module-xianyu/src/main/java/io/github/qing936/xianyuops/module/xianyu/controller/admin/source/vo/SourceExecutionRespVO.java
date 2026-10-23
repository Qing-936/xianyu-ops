package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 批次汇总响应 VO")
@Data
public class SourceExecutionRespVO {

    @Schema(description = "批次号")
    private Integer executionNo;

    @Schema(description = "搜索关键词")
    private String keyword;

    @Schema(description = "获取条数")
    private Integer fetchCount;

    @Schema(description = "有效数")
    private Integer validCount;

    @Schema(description = "最低价（分）")
    private Integer minPrice;

    @Schema(description = "最高价（分）")
    private Integer maxPrice;

    @Schema(description = "平均价（分）")
    private Integer avgPrice;

    @Schema(description = "价格指数")
    private Integer priceIndex;

    @Schema(description = "执行时间")
    private LocalDateTime executedAt;

    @Schema(description = "批次状态")
    private Integer status;
}
