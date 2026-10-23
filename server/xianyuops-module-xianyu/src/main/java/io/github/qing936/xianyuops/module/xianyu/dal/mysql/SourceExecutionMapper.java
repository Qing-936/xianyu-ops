package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceExecutionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SourceExecutionMapper extends BaseMapperX<SourceExecutionDO> {

    default List<SourceExecutionDO> selectByTaskId(Long taskId) {
        return selectList(new LambdaQueryWrapperX<SourceExecutionDO>()
                .eq(SourceExecutionDO::getTaskId, taskId)
                .orderByDesc(SourceExecutionDO::getExecutionNo));
    }

    default List<SourceExecutionDO> selectByTaskIds(Collection<Long> taskIds) {
        return selectList(new LambdaQueryWrapperX<SourceExecutionDO>()
                .inIfPresent(SourceExecutionDO::getTaskId, taskIds));
    }
}
