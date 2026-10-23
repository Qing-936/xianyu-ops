package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 闲鱼 Agent 实例（Windows 上跑闲管家的机器 Agent）
 */
@TableName("app_xianyu_agent")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class XianyuAgentDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** Agent 名称（默认取 hostName，管理端可改） */
    private String name;

    /** 机器指纹（唯一，首次心跳注册） */
    private String machineCode;

    /** 专属 Token 的 SHA-256（NULL=未激活；M5 一机一 Token，明文仅注册/重置时下发一次） */
    private String tokenHash;

    /** 主机名 */
    private String hostName;

    /** 操作系统版本 */
    private String osVersion;

    /** Agent 程序版本 */
    private String agentVersion;

    /** 能力声明（逗号分隔：text,image,emoji；空/旧 Agent 视为 text） */
    private String capabilities;

    /** 状态：online/offline，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.XianyuAgentStatusEnum} */
    private String status;

    /** 最近心跳是否锁屏（管理端告警） */
    private Boolean screenLocked;

    /** 最后心跳时间 */
    private LocalDateTime lastHeartbeatAt;

    /** 备注 */
    private String remark;

}
