package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("app_source_inventory")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceInventoryDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long taskId;
    private Long sourceItemId;
    private Long sourceDetailId;
    private String externalItemId;
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
    private String detailUrl;
    private String images;
    private Integer stock;
    private String location;
    private String province;
    private String city;
    private String transportFee;
    private Integer viewCount;
    private Integer wantCount;
    private Integer soldCount;
    private String sellerName;
    private String sellerCity;
    private String sellerCredit;
    private String sellerLevel;
    private String categoryName;
    private String itemType;
    private String tagList;
    private Integer inventoryStatus;
    private LocalDateTime inventoryTime;
    private String inventoryUser;
    private Long xianyuProductId;
    private Long xianyuAccountId;
    private Long xianyuRemoteId;
    private Integer xianyuStatus;
    private String remark;
    private String auditRemark;
    private Integer cleanStatus;
    private LocalDateTime cleanTime;
    private String cleanConfig;
    private String cleanHtml;
}
