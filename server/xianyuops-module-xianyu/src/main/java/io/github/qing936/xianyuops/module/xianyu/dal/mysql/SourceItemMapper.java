package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import cn.hutool.core.collection.CollUtil;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface SourceItemMapper extends BaseMapperX<SourceItemDO> {

    default List<SourceItemDO> selectValidItems(Long taskId, Integer executionNo) {
        return selectList(new LambdaQueryWrapperX<SourceItemDO>()
                .eq(SourceItemDO::getTaskId, taskId)
                .eq(SourceItemDO::getExecutionNo, executionNo)
                .eq(SourceItemDO::getIsValid, true)
                .orderByDesc(SourceItemDO::getCreateTime));
    }

    default List<SourceItemDO> selectPreviousItems(Long taskId, Integer executionNo,
                                                   Collection<String> externalItemIds) {
        if (CollUtil.isEmpty(externalItemIds) || executionNo == null || executionNo <= 1) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<SourceItemDO>()
                .eq(SourceItemDO::getTaskId, taskId)
                .lt(SourceItemDO::getExecutionNo, executionNo)
                .eq(SourceItemDO::getIsValid, true)
                .in(SourceItemDO::getExternalItemId, externalItemIds)
                .orderByDesc(SourceItemDO::getExecutionNo)
                .orderByDesc(SourceItemDO::getId));
    }

    default List<SourceItemDO> selectAllByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<SourceItemDO>()
                .eq(SourceItemDO::getTaskId, taskId)
                .orderByDesc(SourceItemDO::getCreateTime));
    }
}
