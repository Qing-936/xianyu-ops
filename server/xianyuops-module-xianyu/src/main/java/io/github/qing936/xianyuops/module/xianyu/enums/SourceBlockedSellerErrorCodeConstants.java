package io.github.qing936.xianyuops.module.xianyu.enums;

import io.github.qing936.xianyuops.framework.common.exception.ErrorCode;

/**
 * 货源拉黑卖家 错误码枚举类
 * <p>
 * app 货源管理，使用 1-020-002-xxx 段
 */
public interface SourceBlockedSellerErrorCodeConstants {

    ErrorCode SOURCE_BLOCKED_SELLER_NOT_EXISTS = new ErrorCode(1_020_002_000, "拉黑记录不存在");
    ErrorCode SOURCE_BLOCKED_SELLER_ALREADY_EXISTS = new ErrorCode(1_020_002_001, "该卖家已在黑名单中");
    ErrorCode SOURCE_BLOCKED_SELLER_SELLER_REQUIRED = new ErrorCode(1_020_002_002, "卖家名称与卖家ID不能同时为空");
    ErrorCode SOURCE_BLOCKED_SELLER_NOT_EFFECTIVE = new ErrorCode(1_020_002_003, "该拉黑记录已解除，无需重复解除");
    ErrorCode SOURCE_BLOCKED_SELLER_REASON_REQUIRED = new ErrorCode(1_020_002_004, "拉黑原因不能为空");
    ErrorCode SOURCE_BLOCKED_SELLER_CANNOT_INVENTORY = new ErrorCode(1_020_002_005, "该卖家已被拉黑，禁止入库");

}
