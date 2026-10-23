package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCleanSkillDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SourceCleanSkillMapper extends BaseMapperX<SourceCleanSkillDO> {
    @Insert("INSERT INTO app_source_clean_skill (skill_code, skill_type, skill_name, version, config, customized, enabled, tenant_id) "
            + "VALUES (#{skillCode}, #{skillType}, #{skillName}, #{version}, #{config}, 0, 1, #{tenantId}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    void insertPreset(SourceCleanSkillDO skill);

    @Select("SELECT * FROM app_source_clean_skill WHERE skill_code = #{code} AND deleted = 0 FOR UPDATE")
    SourceCleanSkillDO selectByCodeForUpdate(@Param("code") String code);
}
