package io.github.qing936.xianyuops.module.xianyu.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SourceInventoryStatusEnum {

    PENDING_AUDIT(0, "待审核"),
    IN_STOCK(1, "已入库"),
    PRODUCT_CREATED(2, "已创建商品"),
    PUBLISHED(3, "已上架"),
    SOLD(4, "已售出"),
    OFF_SHELF(5, "已下架");

    private final int status;
    private final String name;

}
