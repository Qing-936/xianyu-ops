package io.github.qing936.xianyuops.module.system.api.agent;

import io.github.qing936.xianyuops.module.system.api.agent.dto.AgentActorRespDTO;

/**
 * Agent Actor 信息 API（供其它模块查询当前操作者服务端可信身份）。
 *
 * <p>只允许根据服务端已认证的 userId 查询，绝不接受外部传入的身份声明。
 */
public interface AgentActorApi {

    /**
     * 获取用户最小身份信息（用户名 / 昵称 / 角色标识）。
     *
     * @param userId 已认证用户编号（来自 SecurityFrameworkUtils.getLoginUser()，非前端传入）
     * @return 身份信息；用户不存在时返回 null
     */
    AgentActorRespDTO getActor(Long userId);

}
