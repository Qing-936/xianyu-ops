package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼回复任务状态枚举
 * <p>
 * 对应 app_xianyu_reply_task.status 字段。
 * 状态机：pending → dispatched → success/failed；pending → canceled；dispatched → timeout（Job 兜底）。
 */
@Getter
@AllArgsConstructor
public enum XianyuReplyTaskStatusEnum {

    PENDING("pending", "待执行"),
    DISPATCHED("dispatched", "已下发"),
    SUCCESS("success", "成功"),
    FAILED("failed", "失败"),
    CANCELED("canceled", "已取消"),
    TIMEOUT("timeout", "已超时");

    private final String status;
    private final String desc;

    public static XianyuReplyTaskStatusEnum valueOfStatus(String status) {
        if (status == null) {
            return null;
        }
        for (XianyuReplyTaskStatusEnum e : values()) {
            if (e.status.equals(status)) {
                return e;
            }
        }
        return null;
    }

    public boolean isFinished() {
        return this == SUCCESS || this == FAILED || this == CANCELED || this == TIMEOUT;
    }
}
