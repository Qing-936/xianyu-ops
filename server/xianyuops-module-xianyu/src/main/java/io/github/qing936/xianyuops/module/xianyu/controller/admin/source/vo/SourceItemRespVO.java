package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 搜索结果响应 VO")
@Data
public class SourceItemRespVO {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "商品名称")
    private String title;

    @Schema(description = "卖家")
    private String sellerName;

    @Schema(description = "价格（分）")
    private Integer price;

    @Schema(description = "获取时间")
    private LocalDateTime createTime;

    @Schema(description = "商品详情URL")
    private String detailUrl;

    @Schema(description = "浏览量")
    private Integer viewCount;

    @Schema(description = "想要量")
    private Integer wantCount;

    @Schema(description = "热度（高/中/低）")
    private String heatLevel;

    @Schema(description = "高价值（高/中/低/-）")
    private String highValue;

    @Schema(description = "竞争力（高/中/低）")
    private String competitiveness;

    @Schema(description = "卖家级别（优质/个人）")
    private String sellerLevel;

    @Schema(description = "详情记录ID，可用于入库")
    private Long detailId;

    @Schema(description = "关联的货源库记录ID，存在时表示已入库")
    private Long sourceInventoryId;

    @Schema(description = "搜索关键词")
    private String searchKeyword;
}
