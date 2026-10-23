package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SourceBlockedSellerStatusEnum {

    RELEASED(0, "已解除"),
    EFFECTIVE(1, "生效中");

    private final Integer status;
    private final String name;

}
