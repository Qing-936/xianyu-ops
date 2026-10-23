package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼 Agent 运行事件类型枚举
 * <p>
 * 对应 app_xianyu_agent_event.event_type 字段。
 */
@Getter
@AllArgsConstructor
public enum XianyuAgentEventTypeEnum {

    LOGIN_EXPIRED("login_expired", "闲鱼登录失效"),
    SCREEN_LOCKED("screen_locked", "系统锁屏"),
    OCR_FAILED("ocr_failed", "OCR 识别失败"),
    SEND_SUCCESS("send_success", "发送成功"),
    SEND_FAILED("send_failed", "发送失败"),
    SEND_UNKNOWN("send_unknown", "发送结果未知"),
    CLIENT_ERROR("client_error", "Agent 客户端异常"),
    AGENT_START("agent_start", "Agent 启动"),
    AGENT_STOP("agent_stop", "Agent 停止");

    private final String type;
    private final String desc;

    public static XianyuAgentEventTypeEnum valueOfType(String type) {
        if (type == null) {
            return null;
        }
        for (XianyuAgentEventTypeEnum e : values()) {
            if (e.type.equals(type)) {
                return e;
            }
        }
        return null;
    }
}
