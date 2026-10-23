package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 闲鱼 Agent 注册码状态枚举（派生值，不落库）
 * <p>
 * 由 app_xianyu_agent_enroll 的 used_time / expire_time 实时推导，避免存状态列产生
 * 「已过期但状态仍是待使用」的脏数据。
 */
@Getter
@AllArgsConstructor
public enum XianyuAgentEnrollStatusEnum {

    UNUSED(0, "待使用"),
    USED(1, "已使用"),
    EXPIRED(2, "已过期");

    private final Integer status;
    private final String desc;

    /** 已使用优先于已过期：过期前被消费掉的码记为「已使用」 */
    public static XianyuAgentEnrollStatusEnum resolve(LocalDateTime usedTime, LocalDateTime expireTime) {
        if (usedTime != null) {
            return USED;
        }
        return expireTime != null && !expireTime.isAfter(LocalDateTime.now()) ? EXPIRED : UNUSED;
    }

}
