package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 货源采集任务执行方式枚举
 * <p>
 * 对应 app_source_task.trigger_mode 字段
 */
@Getter
@AllArgsConstructor
public enum SourceTaskTriggerModeEnum {

    ONCE(0, "一次性"),
    DAILY(1, "每日定时");

    private final Integer mode;
    private final String desc;

    /**
     * 根据模式值获取枚举
     */
    public static SourceTaskTriggerModeEnum valueOf(Integer mode) {
        if (mode == null) {
            return null;
        }
        for (SourceTaskTriggerModeEnum e : values()) {
            if (e.mode.equals(mode)) {
                return e;
            }
        }
        return null;
    }
}
