package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCookieDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SourceCookieMapper extends BaseMapperX<SourceCookieDO> {

    default SourceCookieDO selectByUnb(String unb) {
        return selectOne(SourceCookieDO::getUnb, unb);
    }

    @Select("""
            SELECT *
            FROM app_source_cookie
            WHERE deleted = 0
              AND status = 0
              AND (cooldown_until IS NULL OR cooldown_until <= #{now})
            ORDER BY last_refresh_at DESC, last_used_at ASC, id ASC
            """)
    List<SourceCookieDO> selectAvailableList(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE app_source_cookie
            SET last_used_at = #{now},
                updater = 'system'
            WHERE id = #{id}
              AND deleted = 0
            """)
    int touchLastUsedAt(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE app_source_cookie
            SET cookie_json = #{cookieJson},
                status = 0,
                last_refresh_at = #{now},
                cooldown_until = NULL,
                updater = 'system'
            WHERE id = #{id}
              AND deleted = 0
            """)
    int updateRuntimeRefresh(@Param("id") Long id,
                             @Param("cookieJson") String cookieJson,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE app_source_cookie
            SET status = #{status},
                cooldown_until = #{cooldownUntil},
                updater = 'system'
            WHERE id = #{id}
              AND deleted = 0
            """)
    int updateFailureState(@Param("id") Long id,
                           @Param("status") Integer status,
                           @Param("cooldownUntil") LocalDateTime cooldownUntil);
}
