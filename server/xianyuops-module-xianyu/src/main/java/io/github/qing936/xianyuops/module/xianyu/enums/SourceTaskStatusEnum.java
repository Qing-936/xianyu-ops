package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 货源采集任务状态枚举
 * <p>
 * 对应 app_source_task.status 字段
 */
@Getter
@AllArgsConstructor
public enum SourceTaskStatusEnum {

    RUNNING(0, "运行中"),
    PAUSED(1, "已暂停"),
    COMPLETED(2, "已完成"),
    SUBMIT_FAILED(3, "提交失败"),
    FAILED(4, "失败");

    private final Integer status;
    private final String desc;

    /**
     * 根据状态值获取枚举
     */
    public static SourceTaskStatusEnum valueOf(Integer status) {
        if (status == null) {
            return null;
        }
        for (SourceTaskStatusEnum e : values()) {
            if (e.status.equals(status)) {
                return e;
            }
        }
        return null;
    }
}
