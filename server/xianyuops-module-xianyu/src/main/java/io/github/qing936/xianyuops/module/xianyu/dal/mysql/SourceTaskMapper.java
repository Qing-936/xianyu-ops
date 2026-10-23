package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceTaskPendingInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SourceTaskMapper extends BaseMapperX<SourceTaskDO> {

    /**
     * 汇总「尚无任何商品入库」的货源任务（只看有数据的最新批次）：
     * 每个任务只取有效采集商品所在的最新批次（execution_no 最大）并排除黑名单卖家；
     * 仅当该批次所有商品均未入库（无任何 app_source_inventory 记录与之匹配）时才返回该任务。
     */
    @Select("""
            SELECT t.id AS taskId, t.product_name AS taskName,
                   COUNT(i.id) AS pendingResultCount, MAX(i.execution_no) AS latestExecutionNo,
                   MAX(i.create_time) AS latestExecutionTime,
                   MAX(t.last_viewed_execution_no) AS lastViewedExecutionNo
            FROM app_source_task t
            JOIN app_source_item i ON i.task_id = t.id AND i.is_valid = 1 AND i.deleted = 0
            JOIN app_source_item_detail d ON d.id = (
                SELECT MAX(d2.id) FROM app_source_item_detail d2
                WHERE d2.item_id = i.id AND d2.deleted = 0
            ) AND d.deleted = 0
            WHERE t.deleted = 0
              AND i.execution_no = (
                SELECT MAX(i2.execution_no) FROM app_source_item i2
                WHERE i2.task_id = t.id AND i2.is_valid = 1 AND i2.deleted = 0
              )
              AND NOT (EXISTS (
                SELECT 1 FROM app_source_blocked_seller b
                WHERE b.status = 1 AND b.deleted = 0
                  AND ((d.seller_id IS NOT NULL AND TRIM(d.seller_id) <> ''
                        AND CAST(b.seller_id AS BINARY) = CAST(d.seller_id AS BINARY))
                    OR (i.seller_name IS NOT NULL AND TRIM(i.seller_name) <> ''
                        AND CAST(b.seller_name AS BINARY) = CAST(i.seller_name AS BINARY)))
              ))
              AND NOT EXISTS (
                SELECT 1
                FROM app_source_item ai
                JOIN app_source_item_detail aid ON aid.id = (
                    SELECT MAX(aid2.id) FROM app_source_item_detail aid2
                    WHERE aid2.item_id = ai.id AND aid2.deleted = 0
                ) AND aid.deleted = 0
                JOIN app_source_inventory inv ON inv.deleted = 0 AND (
                    (ai.external_item_id IS NOT NULL AND TRIM(ai.external_item_id) <> ''
                     AND CAST(inv.external_item_id AS BINARY) = CAST(ai.external_item_id AS BINARY))
                    OR inv.source_detail_id = aid.id
                )
                WHERE ai.task_id = t.id AND ai.is_valid = 1 AND ai.deleted = 0
                  AND ai.execution_no = (
                    SELECT MAX(i2.execution_no) FROM app_source_item i2
                    WHERE i2.task_id = t.id AND i2.is_valid = 1 AND i2.deleted = 0
                  )
              )
            GROUP BY t.id, t.product_name
            ORDER BY t.id DESC
            """)
    List<SourceTaskPendingInventoryRespVO> selectPendingInventoryTaskSummaryList();

    default PageResult<SourceTaskDO> selectPage(SourceTaskPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SourceTaskDO>()
                .likeIfPresent(SourceTaskDO::getProductName, reqVO.getProductName())
                .eqIfPresent(SourceTaskDO::getStatus, reqVO.getStatus())
                .eqIfPresent(SourceTaskDO::getTriggerMode, reqVO.getTriggerMode())
                .orderByDesc(SourceTaskDO::getId));
    }

}
