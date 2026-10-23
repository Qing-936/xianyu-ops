package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 货源采集任务
 */
@TableName("app_source_task")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceTaskDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 搜索关键词（商品名称） */
    private String productName;

    /** 筛选最低价（分） */
    private Integer filterMinPrice;

    /** 筛选最高价（分） */
    private Integer filterMaxPrice;

    /** 筛选最小浏览量 */
    private Integer filterViewCountMin;

    /** 筛选最大浏览量 */
    private Integer filterViewCountMax;

    /** 筛选最小想要量 */
    private Integer filterWantCountMin;

    /** 筛选最大想要量 */
    private Integer filterWantCountMax;

    /** 每次获取条数 */
    private Integer fetchCount;

    /** 已执行次数 */
    private Integer executedCount;

    /** 任务状态，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskStatusEnum} */
    private Integer status;

    /** 提交失败原因（status=3时有值） */
    private String errorMessage;

    /** 执行方式，见 {@link io.github.qing936.xianyuops.module.xianyu.enums.SourceTaskTriggerModeEnum} */
    private Integer triggerMode;

    /** 每日执行时间（HH:mm格式） */
    private String scheduleTime;

    /** 最近执行时间 */
    private LocalDateTime lastExecutedAt;

    /** 最后查看的批次号（用于工作台未查看提醒，全局共享） */
    private Integer lastViewedExecutionNo;

}
