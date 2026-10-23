package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 拉黑卖家保存 Request VO")
@Data
public class SourceBlockedSellerSaveReqVO {

    @Schema(description = "记录ID（更新时必填）")
    private Long id;

    @Schema(description = "卖家ID（优先匹配键，可空）")
    private String sellerId;

    @Schema(description = "卖家名称/昵称")
    private String sellerName;

    @Schema(description = "拉黑原因")
    private String blockReason;

    @Schema(description = "来源任务ID（从采集结果拉黑时携带）")
    private Long sourceTaskId;

    @Schema(description = "采集详情ID（从采集结果拉黑时携带，用于补全 sellerId/sellerName）")
    private Long detailId;

}
