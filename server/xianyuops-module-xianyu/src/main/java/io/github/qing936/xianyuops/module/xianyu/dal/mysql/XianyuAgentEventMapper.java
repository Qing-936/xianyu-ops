package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentEventDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface XianyuAgentEventMapper extends BaseMapperX<XianyuAgentEventDO> {

    default PageResult<XianyuAgentEventDO> selectPage(Long agentId, XianyuAgentEventPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<XianyuAgentEventDO>()
                .eq(XianyuAgentEventDO::getAgentId, agentId)
                .eqIfPresent(XianyuAgentEventDO::getEventType, reqVO.getEventType())
                .eqIfPresent(XianyuAgentEventDO::getLevel, reqVO.getLevel())
                .orderByDesc(XianyuAgentEventDO::getId));
    }

}
