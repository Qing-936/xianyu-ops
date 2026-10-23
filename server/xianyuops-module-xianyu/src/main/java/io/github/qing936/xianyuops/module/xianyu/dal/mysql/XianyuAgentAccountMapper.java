package io.github.qing936.xianyuops.module.xianyu.dal.mysql;

import io.github.qing936.xianyuops.framework.mybatis.core.mapper.BaseMapperX;
import io.github.qing936.xianyuops.framework.mybatis.core.query.LambdaQueryWrapperX;
import io.github.qing936.xianyuops.module.xianyu.dal.data.XianyuAgentAccountDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface XianyuAgentAccountMapper extends BaseMapperX<XianyuAgentAccountDO> {

    /** 某 Agent 的绑定列表（含 account_id 为 NULL 的未对齐行） */
    default List<XianyuAgentAccountDO> selectByAgentId(Long agentId) {
        return selectList(new LambdaQueryWrapperX<XianyuAgentAccountDO>()
                .eq(XianyuAgentAccountDO::getAgentId, agentId)
                .orderByAsc(XianyuAgentAccountDO::getId));
    }

    /** 按 accountId 查绑定行（agent_id + account_id 唯一） */
    default XianyuAgentAccountDO selectByAgentIdAndAccountId(Long agentId, Long accountId) {
        return selectOne(XianyuAgentAccountDO::getAgentId, agentId,
                XianyuAgentAccountDO::getAccountId, accountId);
    }

    /** 按 accountId 查全部绑定行（外发闸门：账号可能被多 Agent 绑定，MVP 前提唯一 Agent 但查询仍按列表处理） */
    default List<XianyuAgentAccountDO> selectListByAccountId(Long accountId) {
        LambdaQueryWrapperX<XianyuAgentAccountDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(XianyuAgentAccountDO::getAccountId, accountId);
        return selectList(wrapper);
    }

    /** 查未对齐绑定行（account_id IS NULL），双键 (authorize_id, user_nick) 匹配；authorizeId 空时退化为纯 userNick */
    default XianyuAgentAccountDO selectUnbound(Long agentId, String authorizeId, String userNick) {
        LambdaQueryWrapperX<XianyuAgentAccountDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(XianyuAgentAccountDO::getAgentId, agentId);
        wrapper.isNull(XianyuAgentAccountDO::getAccountId);
        wrapper.eq(XianyuAgentAccountDO::getUserNick, userNick);
        if (authorizeId != null && !authorizeId.isEmpty()) {
            wrapper.eq(XianyuAgentAccountDO::getAuthorizeId, authorizeId);
        }
        return selectOne(wrapper);
    }

}
