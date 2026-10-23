package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼号登录状态枚举（Agent 心跳上报）
 * <p>
 * 对应 app_xianyu_agent_account.login_status 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuLoginStatusEnum {

    ONLINE("online", "已登录"),
    OFFLINE("offline", "未登录"),
    UNKNOWN("unknown", "未知");

    private final String status;
    private final String desc;

    public static XianyuLoginStatusEnum valueOfStatus(String status) {
        if (status == null) {
            return null;
        }
        for (XianyuLoginStatusEnum e : values()) {
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
