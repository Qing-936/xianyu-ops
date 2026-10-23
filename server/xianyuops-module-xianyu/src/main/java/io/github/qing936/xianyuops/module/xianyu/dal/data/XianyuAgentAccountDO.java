package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 闲鱼 Agent × 闲鱼号绑定（运行态）
 * <p>
 * 开源核心版不包含账号主数据，accountId 固定为 NULL，仅保留心跳快照字段。
 */
@TableName("app_xianyu_agent_account")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentAccountDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** Agent ID */
    private Long agentId;

    /** 闲鱼账号 ID（开源核心版固定为 NULL） */
    private Long accountId;

    /** 授权 ID 快照（心跳上报，可空） */
    private String authorizeId;

    /** 闲鱼号昵称快照 */
    private String userNick;

    /** 登录状态：online/offline/unknown，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuLoginStatusEnum} */
    private String loginStatus;

    /** 最近一次上报时间 */
    private LocalDateTime lastReportAt;

}
