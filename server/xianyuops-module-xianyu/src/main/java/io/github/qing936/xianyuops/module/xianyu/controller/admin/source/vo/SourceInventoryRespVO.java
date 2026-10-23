package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 货源库响应 VO")
@Data
public class SourceInventoryRespVO {

    private Long id;
    private String title;
    private String description;
    private String searchKeyword;
    private Long price;
    private Long originalPrice;
    private Long suggestedPrice;
    private BigDecimal profitMargin;
    private String itemCondition;
    private String functionStatus;
    private String brand;
    private String picUrl;
    private String images;
    private String detailUrl;
    private Integer viewCount;
    private Integer wantCount;
    private Integer soldCount;
    private String sellerName;
    private String sellerCity;
    private String sellerLevel;
    private String location;
    private Integer stock;
    private Integer inventoryStatus;
    private LocalDateTime inventoryTime;
    private Integer xianyuStatus;
    private Long xianyuProductId;
    private String remark;
    private String auditRemark;
    private LocalDateTime createTime;
    private Integer cleanStatus;
    /** clean_html 是否非空（供前端判断能否进行图片微调） */
    private Boolean hasCleanHtml;
}
