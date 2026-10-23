package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼外发消息发送状态枚举
 * <p>
 * 对应 app_xianyu_chat_message.send_status 字段（仅 direction=out 的消息使用）：
 * 创建回复任务时以 SENDING 落库，reportResult / Job 超时兜底按 task_id 原地回填终态。
 * <p>
 * 与 {@link XianyuReplyTaskStatusEnum} 的映射：pending/dispatched → SENDING、success → SENT、
 * failed → FAILED、timeout → UNKNOWN（**结果未知不等于失败**：超时仅代表 Agent 未回传，
 * 消息可能实际已发出，标记为失败会诱导用户重复发送）。
 */
@Getter
@AllArgsConstructor
public enum XianyuSendStatusEnum {

    SENDING("sending", "发送中"),
    SENT("sent", "已发送"),
    FAILED("failed", "发送失败"),
    UNKNOWN("unknown", "结果未知");

    private final String status;
    private final String desc;

    public static XianyuSendStatusEnum valueOfStatus(String status) {
        if (status == null) {
            return null;
        }
        for (XianyuSendStatusEnum e : values()) {
            if (e.status.equals(status)) {
                return e;
            }
        }
        return null;
    }
}
