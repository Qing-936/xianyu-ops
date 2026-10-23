package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 闲鱼 Agent 一次性注册码（替代原「引导 Token」全局长期密钥）
 * <p>
 * 生命周期：管理端生成（明文只下发一次）→ Agent 首次心跳消费 → 签发专属 Token 后作废。
 * 状态不落库，由 {@code usedTime} / {@code expireTime} 派生，见
 * {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentEnrollStatusEnum}。
 */
@TableName("app_xianyu_agent_enroll")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentEnrollDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 注册码 SHA-256（明文仅生成时下发一次，不入库、不打日志） */
    private String codeHash;

    /** 备注（建议填目标机器名） */
    private String remark;

    /** 过期时间（超过即失效） */
    private LocalDateTime expireTime;

    /** 被消费时间（NULL=未使用） */
    private LocalDateTime usedTime;

    /** 消费该码注册的 Agent ID（逻辑外键 → app_xianyu_agent.id） */
    private Long agentId;

}
