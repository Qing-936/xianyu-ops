package io.github.qing936.xianyuops.module.xianyu.dal.redis;

/**
 * App Redis Key 枚举类
 *
 * @author XianyuOps系统
 */
public interface RedisKeyConstants {

    /**
     * 闲管家商品类目的缓存
     * <p>
     * KEY 格式：app_xianyu_category:{accountId}:{itemBizType}:{spBizType}
     * VALUE 数据格式：JSON 文本（闲管家类目接口 data 节点的原始 JSON，由 StringRedisTemplate 存取）
     * 过期时间：1 小时（在 XianyuCategoryCacheService 内显式设置）
     */
    String XIANYU_CATEGORY = "app_xianyu_category";

    /**
     * 货源图片微调的待确认预览图
     * <p>
     * KEY 格式：app_source_image_refine_preview:{inventoryId}
     * VALUE 数据格式：转存后的图片地址（纯文本，由 StringRedisTemplate 存取）
     * 过期时间：30 分钟（在 SourceImageRefineServiceImpl 内显式设置）
     */
    String SOURCE_IMAGE_REFINE_PREVIEW = "app_source_image_refine_preview";

}
