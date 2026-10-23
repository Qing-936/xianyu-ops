package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SourceCookieStatusEnum {

    ACTIVE(0, "可用"),
    COOLING(1, "冷却中"),
    EXPIRED(2, "已失效"),
    DISABLED(3, "已停用");

    private final int status;
    private final String name;
}
