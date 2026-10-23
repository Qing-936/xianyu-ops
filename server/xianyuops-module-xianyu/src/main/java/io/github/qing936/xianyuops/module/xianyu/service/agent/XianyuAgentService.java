package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentAccountRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuAgentEventRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.agent.vo.XianyuMachineAgentRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.agentapi.vo.HeartbeatRespVO;

import java.util.List;

/**
 * 闲鱼机器 Agent 服务（注册/心跳/管理端查询）
 */
public interface XianyuAgentService {

    /**
     * 心跳（注册 + 状态更新 + 账号对齐）。
     * <p>
     * 调用点须在租户上下文内（Agent 侧 Controller 经 TenantUtils.execute 包裹）。
     */
    HeartbeatRespVO heartbeat(HeartbeatReqVO reqVO);

    /** 管理端：Agent 分页（accountCount 为绑定表计数） */
    PageResult<XianyuMachineAgentRespVO> getAgentPage(XianyuMachineAgentPageReqVO reqVO);

    /** 管理端：某 Agent 的闲鱼号列表（含未对齐行，shopName join 主数据） */
    List<XianyuAgentAccountRespVO> getAgentAccounts(Long agentId);

    /** 管理端：某 Agent 的事件流分页 */
    PageResult<XianyuAgentEventRespVO> getAgentEvents(Long agentId, XianyuAgentEventPageReqVO reqVO);

    /** 管理端：重置专属 Token（返回新明文仅显示一次；旧 Token 立即失效） */
    String resetToken(Long agentId);

}
