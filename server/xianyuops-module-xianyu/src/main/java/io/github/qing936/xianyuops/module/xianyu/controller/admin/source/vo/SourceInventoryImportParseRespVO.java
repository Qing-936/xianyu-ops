package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 货源库批量导入解析预览 VO
 */
@Schema(description = "管理后台 - 货源库批量导入解析预览 VO")
@Data
public class SourceInventoryImportParseRespVO {

    @Schema(description = "Excel 行号")
    private Integer rowNo;

    @Schema(description = "平台（如 ALIBABA_1688 / XIANYU）")
    private String platform;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "原始价格文本（如 0.30-128.50）")
    private String priceText;

    @Schema(description = "商品价格（分）；区间价取最小值")
    private Long price;

    @Schema(description = "商品链接")
    private String link;

    @Schema(description = "外部平台商品 ID（从链接提取，用于去重）")
    private String externalItemId;

    @Schema(description = "图片 URL 列表（原始外链）")
    private List<String> images;

    @Schema(description = "卖家昵称")
    private String seller;

    @Schema(description = "成色")
    private String itemCondition;

    @Schema(description = "功能状态")
    private String functionStatus;

    @Schema(description = "是否已入库（按 externalItemId 或 detailUrl 去重）")
    private Boolean duplicated;

    @Schema(description = "警告信息（如无法解析价格、链接无法解析商品ID等）")
    private List<String> warnings;

}
