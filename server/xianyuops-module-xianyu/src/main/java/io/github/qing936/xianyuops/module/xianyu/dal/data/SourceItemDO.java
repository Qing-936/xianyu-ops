package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 货源搜索结果
 */
@TableName("app_source_item")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceItemDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 关联任务ID */
    private Long taskId;

    /** 批次号 */
    private Integer executionNo;

    /** 闲鱼商品ID */
    private String externalItemId;

    /** 商品标题 */
    private String title;

    /** 售价（分） */
    private Integer price;

    /** 原价（分） */
    private Integer originalPrice;

    /** 商品图片URL */
    private String picUrl;

    /** 商品详情URL */
    private String detailUrl;

    /** 发布地区 */
    private String location;

    /** 想要人数 */
    private Integer wantCount;

    /** 浏览量 */
    private Integer viewCount;

    /** 发布时间 */
    private String publishTime;

    /** 卖家昵称 */
    private String sellerName;

    /** 卖家级别（优质/个人） */
    private String sellerLevel;

    /** 卖家信用 */
    private String sellerCredit;

    /** 卖家评价数 */
    private String sellerReviewCount;

    /** 卖家好评率 */
    private String sellerGoodRate;

    /** 商品类型 */
    private String itemType;

    /** 降价标签 */
    private String priceDropTag;

    /** 发货速度 */
    private String shipSpeed;

    /** 标签列表（JSON字符串） */
    private String tagList;

    /** 热度（高/中/低） */
    private String heatLevel;

    /** 高价值（高/中/低/-） */
    private String highValue;

    /** 竞争力（高/中/低） */
    private String competitiveness;

    /** 搜索关键词 */
    private String searchKeyword;

    /** 是否有效 */
    private Boolean isValid;

    /** 采集时间 */
    private LocalDateTime crawlTime;

}
