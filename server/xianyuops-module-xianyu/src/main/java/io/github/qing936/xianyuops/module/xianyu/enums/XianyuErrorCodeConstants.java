package io.github.qing936.xianyuops.module.xianyu.enums;

import io.github.qing936.xianyuops.framework.common.exception.ErrorCode;

/**
 * App 错误码枚举类
 * <p>
 * app 系统，使用 1-005-000-000 段
 */
public interface XianyuErrorCodeConstants {

    // ========== 闲鱼端口 1-005-001-000 ==========
    ErrorCode XIANYU_PORT_NOT_EXISTS = new ErrorCode(1_005_001_000, "闲鱼端口不存在");
    ErrorCode XIANYU_PORT_NAME_DUPLICATE = new ErrorCode(1_005_001_001, "闲鱼端口名称已存在");
    ErrorCode XIANYU_PORT_APP_KEY_DUPLICATE = new ErrorCode(1_005_001_002, "闲鱼端口 AppKey 已存在");

    // ========== 闲鱼账号 1-005-002-000 ==========
    ErrorCode XIANYU_ACCOUNT_NOT_EXISTS = new ErrorCode(1_005_002_000, "闲鱼账号不存在");
    ErrorCode XIANYU_ACCOUNT_SYNC_FAIL = new ErrorCode(1_005_002_001, "闲鱼账号同步失败：{}");
    ErrorCode XIANYU_ACCOUNT_USER_IDENTITY_DUPLICATE = new ErrorCode(1_005_002_002, "会员ID已存在，请勿重复添加");
    ErrorCode XIANYU_ACCOUNT_SYNC_NOT_MATCHED = new ErrorCode(1_005_002_003, "该端口下未找到匹配的账号");
    ErrorCode XIANYU_ACCOUNT_NOTIFY_EMAIL_INVALID = new ErrorCode(1_005_002_004, "订单通知邮箱格式不正确");

    // ========== 闲鱼商品 1-005-003-000 ==========
    ErrorCode XIANYU_PRODUCT_NOT_EXISTS = new ErrorCode(1_005_003_000, "闲鱼商品不存在");
    ErrorCode XIANYU_PRODUCT_SDK_FAIL = new ErrorCode(1_005_003_001, "闲鱼商品操作失败：{}");
    ErrorCode XIANYU_PRODUCT_REMOTE_ID_MISSING = new ErrorCode(1_005_003_002, "闲鱼商品尚未同步到远端，无法执行该操作");
    ErrorCode XIANYU_PRODUCT_ACCOUNT_IDS_EMPTY = new ErrorCode(1_005_003_003, "所属账号列表不能为空");
    ErrorCode XIANYU_PRODUCT_REMOTE_DELETE_FAIL = new ErrorCode(1_005_003_004, "远端商品删除失败：{}");
    ErrorCode XIANYU_PRODUCT_DELIVER_TYPE_INVALID = new ErrorCode(1_005_003_005, "自动发货方式不合法，只允许 0(不自动发货) 1(固定网盘链接) 2(自助下单链接)");
    ErrorCode XIANYU_PRODUCT_DELIVER_LINK_REQUIRED = new ErrorCode(1_005_003_006, "选择固定网盘链接发货时，固定链接不能为空");
    ErrorCode XIANYU_PRODUCT_SOURCE_IDS_EMPTY = new ErrorCode(1_005_003_007, "来源货源列表不能为空");

    // ========== 闲鱼订单 1-005-004-000 ==========
    ErrorCode XIANYU_ORDER_NOT_EXISTS = new ErrorCode(1_005_004_000, "闲鱼订单不存在");
    ErrorCode XIANYU_ORDER_SDK_FAIL = new ErrorCode(1_005_004_001, "闲鱼订单操作失败：{}");
    ErrorCode XIANYU_ORDER_NOT_PAID = new ErrorCode(1_005_004_002, "闲鱼订单不是已付款状态，无法发货");
    ErrorCode XIANYU_ORDER_ALREADY_SHIPPED = new ErrorCode(1_005_004_003, "闲鱼订单已经发货");

    // ========== 卡券管理 1-005-009-000 ==========
    ErrorCode COUPON_CARD_NOT_EXISTS = new ErrorCode(1_005_009_000, "卡券不存在");
    ErrorCode COUPON_CARD_NAME_EMPTY = new ErrorCode(1_005_009_001, "卡券名称不能为空");
    ErrorCode COUPON_CARD_NAME_TOO_LONG = new ErrorCode(1_005_009_002, "卡券名称长度不能超过128个字符");
    ErrorCode COUPON_CARD_TYPE_INVALID = new ErrorCode(1_005_009_003, "卡券类型不合法，只允许 1(直充) 2(卡密) 3(券码) 或 4(链接类)");
    ErrorCode COUPON_CARD_AMOUNT_INVALID = new ErrorCode(1_005_009_004, "卡券原价不合法，范围为 1~999999999 分");
    ErrorCode COUPON_CARD_IMPORT_EMPTY = new ErrorCode(1_005_009_006, "需要输入卡密数据");
    ErrorCode COUPON_CARD_IMPORT_EXCEED_LIMIT = new ErrorCode(1_005_009_007, "单次导入不能超过5000条");
    ErrorCode COUPON_CARD_INSTANCE_DELETE_EMPTY = new ErrorCode(1_005_009_008, "请选择要删除的卡密");
    ErrorCode COUPON_CARD_INSTANCE_DELETE_EXCEED_LIMIT = new ErrorCode(1_005_009_009, "单次删除不能超过200条");
    ErrorCode COUPON_CARD_INSTANCE_DELETE_USED = new ErrorCode(1_005_009_010, "已使用的卡密不允许删除");
    ErrorCode COUPON_CARD_PLACEHOLDER_EXISTS = new ErrorCode(1_005_009_012, "占位卡券只能存在一个，请先取消已有占位卡券");
    ErrorCode COUPON_CARD_PLACEHOLDER_TYPE_INVALID = new ErrorCode(1_005_009_013, "仅链接类卡券(type=4)可设为占位卡券");
    ErrorCode COUPON_CARD_PLACEHOLDER_DELETE_FORBIDDEN = new ErrorCode(1_005_009_014, "占位卡券不允许删除，请先取消其占位标记");

