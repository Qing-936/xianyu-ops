package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 创建采集任务 Request VO")
@Data
public class SourceTaskCreateReqVO {

    @Schema(description = "商品名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "Steam充值卡")
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称长度不能超过128个字符")
    private String productName;

    @Schema(description = "筛选最低价（分）", example = "1000")
    private Integer filterMinPrice;

    @Schema(description = "筛选最高价（分）", example = "5000")
    private Integer filterMaxPrice;

    @Schema(description = "筛选最小浏览量", example = "100")
    @Min(value = 0, message = "最小浏览量不能小于0")
    private Integer filterViewCountMin;

    @Schema(description = "筛选最大浏览量", example = "1000")
    @Min(value = 0, message = "最大浏览量不能小于0")
    private Integer filterViewCountMax;

    @Schema(description = "筛选最小想要量", example = "10")
    @Min(value = 0, message = "最小想要量不能小于0")
    private Integer filterWantCountMin;

    @Schema(description = "筛选最大想要量", example = "100")
    @Min(value = 0, message = "最大想要量不能小于0")
    private Integer filterWantCountMax;

    @Schema(description = "获取条数", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    @NotNull(message = "获取条数不能为空")
    @Min(value = 1, message = "获取条数最小为1")
    @Max(value = 100, message = "获取条数最大为100")
    private Integer fetchCount;

    @Schema(description = "执行方式（0=一次性, 1=每日定时）", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "执行方式不能为空")
    private Integer triggerMode;

    @Schema(description = "执行时间（HH:mm格式，定时任务时必填）", example = "09:00")
    private String scheduleTime;
}
