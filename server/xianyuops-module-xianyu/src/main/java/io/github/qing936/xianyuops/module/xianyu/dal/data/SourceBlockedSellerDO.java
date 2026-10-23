package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 货源拉黑卖家
 */
@TableName("app_source_blocked_seller")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceBlockedSellerDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 闲鱼卖家ID（主匹配键，可空） */
    private String sellerId;

    /** 卖家名称/昵称（辅助匹配键 + 展示） */
    private String sellerName;

    /** 拉黑原因 */
    private String blockReason;

    /** 来源任务ID（app_source_task.id，手动添加时为空） */
    private Long sourceTaskId;

    /** 状态，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.SourceBlockedSellerStatusEnum} */
    private Integer status;

    /** 拉黑时间 */
    private LocalDateTime blockTime;

    /** 解除时间（status=0 时有值） */
    private LocalDateTime unblockTime;

}