    // ========== 闲鱼自助下单 1-005-010-000 ==========
    ErrorCode SELF_ORDER_ORDER_NOT_EXISTS = new ErrorCode(1_005_010_000, "订单不存在");
    ErrorCode SELF_ORDER_SIGN_INVALID = new ErrorCode(1_005_010_001, "链接无效，请通过订单内的链接访问");
    ErrorCode SELF_ORDER_PHASE_NOT_ALLOWED = new ErrorCode(1_005_010_002, "当前阶段不允许该操作");
    ErrorCode SELF_ORDER_IMAGE_URLS_EMPTY = new ErrorCode(1_005_010_003, "请至少上传一张图片");
    ErrorCode SELF_ORDER_UPLOAD_NOT_IMAGE = new ErrorCode(1_005_010_004, "仅支持上传图片文件");
    ErrorCode SELF_ORDER_UPLOAD_EXCEED_LIMIT = new ErrorCode(1_005_010_005, "图片大小不能超过 10MB");
    ErrorCode SELF_ORDER_MAIL_NOT_RETRYABLE = new ErrorCode(1_005_010_006, "当前邮件状态不允许重发");
    ErrorCode SELF_ORDER_MAIL_NO_EMAIL = new ErrorCode(1_005_010_007, "账号未配置订单通知邮箱，请先在账号管理中配置");
    ErrorCode SELF_ORDER_MAIL_SEND_FAIL = new ErrorCode(1_005_010_008, "点单邮件发送失败：{}");
    ErrorCode SELF_ORDER_ACCOUNT_EMAIL_INVALID = new ErrorCode(1_005_010_009, "订单通知邮箱格式不合法");
    ErrorCode SELF_ORDER_PARSE_INFO_INVALID = new ErrorCode(1_005_010_010, "解析结果格式不合法");
    ErrorCode SELF_ORDER_CHECK_MISMATCH = new ErrorCode(1_005_010_011, "上传的截图与所购商品不一致，请重新上传正确的订单截图");
    ErrorCode SELF_ORDER_CHECK_UNRECOGNIZED = new ErrorCode(1_005_010_012, "截图未能自动识别，请重新上传清晰的订单截图");
    ErrorCode SELF_ORDER_UPLOAD_RETRY_EXCEEDED = new ErrorCode(1_005_010_013, "上传次数已达上限，请联系卖家处理");

    // ========== 闲鱼 Agent 消息模块 1-005-011-000 ==========
    ErrorCode XIANYU_AGENT_TOKEN_INVALID = new ErrorCode(1_005_011_000, "Agent Token 无效");
    ErrorCode XIANYU_AGENT_NOT_EXISTS = new ErrorCode(1_005_011_001, "Agent 未注册，请先启动 Agent");
    ErrorCode XIANYU_AGENT_OFFLINE = new ErrorCode(1_005_011_002, "Agent 不在线，无法下发回复任务");
    ErrorCode XIANYU_AGENT_ACCOUNT_NOT_BOUND = new ErrorCode(1_005_011_003, "目标闲鱼账号未绑定到该 Agent");
    ErrorCode XIANYU_AGENT_ACCOUNT_OFFLINE = new ErrorCode(1_005_011_004, "目标闲鱼账号未登录，无法回复");
    ErrorCode XIANYU_REPLY_TASK_NOT_EXISTS = new ErrorCode(1_005_011_005, "回复任务不存在");
    ErrorCode XIANYU_REPLY_TASK_STATUS_INVALID = new ErrorCode(1_005_011_006, "回复任务当前状态不允许该操作");
    ErrorCode XIANYU_REPLY_TASK_CONTENT_EMPTY = new ErrorCode(1_005_011_007, "回复内容不能为空");
    ErrorCode XIANYU_REPLY_TASK_CONTENT_TOO_LONG = new ErrorCode(1_005_011_008, "回复内容不能超过 5000 个字符");
    ErrorCode XIANYU_MESSAGE_UPLOAD_EMPTY = new ErrorCode(1_005_011_009, "上报消息列表不能为空");
    ErrorCode XIANYU_REPLY_TASK_CONTENT_TYPE_INVALID = new ErrorCode(1_005_011_013, "回复任务内容类型不支持");
    ErrorCode XIANYU_REPLY_TASK_MEDIA_INVALID = new ErrorCode(1_005_011_014, "回复任务媒体内容不合法");
    ErrorCode XIANYU_REPLY_TASK_MEDIA_UNSUPPORTED = new ErrorCode(1_005_011_015, "当前 Agent 不支持该媒体类型");
    ErrorCode XIANYU_REPLY_TASK_MEDIA_DISABLED = new ErrorCode(1_005_011_016, "媒体外发已被服务端关闭");
    ErrorCode XIANYU_AGENT_ENROLL_NOT_EXISTS = new ErrorCode(1_005_011_010, "注册码不存在或已失效，请在管理端重新生成");

}
