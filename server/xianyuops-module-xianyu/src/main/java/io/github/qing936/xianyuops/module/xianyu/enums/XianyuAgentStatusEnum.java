package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼 Agent 实例状态枚举
 * <p>
 * 对应 app_xianyu_agent.status 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuAgentStatusEnum {

    ONLINE("online", "在线"),
    OFFLINE("offline", "离线");

    private final String status;
    private final String desc;

    public static XianyuAgentStatusEnum valueOfStatus(String status) {
        if (status == null) {
            return null;
        }
        for (XianyuAgentStatusEnum e : values()) {
            if (e.status.equals(status)) {
                return e;
            }
        }
        return null;
    }

    public boolean isOnline() {
        return this == ONLINE;
    }
}
