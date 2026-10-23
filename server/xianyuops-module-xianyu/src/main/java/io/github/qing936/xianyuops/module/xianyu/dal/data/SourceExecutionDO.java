package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 货源采集批次汇总 DO
 *
 * 对应 app_source_execution 表，由 Python 服务写入，Java 只读。
 */
@TableName("app_source_execution")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceExecutionDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 关联任务ID */
    private Long taskId;

    /** 批次号 */
    private Integer executionNo;

    /** 搜索关键词 */
    private String keyword;

    /** 获取条数 */
    private Integer fetchCount;

    /** 有效数 */
    private Integer validCount;

    /** 最低价（分） */
    private Integer minPrice;

    /** 最高价（分） */
    private Integer maxPrice;

    /** 平均价（分） */
    private Integer avgPrice;

    /** 价格指数（价格段数量，用于市场分析） */
    private Integer priceIndex;

    /** 执行时间 */
    private LocalDateTime executedAt;

    /** 批次状态（0=执行中 1=已完成 2=失败） */
    private Integer status;

    /** 失败原因 */
    private String errorMsg;

}
