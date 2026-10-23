package io.github.qing936.xianyuops.module.xianyu.enums;

import io.github.qing936.xianyuops.framework.common.exception.ErrorCode;

/**
 * 货源图片微调 错误码枚举类
 * <p>
 * app 货源管理，使用 1-020-003-xxx 段
 */
public interface SourceImageRefineErrorCodeConstants {

    ErrorCode SOURCE_IMAGE_REFINE_NOT_CONFIGURED = new ErrorCode(1_020_003_000, "图片微调未启用或未配置工作流 API Key");
    ErrorCode SOURCE_IMAGE_REFINE_NO_CLEAN_HTML = new ErrorCode(1_020_003_001, "该货源暂无清洗后的 HTML，无法进行图片微调");
    ErrorCode SOURCE_IMAGE_REFINE_FAILED = new ErrorCode(1_020_003_002, "图片微调工作流执行失败，请稍后重试或检查工作流日志");
    ErrorCode SOURCE_IMAGE_REFINE_RESULT_INVALID = new ErrorCode(1_020_003_003, "图片微调工作流未返回有效的图片地址");
    ErrorCode SOURCE_IMAGE_REFINE_PREVIEW_EXPIRED = new ErrorCode(1_020_003_004, "微调预览已过期，请重新生成");

}
