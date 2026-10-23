package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface XianyuAgentMapper extends BaseMapperX<XianyuAgentDO> {

    default PageResult<XianyuAgentDO> selectPage(XianyuMachineAgentPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<XianyuAgentDO>()
                .likeIfPresent(XianyuAgentDO::getName, reqVO.getName())
                .eqIfPresent(XianyuAgentDO::getStatus, reqVO.getStatus())
                .orderByDesc(XianyuAgentDO::getId));
    }

    /** 按机器指纹查 Agent（tenant 内唯一） */
    default XianyuAgentDO selectByMachineCode(String machineCode) {
        return selectOne(XianyuAgentDO::getMachineCode, machineCode);
    }

    /**
     * 按专属 Token 哈希查 Agent（M5 一机一 Token：Filter 精确匹配 token→agentId）。
     * token_hash 为 NULL 的行不参与匹配（未激活）；uk_token_hash 保证至多一行。
     */
    default XianyuAgentDO selectByTokenHash(String tokenHash) {
        return selectOne(XianyuAgentDO::getTokenHash, tokenHash);
    }

    /**
     * 离线判定：online 且心跳超过阈值未更新 → offline（02 号文档 §8-①）。
     * last_heartbeat_at 为 NULL 的行（注册后从未心跳）一并纳入。
     */
    @Update("""
            UPDATE app_xianyu_agent
            SET status = 'offline'
            WHERE status = 'online'
              AND (last_heartbeat_at IS NULL OR last_heartbeat_at < #{beforeTime})
            """)
    int updateOffline(@Param("beforeTime") LocalDateTime beforeTime);

}
