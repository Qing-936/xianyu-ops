package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 闲鱼 Agent 运行事件（审计/告警）
 */
@TableName("app_xianyu_agent_event")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentEventDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** Agent ID */
    private Long agentId;

    /** 相关账号（可空） */
    private Long accountId;

    /** 事件类型，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEventTypeEnum} */
    private String eventType;

    /** 级别：info/warn/error，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEventLevelEnum} */
    private String level;

    /** 事件描述 */
    private String content;

    /** 明细 JSON */
    private String rawJson;

}
