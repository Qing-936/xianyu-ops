package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@TableName("app_source_item_detail")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceItemDetailDO extends TenantBaseDO {

    @TableId
    private Long id;

    private Long itemId;

    private Long taskId;

    private String externalItemId;

    private String title;

    private String description;

    private Long price;

    private Long originalPrice;

    private String picUrl;

    private String images;

    /** 卖家ID（用于拉黑卖家的主匹配键） */
    private String sellerId;

    /** 卖家昵称 */
    private String sellerName;
}
