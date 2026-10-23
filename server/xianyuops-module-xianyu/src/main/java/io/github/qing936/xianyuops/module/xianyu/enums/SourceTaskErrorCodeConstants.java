package io.github.qing936.xianyuops.module.xianyu.enums;

import io.github.qing936.xianyuops.framework.common.exception.ErrorCode;

/**
 * 货源采集任务 错误码枚举类
 * <p>
 * app 货源管理，使用 1-020-001-xxx 段
 */
public interface SourceTaskErrorCodeConstants {

    ErrorCode SOURCE_TASK_NOT_EXISTS = new ErrorCode(1_020_001_000, "采集任务不存在");
    ErrorCode SOURCE_TASK_CANNOT_PAUSE = new ErrorCode(1_020_001_001, "仅运行中的任务可暂停");
    ErrorCode SOURCE_TASK_CANNOT_RESUME = new ErrorCode(1_020_001_002, "仅已暂停或已完成的任务可恢复");
    ErrorCode SOURCE_TASK_PRICE_RANGE_INVALID = new ErrorCode(1_020_001_003, "筛选最低价不能大于最高价");
    ErrorCode SOURCE_TASK_SCHEDULE_TIME_REQUIRED = new ErrorCode(1_020_001_004, "每日定时任务必须设置执行时间");
    ErrorCode SOURCE_TASK_CANNOT_EXECUTE = new ErrorCode(1_020_001_005, "仅运行中或提交失败的任务可立即执行");
    ErrorCode SOURCE_TASK_VIEW_COUNT_RANGE_INVALID = new ErrorCode(1_020_001_006, "最小浏览量不能大于最大浏览量");
    ErrorCode SOURCE_TASK_WANT_COUNT_RANGE_INVALID = new ErrorCode(1_020_001_007, "最小想要量不能大于最大想要量");
    ErrorCode SOURCE_INVENTORY_NOT_EXISTS = new ErrorCode(1_020_001_008, "货源不存在");
    ErrorCode SOURCE_INVENTORY_DELETE_CONFLICT = new ErrorCode(1_020_001_011, "已关联闲鱼商品的货源不可删除");
    ErrorCode SOURCE_INVENTORY_DETAIL_NOT_EXISTS = new ErrorCode(1_020_001_012, "采集详情不存在或已删除");
    ErrorCode SOURCE_INVENTORY_ALREADY_EXISTS = new ErrorCode(1_020_001_013, "该商品已入库");
    ErrorCode SOURCE_INVENTORY_ADAPTER_FAILED = new ErrorCode(1_020_001_014, "货源入库服务暂不可用，请稍后重试");
    ErrorCode SOURCE_INVENTORY_PRODUCT_ALREADY_CREATED = new ErrorCode(1_020_001_015, "该货源已创建闲鱼商品，不可重复创建");
    ErrorCode SOURCE_INVENTORY_INVALID_LINK = new ErrorCode(1_020_001_016, "闲鱼商品链接无效或无法解析");
    ErrorCode SOURCE_INVENTORY_CLEANING = new ErrorCode(1_020_001_017, "货源正在清洗中，暂不可刷新");
    ErrorCode SOURCE_INVENTORY_REFRESH_PRODUCT_CREATED = new ErrorCode(1_020_001_018, "创建的商品无法刷新");
    ErrorCode SOURCE_TASK_RETRY_BUSY = new ErrorCode(1_020_001_017, "已有采集任务正在重试，请稍后再试");
    ErrorCode SOURCE_CLEAN_NOT_CONFIGURED = new ErrorCode(1_020_001_018, "AI清洗未启用或未配置工作流API Key");
    ErrorCode SOURCE_CLEAN_INVALID_REQUEST = new ErrorCode(1_020_001_019, "清洗参数无效，请检查货源数量、图片、清洗类型和Skill配置");
    ErrorCode SOURCE_CLEAN_BUSY = new ErrorCode(1_020_001_020, "货源已有清洗任务，请等待完成");
    ErrorCode SOURCE_CLEAN_TASK_NOT_EXISTS = new ErrorCode(1_020_001_021, "清洗任务不存在或不属于当前用户");

}
