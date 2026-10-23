package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.github.qing936.xianyuops.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 拉黑卖家分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceBlockedSellerPageReqVO extends PageParam {

    @Schema(description = "卖家名称/昵称，模糊匹配")
    private String sellerName;

    @Schema(description = "状态（0=已解除 1=生效中）")
    private Integer status;

    @Schema(description = "来源任务ID")
    private Long sourceTaskId;

}
