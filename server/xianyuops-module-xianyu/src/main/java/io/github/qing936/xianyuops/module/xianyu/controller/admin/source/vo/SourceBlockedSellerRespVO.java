package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 拉黑卖家响应 VO")
@Data
public class SourceBlockedSellerRespVO {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "卖家ID")
    private String sellerId;

    @Schema(description = "卖家名称/昵称")
    private String sellerName;

    @Schema(description = "拉黑原因")
    private String blockReason;

    @Schema(description = "来源任务ID（手动添加时为空）")
    private Long sourceTaskId;

    @Schema(description = "状态（0=已解除 1=生效中）")
    private Integer status;

    @Schema(description = "拉黑时间")
    private LocalDateTime blockTime;

    @Schema(description = "解除时间")
    private LocalDateTime unblockTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
