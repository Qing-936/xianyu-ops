package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼消息方向枚举
 * <p>
 * 对应 app_xianyu_chat_message.direction 字段
 */
@Getter
@AllArgsConstructor
public enum XianyuMessageDirectionEnum {

    IN("in", "收到"),
    OUT("out", "发出");

    private final String direction;
    private final String desc;

    public static XianyuMessageDirectionEnum valueOfDirection(String direction) {
        if (direction == null) {
            return null;
        }
        for (XianyuMessageDirectionEnum e : values()) {
            if (e.direction.equals(direction)) {
                return e;
            }
        }
        return null;
    }
}
