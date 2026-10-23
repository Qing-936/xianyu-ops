package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闲鱼回复任务来源枚举
 * <p>
 * 对应 app_xianyu_reply_task.source 字段。
 * 当前阶段只有 manual（管理端人工回复）；ai 为后续 AI 自动回复预留。
 */
@Getter
@AllArgsConstructor
public enum XianyuReplyTaskSourceEnum {

    MANUAL("manual", "人工"),
    AI("ai", "AI");

    private final String source;
    private final String desc;

    public static XianyuReplyTaskSourceEnum valueOfSource(String source) {
        if (source == null) {
            return null;
        }
        for (XianyuReplyTaskSourceEnum e : values()) {
            if (e.source.equals(source)) {
                return e;
            }
        }
        return null;
    }
}
