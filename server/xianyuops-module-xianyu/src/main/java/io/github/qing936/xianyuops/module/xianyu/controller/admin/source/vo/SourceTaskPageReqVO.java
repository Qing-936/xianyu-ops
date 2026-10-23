package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 采集任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceTaskPageReqVO extends PageParam {

    @Schema(description = "商品名称，模糊匹配")
    private String productName;

    @Schema(description = "任务状态（0=运行中, 1=已暂停, 2=已完成）")
    private Integer status;

    @Schema(description = "执行方式（0=一次性, 1=每日定时）")
    private Integer triggerMode;
}
