package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼消息状态枚举
 * <p>
 * 对应 app_xianyu_chat_message.msg_status 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuMsgStatusEnum {

    UNREAD("unread", "未读"),
    READ("read", "已读"),
    REPLIED("replied", "已回复");

    private final String status;
    private final String desc;

    public static XianyuMsgStatusEnum valueOfStatus(String status) {
        if (status == null) {
            return null;
        }
        for (XianyuMsgStatusEnum e : values()) {
            if (e.status.equals(status)) {
                return e;
            }
        }
        return null;
    }
}
