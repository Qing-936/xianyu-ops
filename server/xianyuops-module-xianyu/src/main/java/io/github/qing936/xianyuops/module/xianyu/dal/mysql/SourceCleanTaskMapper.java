package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCleanTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SourceCleanTaskMapper extends BaseMapperX<SourceCleanTaskDO> {
    @Select("SELECT * FROM app_source_clean_task WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    SourceCleanTaskDO lock(@Param("id") Long id);

    @Select("SELECT * FROM app_source_clean_task WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND request_id = #{requestId} AND deleted = 0 LIMIT 1")
    SourceCleanTaskDO selectByRequestId(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                                        @Param("requestId") String requestId);
}
