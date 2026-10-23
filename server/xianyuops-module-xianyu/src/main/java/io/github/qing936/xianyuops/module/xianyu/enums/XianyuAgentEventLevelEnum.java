package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼 Agent 运行事件级别枚举
 * <p>
 * 对应 app_xianyu_agent_event.level 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuAgentEventLevelEnum {

    INFO("info", "信息"),
    WARN("warn", "警告"),
    ERROR("error", "错误");

    private final String level;
    private final String desc;

    public static XianyuAgentEventLevelEnum valueOfLevel(String level) {
        if (level == null) {
            return null;
        }
        for (XianyuAgentEventLevelEnum e : values()) {
            if (e.level.equals(level)) {
                return e;
            }
        }
        return null;
    }
}
